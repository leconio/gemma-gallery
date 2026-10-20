import Foundation
import Combine
import UIKit
import PhotosUI
import UniformTypeIdentifiers
import GemmaShared
import LiteRTLM

final class IOSServices: NSObject, ObservableObject, PlatformServices, UIDocumentPickerDelegate {
    let filesDirectory: String
    let platformLabel: String
    let initialBackend: String
    let runtime: EmbeddingRuntime = IOSRuntime()
    let photos: SystemPhotoLibrary = IOSPhotos()
    let index: PhotoIndex
    weak var presenter: UIViewController? { didSet { (photos as? IOSPhotos)?.presenter = presenter } }
    private var modelCallback: ModelImportCallback?
    private var transfer: ModelTransfer?
    override init() {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("GemmaPocket", isDirectory: true)
        try! FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var excluded = folder; var resources = URLResourceValues(); resources.isExcludedFromBackup = true
        try? excluded.setResourceValues(resources)
        filesDirectory = folder.path
        index = IOSPhotoIndex(directory: folder)
        #if targetEnvironment(simulator)
        platformLabel = "iOS \(UIDevice.current.systemVersion) · Simulator (Apple Silicon)"
        #else
        platformLabel = "iOS \(UIDevice.current.systemVersion) · \(UIDevice.current.model)"
        #endif
        initialBackend = ProcessInfo.processInfo.arguments.contains("--gpu") ? "GPU" : "CPU"
        super.init()
    }
    func prepareModel(observer: ModelObserver) -> ModelJob {
        let job = ModelTransfer(directory: URL(fileURLWithPath: filesDirectory).appendingPathComponent("models"), observer: observer)
        transfer = job; job.start(); return job
    }
    func pickModel(callback: ModelImportCallback) {
        DispatchQueue.main.async {
            guard let presenter = self.presenter else { callback.finished(imported: false, error: "File picker is unavailable"); return }
            self.modelCallback = callback
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: false)
            picker.delegate = self; presenter.present(picker, animated: true)
        }
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        modelCallback?.finished(imported: false, error: nil); modelCallback = nil
    }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let callback = modelCallback else { return }; modelCallback = nil
        guard let url = urls.first else { callback.finished(imported: false, error: nil); return }
        let directory = URL(fileURLWithPath: filesDirectory).appendingPathComponent("models", isDirectory: true)
        DispatchQueue.global(qos: .utility).async {
            let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
            let partial = directory.appendingPathComponent("model.import.part")
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                guard try url.resourceValues(forKeys: [.fileSizeKey]).fileSize == Int(ModelSpec.shared.bytes) else { throw AppError("Select the original 388 MB model file") }
                try? FileManager.default.removeItem(at: partial)
                try FileManager.default.copyItem(at: url, to: partial)
                try ModelTransfer.verify(partial)
                try ModelTransfer.install(partial, directory.appendingPathComponent(ModelSpec.shared.filename))
                DispatchQueue.main.async { callback.finished(imported: true, error: nil) }
            } catch {
                try? FileManager.default.removeItem(at: partial)
                DispatchQueue.main.async { callback.finished(imported: false, error: error.localizedDescription) }
            }
        }
    }
    func log(message: String) { NSLog("GemmaPocket %@", message) }
}

private final class IOSRuntime: EmbeddingRuntime {
    private var engine: LiteRTLM.EmbeddingEngine?
    func initialize(modelPath: String, backend: String, callback: OperationCallback) {
        Task {
            if let engine { await engine.close() }; engine = nil
            let selected: LiteRTLM.Backend = backend == "GPU" ? .gpu : .cpu(threadCount: 4)
            let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("GemmaPocket", isDirectory: true)
            try? FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
            let next = LiteRTLM.EmbeddingEngine(config: EmbeddingEngineConfig(modelPath: modelPath, backend: selected,
                visionBackend: selected, cacheDir: cache.path, maxInputLength: 256, visionTokensPerImage: 70))
            do {
                try await next.initialize(); engine = next
                #if targetEnvironment(simulator)
                if ProcessInfo.processInfo.arguments.contains("--search-score-audit") {
                    try await auditSearchScores(next, modelPath: modelPath)
                }
                #endif
                await MainActor.run { callback.finished(error: nil) }
            }
            catch { await next.close(); await MainActor.run { callback.finished(error: "\(backend) initialization failed: \(error.localizedDescription)") } }
        }
    }
    func embedText(text: String, callback: VectorCallback) { compute(.text(text), callback: callback) }
    func embedImage(path: String, callback: VectorCallback) { compute(.imageFile(path), callback: callback) }
    private func compute(_ content: LiteRTLM.Content, callback: VectorCallback) {
        Task {
            do {
                guard let engine else { throw AppError("The model is not loaded") }
                let response = try await engine.computeEmbedding(contents: [content], options: EmbeddingOptions(
                    normalize: true, insertSpecialTokens: true, outputSize: 768, visionTokensPerImage: 70))
                let vector = KotlinFloatArray(size: Int32(response.embedding.count))
                for (i, value) in response.embedding.enumerated() { vector.set(index: Int32(i), value: value) }
                await MainActor.run { callback.finished(vector: vector, error: nil) }
            } catch { await MainActor.run { callback.finished(vector: nil, error: error.localizedDescription) } }
        }
    }
    func close() { let old = engine; engine = nil; Task { await old?.close() } }

    #if targetEnvironment(simulator)
    // Opt-in fixture evaluation only. Normal app launches never record query embeddings.
    private func auditSearchScores(_ engine: LiteRTLM.EmbeddingEngine, modelPath: String) async throws {
        let directory = URL(fileURLWithPath: modelPath).deletingLastPathComponent().deletingLastPathComponent()
        let input = directory.appendingPathComponent("search-audit-queries.json")
        let queries = try JSONDecoder().decode([String].self, from: Data(contentsOf: input))
        var rows = [[String: Any]]()
        for query in queries {
            let response = try await engine.computeEmbedding(contents: [.text(ModelSpec.shared.query(text: query))], options: EmbeddingOptions(
                normalize: true, insertSpecialTokens: true, outputSize: 768, visionTokensPerImage: 70))
            rows.append(["query": query, "vector": response.embedding])
        }
        let data = try JSONSerialization.data(withJSONObject: ["model": ModelSpec.shared.sha256,
            "minimumSimilarity": SearchRelevance.shared.minimumSimilarity, "queries": rows], options: [.prettyPrinted, .sortedKeys])
        try data.write(to: directory.appendingPathComponent("search-score-audit.json"), options: .atomic)
    }
    #endif
}

struct AppError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
