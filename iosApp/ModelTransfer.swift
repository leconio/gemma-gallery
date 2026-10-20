import Foundation
import CryptoKit
import Darwin
import GemmaShared

/// A bounded streaming transfer. A model enters the installed path only after SHA-256 verification.
final class ModelTransfer: NSObject, ModelJob, URLSessionDataDelegate {
    private let directory: URL
    private let observer: ModelObserver
    private let queue: OperationQueue = { let q = OperationQueue(); q.maxConcurrentOperationCount = 1; q.qualityOfService = .utility; return q }()
    private var session: URLSession!
    private var task: URLSessionDataTask?
    private var writer: FileHandle?
    private var cancelled = false
    private var completed = false
    private var attempt = 0
    private var received: Int64 = 0
    private var failure: Error?
    private var lastProgress: Int64 = 0
    private var destination: URL { directory.appendingPathComponent(ModelSpec.shared.filename) }
    private var partial: URL { directory.appendingPathComponent(ModelSpec.shared.filename + ".download.part") }
    init(directory: URL, observer: ModelObserver) { self.directory = directory; self.observer = observer; super.init() }
    func start() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 60; config.timeoutIntervalForResource = 3600
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: config, delegate: self, delegateQueue: queue)
        queue.addOperation { [self] in
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                if FileManager.default.fileExists(atPath: destination.path) {
                    observer.status(message: "Verifying local model...")
                    do { try Self.verify(destination); finish(path: destination.path); return }
                    catch { try FileManager.default.removeItem(at: destination) }
                }
                beginAttempt()
            } catch { finish(error: error) }
        }
    }
    func cancel() { queue.addOperation { [self] in cancelled = true; if let task { task.cancel() } else { finish() } } }
    private func beginAttempt() {
        if cancelled { finish(); return }
        failure = nil
        do {
            received = Self.size(partial)
            if received > ModelSpec.shared.bytes { try FileManager.default.removeItem(at: partial); received = 0 }
            if received == ModelSpec.shared.bytes { try finishDownload(); return }
            let free = try directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
            guard free > ModelSpec.shared.bytes - received + 16 * 1024 * 1024 else { throw AppError("Free at least 410 MB and try again.") }
            if !FileManager.default.fileExists(atPath: partial.path) { FileManager.default.createFile(atPath: partial.path, contents: nil) }
            writer = try FileHandle(forWritingTo: partial); try writer?.seekToEnd()
            let source = attempt == 0 ? ModelSpec.shared.source : ModelSpec.shared.mirror
            var request = URLRequest(url: URL(string: source)!)
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            request.setValue("GemmaPocket/0.4", forHTTPHeaderField: "User-Agent")
            if received > 0 { request.setValue("bytes=\(received)-", forHTTPHeaderField: "Range") }
            observer.status(message: "Connecting to \(request.url!.host!)...")
            observer.progress(received: received, total: ModelSpec.shared.bytes)
            task = session.dataTask(with: request); task!.resume()
        } catch { retryOrFinish(error) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard request.url?.scheme == "https" else {
            failure = AppError("The model download requires HTTPS"); completionHandler(nil); return
        }
        completionHandler(request)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        do {
            guard let response = response as? HTTPURLResponse, response.url?.scheme == "https" else { throw AppError("Invalid HTTPS response") }
            let remaining: Int64
            if response.statusCode == 200 {
                // A server may ignore Range. Restart the local partial before accepting its full response.
                if received != 0 { try writer?.truncate(atOffset: 0); try writer?.seek(toOffset: 0); received = 0 }
                remaining = ModelSpec.shared.bytes
            } else if response.statusCode == 206 {
                guard let range = response.value(forHTTPHeaderField: "Content-Range"),
                      range == "bytes \(received)-\(ModelSpec.shared.bytes - 1)/\(ModelSpec.shared.bytes)" else { throw AppError("Invalid download range") }
                remaining = ModelSpec.shared.bytes - received
            } else { throw AppError("Model server returned HTTP \(response.statusCode)") }
            if response.expectedContentLength >= 0 && response.expectedContentLength != remaining { throw AppError("Unexpected model size") }
            if let encoding = response.value(forHTTPHeaderField: "Content-Encoding"), encoding.lowercased() != "identity" { throw AppError("Unexpected model encoding") }
            lastProgress = 0; completionHandler(.allow)
        } catch { failure = error; completionHandler(.cancel) }
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard !cancelled, failure == nil else { dataTask.cancel(); return }
        do {
            guard received + Int64(data.count) <= ModelSpec.shared.bytes else { throw AppError("The downloaded model is too large") }
            try writer?.write(contentsOf: data); received += Int64(data.count)
            if received - lastProgress >= 1024 * 1024 || received == ModelSpec.shared.bytes {
                observer.progress(received: received, total: ModelSpec.shared.bytes); lastProgress = received
            }
        } catch { failure = error; dataTask.cancel() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        try? writer?.close(); writer = nil; self.task = nil
        if cancelled { finish(); return }
        if let error = failure ?? error { retryOrFinish(error); return }
        do { try finishDownload() } catch { retryOrFinish(error) }
    }
    private func finishDownload() throws {
        observer.status(message: "Verifying download...")
        do { try Self.verify(partial) }
        catch {
            // Incomplete transfers remain resumable; a full corrupt transfer must restart.
            if Self.size(partial) >= ModelSpec.shared.bytes { try? FileManager.default.removeItem(at: partial) }
            throw error
        }
        try Self.install(partial, destination); finish(path: destination.path)
    }
    private func retryOrFinish(_ error: Error) {
        try? writer?.close(); writer = nil
        if cancelled { finish() }
        else if attempt == 0 { attempt = 1; beginAttempt() }
        else { finish(error: AppError("Download could not be completed. Tap Retry to resume. \(error.localizedDescription)")) }
    }
    private func finish(path: String? = nil, error: Error? = nil) {
        guard !completed else { return }; completed = true
        try? writer?.close(); writer = nil
        observer.finished(modelPath: path, error: error?.localizedDescription, cancelled: cancelled)
        session.finishTasksAndInvalidate()
    }
    static func size(_ file: URL) -> Int64 { (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0 }
    static func verify(_ file: URL) throws {
        guard size(file) == ModelSpec.shared.bytes else { throw AppError("The model file is incomplete") }
        let reader = try FileHandle(forReadingFrom: file); defer { try? reader.close() }
        var hash = SHA256()
        while let data = try reader.read(upToCount: 1024 * 1024), !data.isEmpty { hash.update(data: data) }
        let value = hash.finalize().map { String(format: "%02x", $0) }.joined()
        guard value == ModelSpec.shared.sha256 else { throw AppError("The model checksum does not match") }
    }
    static func install(_ partial: URL, _ destination: URL) throws {
        // POSIX rename replaces only the app's model, atomically on the same private filesystem.
        guard rename(partial.path, destination.path) == 0 else { throw AppError("Could not save the verified model") }
    }
}
