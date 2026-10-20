package com.spawnlau.gemmapocket

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.spawnlau.gemmapocket.shared.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

internal class AndroidPhotos(private val activity: MainActivity) : SystemPhotoLibrary {
    private val resolver = activity.contentResolver
    private val io = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val inputs = File(activity.cacheDir, "photo-inference").apply { mkdirs() }
    private var observer: PhotoChangeObserver? = null
    private var accessCallback: PhotoSnapshotCallback? = null
    private var actionCallback: PhotoActionCallback? = null
    private var afterConsent: (() -> Unit)? = null
    private var shareCallback: PhotoActionCallback? = null
    private var reportFile: File? = null
    private val share = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        reportFile?.delete(); reportFile = null
        shareCallback?.finished(false, null); shareCallback = null
    }
    private val changes = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { notifyChange() }
    }
    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        accessCallback?.let { accessCallback = null; refresh(it) }
    }
    private val consent = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val callback = actionCallback ?: return@registerForActivityResult
        val continuation = afterConsent; actionCallback = null; afterConsent = null
        if (result.resultCode != Activity.RESULT_OK) callback.finished(true, null)
        else if (continuation != null) io.execute { try { continuation(); callback.finished(false, null) }
            catch (e: Exception) { callback.finished(false, e.message ?: "System photo operation failed") } }
        else callback.finished(false, null)
    }
    init {
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, changes)
        inputs.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
    }
    private fun access(): String {
        fun granted(name: String) = activity.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> "full"
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "limited"
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> "full"
            else -> "denied"
        }
    }
    override fun requestAccess(callback: PhotoSnapshotCallback) {
        activity.runOnUiThread {
            if (accessCallback != null) { callback.finished(null, "A permission request is already open"); return@runOnUiThread }
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            val settings = activity.getPreferences(0)
            if (access() == "denied" && settings.getBoolean("asked-photo-access", false) && !activity.shouldShowRequestPermissionRationale(permission)) {
                activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                refresh(callback)
                return@runOnUiThread
            }
            settings.edit().putBoolean("asked-photo-access", true).apply()
            accessCallback = callback
            permissions.launch(when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            })
        }
    }
    override fun refresh(callback: PhotoSnapshotCallback) { io.execute {
        try {
            cache.evictAll()
            val permission = access()
            val photos = mutableListOf<SystemPhoto>(); val albums = linkedMapOf<String, SystemAlbum>()
            if (permission != "denied") {
                val columns = mutableListOf("_id", "_display_name", "date_modified", "_size", "width", "height", "datetaken", "bucket_display_name")
                if (Build.VERSION.SDK_INT >= 29) columns += listOf("relative_path", "volume_name")
                resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns.toTypedArray(), null, null, "datetaken DESC, _id DESC")?.use { cursor ->
                    fun str(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name)) ?: ""
                    fun num(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                    while (cursor.moveToNext()) {
                        val volume = if (Build.VERSION.SDK_INT >= 29) str("volume_name") else "external"
                        val folder = if (Build.VERSION.SDK_INT >= 29) str("relative_path") else str("bucket_display_name")
                        val albumId = "$volume|$folder"
                        albums[albumId] = SystemAlbum(albumId, str("bucket_display_name").ifBlank { folder.ifBlank { "Photos" } })
                        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val id = ContentUris.withAppendedId(collection, num("_id")).toString()
                        photos += SystemPhoto(id, str("_display_name"), "${num("date_modified")}:${num("_size")}:${num("width")}:${num("height")}",
                            listOf(albumId), num("width").toInt(), num("height").toInt(), num("_size"), num("datetaken"))
                    }
                }
            }
            callback.finished(PhotoSnapshot(permission, photos, albums.values.sortedBy { it.title },
                Build.VERSION.SDK_INT >= 30 && permission != "denied", Build.VERSION.SDK_INT >= 30 && permission != "denied", "Move to folder", "Move to trash"), null)
        } catch (e: SecurityException) {
            callback.finished(PhotoSnapshot("denied", emptyList(), emptyList(), false, false, "Move to folder", "Move to trash"), null)
        } catch (e: Exception) { callback.finished(null, e.message ?: "Cannot read system photos") }
    } }
    override fun observe(observer: PhotoChangeObserver?) { this.observer = observer }
    override fun openSettings() { activity.runOnUiThread {
        activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
    } }
    fun notifyChange() { observer?.changed() }
    private fun uri(id: String): Uri {
        require(access() != "denied") { "Photo access was revoked" }
        val uri = Uri.parse(id)
        require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY && uri.pathSegments.contains("images")) { "Invalid system photo" }
        resolver.query(uri, arrayOf("_id"), null, null, null)?.use { require(it.moveToFirst()) { "Photo is no longer available" } }
            ?: error("Photo is no longer available")
        return uri
    }
    override fun thumbnail(id: String, pixels: Int, callback: PhotoBytesCallback) {
        val key = "$id:$pixels"; cache.get(key)?.let { callback.finished(it, null); return }
        io.execute { try {
            val uri = uri(id)
            val bitmap = if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(uri, Size(pixels, pixels), null)
            else {
                val file = File(inputs, "thumb-${UUID.randomUUID()}.jpg")
                try { AndroidPhotoPreprocessor.prepare(resolver, uri, file); BitmapFactory.decodeFile(file.path) } finally { file.delete() }
            }
            val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray() }
            bitmap.recycle(); cache.put(key, bytes); callback.finished(bytes, null)
        } catch (e: Exception) { callback.finished(null, e.message) } }
    }
    override fun prepareImage(id: String, callback: PhotoFileCallback) { io.execute {
        val file = File(inputs, "input-${UUID.randomUUID()}.jpg")
        try { AndroidPhotoPreprocessor.prepare(resolver, uri(id), file); callback.finished(file.path, null) }
        catch (e: Exception) { file.delete(); callback.finished(null, e.message ?: "Cannot read photo") }
    } }
    override fun releaseImage(path: String) { val file = File(path); if (file.parentFile?.canonicalFile == inputs.canonicalFile) file.delete() }
    private fun request(callback: PhotoActionCallback, pending: android.app.PendingIntent, after: (() -> Unit)? = null) {
        activity.runOnUiThread {
            if (actionCallback != null) { callback.finished(false, "Another system operation is open"); return@runOnUiThread }
            actionCallback = callback; afterConsent = after
            try { consent.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
            catch (e: Exception) { actionCallback = null; afterConsent = null; callback.finished(false, e.message) }
        }
    }
    override fun deletePhotos(ids: List<String>, callback: PhotoActionCallback) { io.execute {
        try {
            if (Build.VERSION.SDK_INT < 30) error("System trash requires Android 11 or later")
            require(ids.isNotEmpty() && ids.size <= 100) { "Select between 1 and 100 photos" }
            request(callback, MediaStore.createTrashRequest(resolver, ids.map(::uri), true))
        } catch (e: Exception) { callback.finished(false, e.message) }
    } }
    override fun organizePhotos(ids: List<String>, albumId: String?, newName: String?, callback: PhotoActionCallback) { io.execute {
        try {
            if (Build.VERSION.SDK_INT < 30) error("Folder changes require Android 11 or later")
            require(ids.isNotEmpty() && ids.size <= 100) { "Select between 1 and 100 photos" }
            val target = albumId?.substringAfter('|') ?: run {
                val name = newName?.trim().orEmpty()
                require(name.isNotBlank() && name.length <= 80 && name.none { it in "/\\\u0000" } && name != "." && name != "..") { "Enter a valid folder name" }
                "Pictures/$name/"
            }
            require(target.startsWith("Pictures/") || target.startsWith("DCIM/")) { "Choose a Pictures or DCIM folder" }
            val uris = ids.map(::uri)
            require(albumId == null || uris.all { it.pathSegments.first() == albumId.substringBefore('|') }) { "Moving between storage volumes is not supported" }
            request(callback, MediaStore.createWriteRequest(resolver, uris)) {
                var completed = 0
                try { uris.forEach { photo ->
                    require(resolver.update(photo, android.content.ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }, null, null) == 1) { "Photo could not be moved" }
                    completed++
                } } catch (e: Exception) { error("Moved $completed / ${uris.size}. ${e.message}") }
            }
        } catch (e: Exception) { callback.finished(false, e.message) }
    } }
    override fun sharePhotos(ids: List<String>, callback: PhotoActionCallback) { io.execute {
        try {
            val uris = ArrayList(ids.map(::uri)); require(uris.isNotEmpty())
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(resolver, "Photos", uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            }
            activity.runOnUiThread { try {
                if (shareCallback != null) error("The share sheet is already open")
                shareCallback = callback; share.launch(Intent.createChooser(intent, "Share photos"))
            } catch (e: Exception) { shareCallback = null; callback.finished(false, e.message) } }
        } catch (e: Exception) { callback.finished(false, e.message) }
    } }
    override fun shareReport(report: String, callback: PhotoActionCallback) { io.execute {
        var file: File? = null
        try {
            require(report.length <= 4_000_000) { "Report is too large" }
            val directory = File(activity.cacheDir, "lab-reports").apply { mkdirs() }
            file = File(directory, "Lab-${UUID.randomUUID()}.json").apply { writeText(report) }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.lab-reports", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(resolver, "Lab report", uri)
            }
            activity.runOnUiThread { try {
                check(shareCallback == null) { "The share sheet is already open" }
                reportFile = file; shareCallback = callback; share.launch(Intent.createChooser(intent, "Export Lab report"))
            } catch (e: Exception) { file?.delete(); reportFile = null; shareCallback = null; callback.finished(false, e.message) } }
        } catch (e: Exception) { file?.delete(); callback.finished(false, e.message) }
    } }
    override fun close() { observer = null; resolver.unregisterContentObserver(changes); cache.evictAll(); io.shutdown() }
}
