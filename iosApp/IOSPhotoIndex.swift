import Foundation
import SQLite3
import GemmaShared

final class IOSPhotoIndex: PhotoIndex {
    private var db: OpaquePointer?
    private let lock = NSLock()
    private var openingError: Error?
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    init(directory: URL) {
        guard sqlite3_open_v2(directory.appendingPathComponent("system-photo-index.sqlite").path, &db,
            SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            openingError = AppError("Cannot open the local photo index. Check available storage."); return
        }
        sqlite3_busy_timeout(db, 5000)
        if sqlite3_exec(db, "CREATE TABLE IF NOT EXISTS photo_index (asset_id TEXT PRIMARY KEY, revision TEXT NOT NULL, vector BLOB, read_ms REAL NOT NULL, embed_ms REAL NOT NULL, error TEXT NOT NULL)", nil, nil, nil) != SQLITE_OK {
            openingError = AppError("Cannot prepare the local photo index. Check available storage.")
        }
    }
    private func statement(_ sql: String) throws -> OpaquePointer {
        if let openingError { throw openingError }
        guard db != nil else { throw AppError("The photo index is closed") }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { throw AppError("Cannot read the local photo index") }
        return stmt
    }
    func load() throws -> [IndexedPhoto] {
        lock.lock(); defer { lock.unlock() }
        let stmt = try statement("SELECT * FROM photo_index"); defer { sqlite3_finalize(stmt) }
        var rows = [IndexedPhoto]()
        while sqlite3_step(stmt) == SQLITE_ROW {
            let id = String(cString: sqlite3_column_text(stmt, 0)); let revision = String(cString: sqlite3_column_text(stmt, 1))
            var vector: KotlinFloatArray?
            if let blob = sqlite3_column_blob(stmt, 2), sqlite3_column_bytes(stmt, 2) == 3072 {
                // Stored vectors are written only after shared validation.
                vector = try? PhotoVectorCodec.shared.decode(bytes: kotlinBytes(Data(bytes: blob, count: 3072)))
            }
            rows.append(IndexedPhoto(id: id, revision: revision, vector: vector, readMs: sqlite3_column_double(stmt, 3),
                embeddingMs: sqlite3_column_double(stmt, 4), error: String(cString: sqlite3_column_text(stmt, 5))))
        }
        return rows
    }
    func save(photo: IndexedPhoto) throws {
        lock.lock(); defer { lock.unlock() }
        let stmt = try statement("INSERT OR REPLACE INTO photo_index VALUES (?, ?, ?, ?, ?, ?)"); defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, photo.id, -1, transient); sqlite3_bind_text(stmt, 2, photo.revision, -1, transient)
        if let vector = photo.vector {
            let data = swiftData(PhotoVectorCodec.shared.encode(vector: vector))
            data.withUnsafeBytes { buffer in _ = sqlite3_bind_blob(stmt, 3, buffer.baseAddress, Int32(data.count), transient) }
        } else { sqlite3_bind_null(stmt, 3) }
        sqlite3_bind_double(stmt, 4, photo.readMs); sqlite3_bind_double(stmt, 5, photo.embeddingMs)
        sqlite3_bind_text(stmt, 6, photo.error, -1, transient)
        guard sqlite3_step(stmt) == SQLITE_DONE else { throw AppError("Cannot save the photo index. Check available storage.") }
    }
    func retain(ids: [String]) throws {
        lock.lock(); defer { lock.unlock() }
        let allowed = Set(ids); let query = try statement("SELECT asset_id FROM photo_index")
        var obsolete = [String]()
        while sqlite3_step(query) == SQLITE_ROW {
            let id = String(cString: sqlite3_column_text(query, 0)); if !allowed.contains(id) { obsolete.append(id) }
        }
        sqlite3_finalize(query)
        guard sqlite3_exec(db, "BEGIN IMMEDIATE", nil, nil, nil) == SQLITE_OK else { throw AppError("Cannot update the local photo index") }
        do {
            let deletion = try statement("DELETE FROM photo_index WHERE asset_id = ?"); defer { sqlite3_finalize(deletion) }
            for id in obsolete {
                sqlite3_reset(deletion); sqlite3_bind_text(deletion, 1, id, -1, transient)
                guard sqlite3_step(deletion) == SQLITE_DONE else { throw AppError("Cannot remove an obsolete index entry") }
            }
            guard sqlite3_exec(db, "COMMIT", nil, nil, nil) == SQLITE_OK else { throw AppError("Cannot save index changes") }
        } catch { sqlite3_exec(db, "ROLLBACK", nil, nil, nil); throw error }
    }
    func close() { lock.lock(); defer { lock.unlock() }; if let db { sqlite3_close(db) }; db = nil }
    deinit { close() }
}
