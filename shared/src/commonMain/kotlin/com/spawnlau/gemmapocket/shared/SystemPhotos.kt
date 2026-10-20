package com.spawnlau.gemmapocket.shared

import okio.Path.Companion.toPath

data class SystemPhoto(
    val id: String, val title: String, val revision: String,
    val albumIds: List<String>, val width: Int, val height: Int, val bytes: Long, val takenAt: Long
)
data class SystemAlbum(val id: String, val title: String)
data class PhotoSnapshot(
    val access: String, val photos: List<SystemPhoto>, val albums: List<SystemAlbum>,
    val canOrganize: Boolean, val canDelete: Boolean, val organizeLabel: String, val deleteLabel: String
)
interface PhotoSnapshotCallback { fun finished(snapshot: PhotoSnapshot?, error: String?) }
interface PhotoBytesCallback { fun finished(bytes: ByteArray?, error: String?) }
interface PhotoFileCallback { fun finished(path: String?, error: String?) }
interface PhotoChangeObserver { fun changed() }
interface PhotoActionCallback { fun finished(cancelled: Boolean, error: String?) }

/** IDs always identify provider-owned photos. Paths exist only for short-lived inference inputs. */
interface SystemPhotoLibrary {
    fun requestAccess(callback: PhotoSnapshotCallback)
    fun refresh(callback: PhotoSnapshotCallback)
    fun observe(observer: PhotoChangeObserver?)
    fun openSettings()
    fun thumbnail(id: String, pixels: Int, callback: PhotoBytesCallback)
    fun prepareImage(id: String, callback: PhotoFileCallback)
    fun releaseImage(path: String)
    fun deletePhotos(ids: List<String>, callback: PhotoActionCallback)
    fun organizePhotos(ids: List<String>, albumId: String?, newName: String?, callback: PhotoActionCallback)
    fun sharePhotos(ids: List<String>, callback: PhotoActionCallback)
    fun shareReport(report: String, callback: PhotoActionCallback) { callback.finished(false, "Report sharing is unavailable") }
    fun close()
}

data class IndexedPhoto(
    val id: String, val revision: String, val vector: FloatArray?,
    val readMs: Double, val embeddingMs: Double, val error: String
)

/** Native SQLite implementations share this contract and the same binary vector format. */
interface PhotoIndex {
    @Throws(Exception::class)
    fun load(): List<IndexedPhoto>
    @Throws(Exception::class)
    fun save(photo: IndexedPhoto)
    @Throws(Exception::class)
    fun retain(ids: List<String>)
    fun close()
}

object PhotoVectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        validateVector(vector)
        return ByteArray(vector.size * 4).also { bytes ->
            vector.forEachIndexed { i, value ->
                val bits = value.toBits()
                repeat(4) { n -> bytes[i * 4 + n] = (bits ushr (n * 8)).toByte() }
            }
        }
    }
    @Throws(IllegalArgumentException::class)
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size == ModelSpec.dimensions * 4) { "Invalid stored vector size" }
        return FloatArray(ModelSpec.dimensions) { i ->
            var bits = 0
            repeat(4) { n -> bits = bits or ((bytes[i * 4 + n].toInt() and 255) shl (n * 8)) }
            Float.fromBits(bits)
        }.also(::validateVector)
    }
}

internal fun indexRevision(photo: SystemPhoto) = "${ModelSpec.vectorVersion}|jpeg1024-v1|${photo.revision}"
internal fun usableIndex(photo: SystemPhoto, cached: IndexedPhoto?): IndexedPhoto? =
    cached?.takeIf { it.revision == indexRevision(photo) && it.vector != null }

/** Called only on an app-owned directory. Legacy copies remain recoverable and are never system assets. */
internal fun migratePrivateIndex(directory: String) {
    val fs = okio.FileSystem.SYSTEM
    val root = directory.toPath()
    val old = root / "index.json"
    val backup = root / "legacy-private-index.json"
    if (fs.exists(old) && !fs.exists(backup)) fs.copy(old, backup)
}
