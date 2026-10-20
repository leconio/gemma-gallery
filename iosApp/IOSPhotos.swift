import Foundation
import UIKit
import Photos
import UniformTypeIdentifiers
import GemmaShared

func kotlinBytes(_ data: Data) -> KotlinByteArray {
    let bytes = KotlinByteArray(size: Int32(data.count))
    data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
        for i in buffer.indices { bytes.set(index: Int32(i), value: Int8(bitPattern: buffer[i])) }
    }
    return bytes
}
func swiftData(_ bytes: KotlinByteArray) -> Data {
    var values = [UInt8](repeating: 0, count: Int(bytes.size))
    for i in values.indices { values[i] = UInt8(bitPattern: bytes.get(index: Int32(i))) }
    return Data(values)
}

final class IOSPhotos: NSObject, SystemPhotoLibrary, PHPhotoLibraryChangeObserver {
    weak var presenter: UIViewController?
    private let worker = DispatchQueue(label: "GemmaPocket.SystemPhotos", qos: .userInitiated)
    private lazy var manager = PHCachingImageManager()
    private var registered = false
    private let cache = NSCache<NSString, NSData>()
    private var observer: PhotoChangeObserver?
    private var activeToken: NSObjectProtocol?
    private let inputs = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("photo-inference", isDirectory: true)
    override init() {
        super.init()
        cache.totalCostLimit = 8 * 1024 * 1024
        try? FileManager.default.createDirectory(at: inputs, withIntermediateDirectories: true)
        let stale = (try? FileManager.default.contentsOfDirectory(at: inputs, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for file in stale where ((try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantFuture) < Date().addingTimeInterval(-86400) {
            try? FileManager.default.removeItem(at: file)
        }
        activeToken = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.observer?.changed() }
    }
    func observe(observer: PhotoChangeObserver?) { self.observer = observer }
    func openSettings() { DispatchQueue.main.async {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    } }
    func photoLibraryDidChange(_ changeInstance: PHChange) {
        cache.removeAllObjects(); DispatchQueue.main.async { self.observer?.changed() }
    }
    func requestAccess(callback: PhotoSnapshotCallback) {
        DispatchQueue.main.async {
            if PHPhotoLibrary.authorizationStatus(for: .readWrite) == .denied {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                self.refresh(callback: callback)
            } else if PHPhotoLibrary.authorizationStatus(for: .readWrite) == .limited, let presenter = self.presenter {
                PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: presenter) { _ in self.refresh(callback: callback) }
            } else { PHPhotoLibrary.requestAuthorization(for: .readWrite) { _ in self.refresh(callback: callback) } }
        }
    }
    func refresh(callback: PhotoSnapshotCallback) { worker.async {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if (status == .authorized || status == .limited) && !self.registered {
            PHPhotoLibrary.shared().register(self); self.registered = true
        }
        let access = status == .authorized ? "full" : status == .limited ? "limited" : "denied"
        self.cache.removeAllObjects()
        var photos = [SystemPhoto](); var albums = [SystemAlbum](); var membership = [String: [String]]()
        if status == .authorized {
            PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil).enumerateObjects { album, _, _ in
                albums.append(SystemAlbum(id: album.localIdentifier, title: album.localizedTitle ?? "Album"))
                PHAsset.fetchAssets(in: album, options: nil).enumerateObjects { photo, _, _ in
                    membership[photo.localIdentifier, default: []].append(album.localIdentifier)
                }
            }
        }
        if status == .authorized || status == .limited {
            let options = PHFetchOptions(); options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            PHAsset.fetchAssets(with: .image, options: options).enumerateObjects { photo, _, _ in
                let modified = (photo.modificationDate ?? photo.creationDate ?? .distantPast).timeIntervalSince1970
                let title = PHAssetResource.assetResources(for: photo).first?.originalFilename ?? "Photo"
                photos.append(SystemPhoto(id: photo.localIdentifier, title: title,
                    revision: "\(modified):\(photo.pixelWidth):\(photo.pixelHeight)", albumIds: membership[photo.localIdentifier] ?? [],
                    width: Int32(photo.pixelWidth), height: Int32(photo.pixelHeight), bytes: 0,
                    takenAt: Int64((photo.creationDate?.timeIntervalSince1970 ?? 0) * 1000)))
            }
        }
        #if targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--photo-audit") {
            let audit: [String: Any] = ["access": access,
                "photos": photos.map { ["id": $0.id, "title": $0.title, "revision": $0.revision, "albums": $0.albumIds] },
                "albums": albums.map { ["id": $0.id, "title": $0.title] }]
            if let bytes = try? JSONSerialization.data(withJSONObject: audit, options: [.prettyPrinted, .sortedKeys]) {
                let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("GemmaPocket")
                try? bytes.write(to: root.appendingPathComponent("system-photo-audit.json"), options: .atomic)
            }
        }
        #endif
        callback.finished(snapshot: PhotoSnapshot(access: access, photos: photos, albums: albums.sorted { $0.title < $1.title },
            canOrganize: status == .authorized, canDelete: status == .authorized || status == .limited,
            organizeLabel: "Add to album", deleteLabel: "Delete from Photos"), error: nil)
    } }
    private func assets(_ ids: [String]) throws -> PHFetchResult<PHAsset> {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard status == .authorized || status == .limited else { throw AppError("Photo access was revoked") }
        let result = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
        guard !ids.isEmpty && result.count == Set(ids).count else { throw AppError("Some photos are no longer available") }
        return result
    }
    private func image(_ id: String, pixels: Int, completion: @escaping (UIImage?, String?) -> Void) {
        do {
            let asset = try assets([id]).object(at: 0)
            let options = PHImageRequestOptions(); options.isNetworkAccessAllowed = false
            options.deliveryMode = .highQualityFormat; options.resizeMode = .exact; options.version = .current
            manager.requestImage(for: asset, targetSize: CGSize(width: pixels, height: pixels), contentMode: .aspectFit, options: options) { image, info in
                if info?[PHImageResultIsDegradedKey] as? Bool == true { return }
                if let image { completion(image, nil) }
                else { completion(nil, (info?[PHImageResultIsInCloudKey] as? Bool == true) ? "Download this iCloud photo in Photos, then resume scanning." : "Photo is unavailable") }
            }
        } catch { completion(nil, error.localizedDescription) }
    }
    func thumbnail(id: String, pixels: Int32, callback: PhotoBytesCallback) {
        let key = "\(id):\(pixels)" as NSString
        if let data = cache.object(forKey: key) { callback.finished(bytes: kotlinBytes(data as Data), error: nil); return }
        worker.async { self.image(id, pixels: Int(pixels)) { image, error in self.worker.async {
            guard let data = image?.jpegData(compressionQuality: 0.75) else { callback.finished(bytes: nil, error: error); return }
            self.cache.setObject(data as NSData, forKey: key, cost: data.count)
            callback.finished(bytes: kotlinBytes(data), error: nil)
        } } }
    }
    func prepareImage(id: String, callback: PhotoFileCallback) { worker.async {
        self.image(id, pixels: 1024) { image, error in self.worker.async {
            guard let image else { callback.finished(path: nil, error: error); return }
            let scale = min(1, 1024 / max(image.size.width, image.size.height))
            let size = CGSize(width: max(1, image.size.width * scale), height: max(1, image.size.height * scale))
            let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
            let rendered = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            let file = self.inputs.appendingPathComponent("input-\(UUID().uuidString).jpg")
            do {
                guard let data = rendered.jpegData(compressionQuality: 0.9) else { throw AppError("Cannot prepare photo") }
                try data.write(to: file, options: .atomic); callback.finished(path: file.path, error: nil)
            } catch { callback.finished(path: nil, error: error.localizedDescription) }
        } }
    } }
    func releaseImage(path: String) {
        let file = URL(fileURLWithPath: path).standardizedFileURL
        if file.deletingLastPathComponent() == inputs.standardizedFileURL { try? FileManager.default.removeItem(at: file) }
    }
    private func completed(_ success: Bool, _ error: Error?, _ callback: PhotoActionCallback) {
        let cancelled = (error as NSError?)?.code == NSUserCancelledError
        callback.finished(cancelled: cancelled, error: success || cancelled ? nil : (error?.localizedDescription ?? "System operation failed"))
    }
    func deletePhotos(ids: [String], callback: PhotoActionCallback) {
        do {
            guard ids.count <= 100 else { throw AppError("Select up to 100 photos") }
            let photos = try assets(ids)
            var deletable = true
            photos.enumerateObjects { photo, _, _ in if !photo.canPerform(.delete) { deletable = false } }
            guard deletable else { throw AppError("Some selected photos cannot be deleted from this library") }
            PHPhotoLibrary.shared().performChanges({ PHAssetChangeRequest.deleteAssets(photos) }) { success, error in self.completed(success, error, callback) }
        } catch { callback.finished(cancelled: false, error: error.localizedDescription) }
    }
    func organizePhotos(ids: [String], albumId: String?, newName: String?, callback: PhotoActionCallback) {
        do {
            guard PHPhotoLibrary.authorizationStatus(for: .readWrite) == .authorized else { throw AppError("Full Photos access is required to organize albums") }
            guard ids.count <= 100 else { throw AppError("Select up to 100 photos") }
            let photos = try assets(ids)
            let album = albumId.flatMap { PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [$0], options: nil).firstObject }
            let name = newName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard album != nil || (!name.isEmpty && name.count <= 80) else { throw AppError("Choose an album or enter a name") }
            if let album, !album.canPerform(.addContent) { throw AppError("This album cannot be changed") }
            PHPhotoLibrary.shared().performChanges({
                let request = album.map { PHAssetCollectionChangeRequest(for: $0) } ?? PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: name)
                request?.addAssets(photos)
            }) { success, error in self.completed(success, error, callback) }
        } catch { callback.finished(cancelled: false, error: error.localizedDescription) }
    }
    func sharePhotos(ids: [String], callback: PhotoActionCallback) { worker.async {
        do {
            let photos = try self.assets(ids)
            guard photos.count <= 100 else { throw AppError("Select up to 100 photos") }
            let directory = self.inputs.appendingPathComponent("share-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var files = [URL](); var bytes = 0
            func stop(_ error: String) {
                try? FileManager.default.removeItem(at: directory)
                callback.finished(cancelled: false, error: error)
            }
            // Read compressed data sequentially, never decode a batch of full-size UIImages into RAM.
            func next(_ offset: Int) {
                if offset == photos.count {
                    DispatchQueue.main.async {
                        guard let presenter = self.presenter else { stop("Share sheet is unavailable"); return }
                        let sheet = UIActivityViewController(activityItems: files, applicationActivities: nil)
                        sheet.popoverPresentationController?.sourceView = presenter.view
                        sheet.completionWithItemsHandler = { _, completed, _, error in
                            try? FileManager.default.removeItem(at: directory)
                            callback.finished(cancelled: !completed, error: error?.localizedDescription)
                        }
                        presenter.present(sheet, animated: true)
                    }
                    return
                }
                let options = PHImageRequestOptions(); options.isNetworkAccessAllowed = false; options.version = .current
                self.manager.requestImageDataAndOrientation(for: photos.object(at: offset), options: options) { data, uti, _, _ in self.worker.async {
                    guard let data else { stop("Download selected cloud photos in Photos before sharing"); return }
                    bytes += data.count
                    guard bytes <= 256 * 1024 * 1024 else { stop("Share fewer photos at a time (256 MB limit)"); return }
                    let file = directory.appendingPathComponent("Photo-\(offset + 1)." + (uti.flatMap { UTType($0)?.preferredFilenameExtension } ?? "jpg"))
                    do { try data.write(to: file, options: .atomic); files.append(file); next(offset + 1) }
                    catch { stop(error.localizedDescription) }
                } }
            }
            next(0)
        } catch { callback.finished(cancelled: false, error: error.localizedDescription) }
    } }
    func shareReport(report: String, callback: PhotoActionCallback) { worker.async {
        let file = self.inputs.appendingPathComponent("Lab-\(UUID().uuidString).json")
        do {
            guard report.utf8.count <= 4_000_000 else { throw AppError("Report is too large") }
            try Data(report.utf8).write(to: file, options: .atomic)
            DispatchQueue.main.async {
                guard let presenter = self.presenter else {
                    try? FileManager.default.removeItem(at: file)
                    callback.finished(cancelled: false, error: "Share sheet is unavailable"); return
                }
                let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
                sheet.popoverPresentationController?.sourceView = presenter.view
                sheet.completionWithItemsHandler = { _, completed, _, error in
                    try? FileManager.default.removeItem(at: file)
                    callback.finished(cancelled: !completed, error: error?.localizedDescription)
                }
                presenter.present(sheet, animated: true)
            }
        } catch { try? FileManager.default.removeItem(at: file); callback.finished(cancelled: false, error: error.localizedDescription) }
    } }
    func close() {
        observer = nil
        if registered { PHPhotoLibrary.shared().unregisterChangeObserver(self); manager.stopCachingImagesForAllAssets(); registered = false }
        if let activeToken { NotificationCenter.default.removeObserver(activeToken) }
        cache.removeAllObjects()
    }
    deinit { close() }
}
