package com.spawnlau.gemmapocket.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.TimeSource

data class DemoState(
    val ready: Boolean = false, val busy: Boolean = false, val acting: Boolean = false,
    val libraryLoaded: Boolean = false, val labPhotos: List<LibraryItem> = emptyList(),
    val status: String = "Connect your Photos library", val detail: String = "", val progress: Float? = null,
    val downloading: Boolean = false, val paused: Boolean = false, val backend: String = "CPU",
    val access: String = "denied", val canOrganize: Boolean = false, val canDelete: Boolean = false,
    val organizeLabel: String = "Organize", val deleteLabel: String = "Delete",
    val albums: List<SystemAlbum> = emptyList(), val albumId: String = "",
    val query: String = "", val results: List<SearchResult> = emptyList(), val selected: Set<String> = emptySet(),
    val searched: Boolean = false, val searching: Boolean = false, val queryMs: Double = 0.0,
    val imageCount: Int = 0, val indexedCount: Int = 0, val failedCount: Int = 0,
    val scanning: Boolean = false, val scanPaused: Boolean = false,
    val lastReadMs: Double = 0.0, val lastEmbeddingMs: Double = 0.0, val lastWriteMs: Double = 0.0
)

class DemoController(val host: PlatformServices, private val autoSmoke: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutable = MutableStateFlow(DemoState(backend = host.initialBackend))
    val state = mutable.asStateFlow()
    private val runtimeLock = Mutex()
    private var photos = emptyList<SystemPhoto>()
    private var cached = emptyMap<String, IndexedPhoto>()
    private var queryVector: FloatArray? = null
    private var setupJob: Job? = null
    private var refreshJob: Job? = null
    private var scanJob: Job? = null
    private var modelJob: ModelJob? = null
    private var modelPath = ""
    private var pauseRequested = false
    private var rescanRequested = false
    private var operationOpen = false
    private var renderGeneration = 0L

    fun start() {
        host.photos.observe(object : PhotoChangeObserver { override fun changed() { scope.launch { refreshLater() } } })
        scope.launch { try {
            withContext(ioDispatcher) { migratePrivateIndex(host.filesDirectory) }
            reloadLibrary()
            if (state.value.access != "denied") prepareModel()
        } catch (e: Throwable) { failure(e) } }
    }
    private fun refreshLater() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(350)
            try { reloadLibrary(); if (state.value.access != "denied") prepareModel() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { failure(e) }
        }
    }
    private suspend fun snapshot(request: Boolean = false): PhotoSnapshot = suspendCancellableCoroutine { c ->
        val callback = object : PhotoSnapshotCallback {
            override fun finished(snapshot: PhotoSnapshot?, error: String?) {
                if (!c.isActive) return
                if (snapshot != null && error == null) c.resume(snapshot)
                else c.resumeWithException(IllegalStateException(error ?: "Cannot read Photos"))
            }
        }
        if (request) host.photos.requestAccess(callback) else host.photos.refresh(callback)
    }
    private suspend fun reloadLibrary(request: Boolean = false) {
        val next = snapshot(request)
        val available = next.photos.map { it.id }.toSet()
        // Hide inaccessible photos before touching the database: disk failures must not keep revoked results visible.
        photos = next.photos; cached = cached.filterKeys { it in available }
        if (next.access == "denied") { pauseRequested = true; queryVector = null }
        mutable.update { it.copy(access = next.access, albums = next.albums, canOrganize = next.canOrganize, canDelete = next.canDelete,
            organizeLabel = next.organizeLabel, deleteLabel = next.deleteLabel,
            albumId = it.albumId.takeIf { id -> next.albums.any { album -> album.id == id } } ?: "", selected = it.selected.intersect(available),
            results = it.results.filter { result -> result.item.id in available }, labPhotos = it.labPhotos.filter { photo -> photo.id in available }) }
        render()
        cached = withContext(ioDispatcher) { host.index.retain(available.toList()); host.index.load().associateBy { it.id } }
        render()
        if (state.value.scanning) rescanRequested = true
        else if (state.value.ready && !state.value.scanPaused && !operationOpen) scan()
    }
    fun connectPhotos() {
        if (state.value.acting) return
        mutable.update { it.copy(acting = true, detail = "") }
        scope.launch { try { reloadLibrary(true); prepareModel() } catch (e: Throwable) { failure(e) }
            finally { mutable.update { it.copy(acting = false) } } }
    }
    private fun prepareModel() {
        if (state.value.access == "denied" || setupJob?.isActive == true || state.value.ready) return
        setupJob = scope.launch {
            mutable.update { it.copy(busy = true, paused = false, status = "Preparing model...", detail = "Download once, then search on this device.") }
            try {
                modelPath = suspendCancellableCoroutine { c ->
                    modelJob = host.prepareModel(object : ModelObserver {
                        override fun status(message: String) { scope.launch { mutable.update { it.copy(status = message, progress = null) } } }
                        override fun progress(received: Long, total: Long) { scope.launch {
                            mutable.update { it.copy(downloading = true, status = "Downloading model · ${received * 100 / total}%",
                                detail = "${received / 1_000_000} / ${total / 1_000_000} MB", progress = received.toFloat() / total) }
                        } }
                        override fun finished(modelPath: String?, error: String?, cancelled: Boolean) {
                            if (!c.isActive) return
                            if (cancelled) c.resumeWithException(DownloadPaused())
                            else if (modelPath != null && error == null) c.resume(modelPath)
                            else c.resumeWithException(IllegalStateException(error ?: "Model setup failed"))
                        }
                    }); c.invokeOnCancellation { modelJob?.cancel() }
                }
                modelJob = null
                runtimeLock.withLock { awaitOperation { host.runtime.initialize(modelPath, state.value.backend, it) } }
                mutable.update { it.copy(ready = true, busy = false, downloading = false, progress = null, status = "Ready", detail = "") }
                scan()
            } catch (e: DownloadPaused) {
                mutable.update { it.copy(busy = false, downloading = false, paused = true, status = "Model download paused", progress = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { mutable.update { it.copy(busy = false, downloading = false, status = "Model not ready") }; failure(e) }
        }
    }
    fun resumeModel() { prepareModel() }
    fun pauseDownload() { modelJob?.cancel() }
    fun scan() {
        if (!state.value.ready || state.value.access == "denied" || operationOpen || scanJob?.isActive == true) return
        pauseRequested = false; rescanRequested = false
        val pending = photos.filter { usableIndex(it, cached[it.id]) == null }
        if (pending.isEmpty()) { mutable.update { it.copy(scanPaused = false) }; return }
        mutable.update { it.copy(scanning = true, scanPaused = false, detail = "") }
        scanJob = scope.launch {
            var lastRender = TimeSource.Monotonic.markNow()
            try {
                for (photo in pending) {
                    if (pauseRequested || state.value.access == "denied") break
                    var path: String? = null
                    try {
                        val readTick = TimeSource.Monotonic.markNow()
                        val imagePath: String = suspendCancellableCoroutine { c -> host.photos.prepareImage(photo.id, object : PhotoFileCallback {
                            override fun finished(path: String?, error: String?) {
                                if (!c.isActive) { if (path != null) host.photos.releaseImage(path); return }
                                if (path != null && error == null) c.resume(path)
                                else c.resumeWithException(IllegalStateException(error ?: "Cannot read photo"))
                            }
                        }) }
                        path = imagePath
                        val readMs = readTick.elapsedNow().inWholeMicroseconds / 1000.0
                        val embedTick = TimeSource.Monotonic.markNow()
                        // Let the current native call finish before releasing its input, even on view disposal.
                        val vector = runtimeLock.withLock { withContext(NonCancellable) { awaitVector { host.runtime.embedImage(imagePath, it) } } }
                        val embedMs = embedTick.elapsedNow().inWholeMicroseconds / 1000.0
                        validateVector(vector)
                        if (photos.any { it.id == photo.id && indexRevision(it) == indexRevision(photo) }) {
                            val record = IndexedPhoto(photo.id, indexRevision(photo), vector, readMs, embedMs, "")
                            val writeTick = TimeSource.Monotonic.markNow()
                            withContext(ioDispatcher) { host.index.save(record) }
                            cached = cached + (photo.id to record)
                            mutable.update { it.copy(lastReadMs = readMs, lastEmbeddingMs = embedMs, lastWriteMs = writeTick.elapsedNow().inWholeMicroseconds / 1000.0) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Throwable) {
                        if (photos.any { it.id == photo.id }) {
                            val record = IndexedPhoto(photo.id, indexRevision(photo), null, 0.0, 0.0, e.message ?: "Photo unavailable")
                            withContext(ioDispatcher) { host.index.save(record) }; cached = cached + (photo.id to record)
                        }
                    } finally { path?.let(host.photos::releaseImage) }
                    // Recompute the visible library at most twice per second during large initial scans.
                    if (lastRender.elapsedNow().inWholeMilliseconds >= 500) { render(); lastRender = TimeSource.Monotonic.markNow() }
                    yield()
                }
                render()
            } finally {
                scanJob = null
                mutable.update { it.copy(scanning = false, scanPaused = pauseRequested,
                    detail = if (state.value.failedCount > 0) "${state.value.failedCount} unavailable. Download cloud photos in Photos, or grant access, then Resume." else it.detail) }
                if (rescanRequested && !pauseRequested && !operationOpen) scan()
            }
        }
    }
    fun pauseScan() { pauseRequested = true; mutable.update { it.copy(scanPaused = true) } }
    private suspend fun render() {
        val generation = ++renderGeneration
        val photoSnapshot = photos; val cacheSnapshot = cached; val current = state.value
        // Editing the field does not submit a new query. Keep the applied filter during library updates.
        val query = queryVector
        val calculated = withContext(ioDispatcher) {
            val allItems = photoSnapshot.map { photo ->
                val record = usableIndex(photo, cacheSnapshot[photo.id])
                LibraryItem(photo.id, "image", photo.title, path = photo.id, vector = record?.vector, embeddingMs = record?.embeddingMs ?: 0.0, revision = photo.revision)
            }
            val visibleIds = photoSnapshot.filter { current.albumId.isEmpty() || current.albumId in it.albumIds }.map { it.id }.toSet()
            val items = allItems.filter { it.id in visibleIds }
            val results = query?.let { matchingPhotos(items, it) } ?: items.map { SearchResult(it, null) }
            val indexed = photoSnapshot.count { p -> usableIndex(p, cacheSnapshot[p.id]) != null }
            val failed = photoSnapshot.count { p -> cacheSnapshot[p.id]?.let { r -> r.revision == indexRevision(p) && r.error.isNotBlank() } == true }
            (Triple(results, indexed, failed) to allItems)
        }
        if (generation == renderGeneration) {
            val visible = calculated.first.first.map { it.item.id }.toSet()
            mutable.update { it.copy(libraryLoaded = true, results = calculated.first.first, labPhotos = calculated.second, imageCount = photoSnapshot.size,
                indexedCount = calculated.first.second, failedCount = calculated.first.third, selected = it.selected.intersect(visible)) }
        }
    }
    fun setQuery(value: String) { mutable.update { it.copy(query = value.take(500)) } }
    suspend fun embedLabText(text: String): FloatArray = runtimeLock.withLock {
        check(state.value.ready && !state.value.busy && state.value.access != "denied") { "Prepare the model and allow Photos access first" }
        awaitVector { host.runtime.embedText(ModelSpec.query(text), it) }.also(::validateVector)
    }
    fun selectAlbum(id: String) { mutable.update { it.copy(albumId = id) }; scope.launch { render() } }
    fun search() {
        if (!state.value.ready || state.value.busy || state.value.searching || state.value.access == "denied") return
        val text = state.value.query.trim()
        if (text.isEmpty()) { queryVector = null; mutable.update { it.copy(searched = false) }; scope.launch { render() }; return }
        mutable.update { it.copy(searching = true) }
        scope.launch { try {
            val tick = TimeSource.Monotonic.markNow()
            val vector = runtimeLock.withLock { awaitVector { host.runtime.embedText(ModelSpec.query(text), it) } }
            validateVector(vector); queryVector = vector
            render()
            mutable.update { it.copy(queryMs = tick.elapsedNow().inWholeMicroseconds / 1000.0, searched = true) }
        } catch (e: Throwable) { failure(e) } finally { mutable.update { it.copy(searching = false) } } }
    }
    fun toggleSelection(id: String) {
        if (!state.value.acting) mutable.update { it.copy(selected = if (id in it.selected) it.selected - id else if (it.selected.size < 100) it.selected + id else it.selected) }
    }
    fun clearSelection() { mutable.update { it.copy(selected = emptySet()) } }
    fun deleteSelected() = action { host.photos.deletePhotos(state.value.selected.toList(), it) }
    fun organizeSelected(albumId: String?, newName: String?) = action { host.photos.organizePhotos(state.value.selected.toList(), albumId, newName, it) }
    fun shareSelected() = action("Share sheet closed") { host.photos.sharePhotos(state.value.selected.toList(), it) }
    private fun action(successMessage: String = "System Photos updated", start: (PhotoActionCallback) -> Unit) {
        if (operationOpen || state.value.selected.isEmpty()) return
        operationOpen = true
        val wasScanning = state.value.scanning; val wasPaused = state.value.scanPaused; pauseRequested = true
        mutable.update { it.copy(acting = true, detail = "") }
        scope.launch { try {
            scanJob?.join()
            val result = suspendCancellableCoroutine<Pair<Boolean, String?>> { c -> start(object : PhotoActionCallback {
                override fun finished(cancelled: Boolean, error: String?) { if (c.isActive) c.resume(cancelled to error) }
            }) }
            // Reconcile provider state even after cancellation or a partially completed folder move.
            reloadLibrary()
            mutable.update { it.copy(detail = result.second ?: if (result.first) "Cancelled" else successMessage,
                selected = if (!result.first && result.second == null) emptySet() else it.selected) }
        } catch (e: Throwable) { failure(e) } finally {
            operationOpen = false; mutable.update { it.copy(acting = false) }; if (wasScanning || !wasPaused) scan()
        } }
    }
    fun switchBackend(backend: String) {
        if (state.value.busy || state.value.acting || (backend == state.value.backend && state.value.ready)) return
        if (modelPath.isEmpty()) { mutable.update { it.copy(backend = backend) }; return }
        val wasScanning = state.value.scanning; pauseRequested = true; mutable.update { it.copy(busy = true) }
        scope.launch { try {
            scanJob?.join(); runtimeLock.withLock { awaitOperation { host.runtime.initialize(modelPath, backend, it) } }
            mutable.update { it.copy(backend = backend, busy = false, ready = true) }; if (wasScanning) scan()
        } catch (e: Throwable) { mutable.update { it.copy(busy = false) }; failure(e) } }
    }
    fun importModel() {
        if (state.value.busy || state.value.ready) return
        host.pickModel(object : ModelImportCallback { override fun finished(imported: Boolean, error: String?) { scope.launch {
            if (error != null) failure(IllegalStateException(error)) else if (imported) prepareModel()
        } } })
    }
    suspend fun imageBytes(id: String, pixels: Int): ByteArray? = suspendCancellableCoroutine { c ->
        host.photos.thumbnail(id, pixels, object : PhotoBytesCallback { override fun finished(bytes: ByteArray?, error: String?) { if (c.isActive) c.resume(bytes) } })
    }
    private fun failure(e: Throwable) {
        if (e !is CancellationException) { host.log("PHOTO_ERROR ${e.message}"); mutable.update { it.copy(detail = e.message ?: "Could not complete the operation") } }
    }
    fun close() { pauseRequested = true; host.photos.observe(null); modelJob?.cancel(); scope.cancel(); host.runtime.close() }
    private class DownloadPaused : Exception()
    private suspend fun awaitOperation(start: (OperationCallback) -> Unit): Unit = suspendCancellableCoroutine { c -> start(object : OperationCallback {
        override fun finished(error: String?) { if (c.isActive) { if (error == null) c.resume(Unit) else c.resumeWithException(IllegalStateException(error)) } }
    }) }
    private suspend fun awaitVector(start: (VectorCallback) -> Unit): FloatArray = suspendCancellableCoroutine { c -> start(object : VectorCallback {
        override fun finished(vector: FloatArray?, error: String?) { if (c.isActive) {
            if (vector != null && error == null) c.resume(vector) else c.resumeWithException(IllegalStateException(error ?: "Inference failed"))
        } }
    }) }
}
