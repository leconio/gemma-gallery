package com.spawnlau.gemmapocket.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlin.time.TimeSource

data class LabState(
    val loaded: Boolean = false, val busy: Boolean = false, val saving: Boolean = false,
    val document: LabDocument = LabDocument(), val scopeIds: Set<String>? = null,
    val tagRun: LabTagRun? = null, val similarRun: LabSimilarRun? = null,
    val message: String = "", val error: String = ""
)

/** Shares the existing index and engine; no images are imported and no model weights are trained. */
class LabController(private val host: PlatformServices, private val embedText: suspend (String) -> FloatArray,
    private val io: CoroutineDispatcher = Dispatchers.Default, private val backend: () -> String = { host.initialBackend }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = LabStore(host.filesDirectory)
    private val disk = Mutex()
    private val mutable = MutableStateFlow(LabState())
    val state = mutable.asStateFlow()
    private var photos = emptyList<LibraryItem>()
    private var sourceReady = false
    private var generation = 0L
    private var job: Job? = null
    private var nextId = 0L
    private var writes = 0

    fun load() { scope.launch {
        try {
            val doc = withContext(io) { store.load() }
            mutable.update { it.copy(loaded = true, document = doc) }
            if (sourceReady) reconcile()
        } catch (e: Exception) { fail(e) } // Keep a corrupt file intact and disable all writes.
    } }
    fun setPhotos(items: List<LibraryItem>) {
        sourceReady = true
        val signature = items.map { Triple(it.id, it.revision, it.vector != null) }
        val old = photos.map { Triple(it.id, it.revision, it.vector != null) }
        photos = items
        if (signature != old) {
            generation++; job?.cancel()
            mutable.update { it.copy(busy = false, tagRun = null, similarRun = null) }
            if (state.value.loaded) reconcile()
        }
    }
    private fun reconcile() {
        val current = state.value.document
        var next = current.retainLabPhotos(photos.map { it.id }.toSet())
        // A group run belongs to the exact image revisions it compared.
        val revisions = photos.associate { it.id to it.revision }
        if (next.grouping?.revisions?.any { (id, revision) -> revisions[id] != revision } == true) next = next.copy(grouping = null)
        mutable.update { it.copy(document = next, scopeIds = it.scopeIds?.intersect(revisions.keys)) }
        if (next != current) persist()
        else scope.launch { disk.withLock { withContext(io) { store.clearReport() } } }
    }
    fun inputs(): List<LibraryItem> = photos.filter { it.vector != null && (state.value.scopeIds?.contains(it.id) != false) }.take(LabSpec.maxPhotos)
    fun setScope(ids: Set<String>) {
        if (!editable()) return
        require(ids.size <= LabSpec.maxPhotos)
        generation++; mutable.update { it.copy(scopeIds = ids.intersect(photos.map { p -> p.id }.toSet()), tagRun = null, similarRun = null, message = "") }
        clearReport()
    }
    fun saveTag(tag: LabTag) {
        if (!editable()) return
        try {
            val current = state.value.document
            val old = current.tags.firstOrNull { it.id == tag.id }
            require(old != null || current.tags.size < LabSpec.maxTags) { "Use up to 20 tags" }
            require(current.tags.none { it.id != tag.id && it.name.equals(tag.name.trim(), true) }) { "This tag name is already in use" }
            val saved = tag.copy(id = old?.id ?: "tag-${kotlin.time.Clock.System.now().toEpochMilliseconds()}-${nextId++}",
                name = tag.name.trim(), description = tag.description.trim(),
                textVector = old?.textVector.takeIf { old?.description == tag.description.trim() },
                textVersion = old?.textVersion.takeIf { old?.description == tag.description.trim() } ?: "")
            validateLabTag(saved)
            updateDocument(current.copy(tags = if (old == null) current.tags + saved else current.tags.map { if (it.id == saved.id) saved else it }), clearRuns = true)
        } catch (e: Exception) { fail(e) }
    }
    fun deleteTag(id: String) {
        if (!editable()) return
        val doc = state.value.document
        updateDocument(doc.copy(tags = doc.tags.filter { it.id != id }, decisions = doc.decisions.filter { it.tagId != id }), true)
    }
    fun review(photoId: String, tagId: String, status: String?) {
        if (!editable()) return
        if (photos.none { it.id == photoId } || state.value.document.tags.none { it.id == tagId }) return
        require(status == null || status == "confirmed" || status == "rejected")
        val doc = state.value.document
        val decisions = doc.decisions.filterNot { it.photoId == photoId && it.tagId == tagId } +
            (status?.let { listOf(LabDecision(photoId, tagId, it)) } ?: emptyList())
        updateDocument(doc.copy(decisions = decisions))
    }
    fun runTags(mode: String) = run {
        var doc = state.value.document
        require(doc.tags.isNotEmpty()) { "Create a tag first" }
        val tick = TimeSource.Monotonic.markNow()
        if (mode == "Text") {
            val tags = doc.tags.map { tag ->
                if (tag.textVector != null && tag.textVersion == LabSpec.textVersion) tag
                else tag.copy(textVector = embedText(tag.description).also(::validateVector), textVersion = LabSpec.textVersion)
            }
            doc = doc.copy(tags = tags)
        }
        val input = inputs(); require(input.isNotEmpty()) { "Choose indexed photos first" }
        val references = photos.filter { p -> doc.tags.any { p.id in it.positiveIds || p.id in it.negativeIds } }
        val result = withContext(io) { classifyLabPhotos(input, doc.tags, mode, references) }
            .copy(elapsedMs = tick.elapsedNow().inWholeMicroseconds / 1000.0)
        mutable.update { it.copy(document = doc, tagRun = result,
            message = "${result.scores.count { score -> score.suggested }} suggestions · ${result.elapsedMs.toInt()} ms" +
                if (result.skippedTagIds.isEmpty()) "" else " · ${result.skippedTagIds.size} tags need positive examples") }
        persist()
    }
    fun runSimilar(referenceId: String, cutoff: Double) = run {
        val tick = TimeSource.Monotonic.markNow(); val input = inputs()
        val result = withContext(io) { similarLabPhotos(input, referenceId, cutoff) }
            .copy(elapsedMs = tick.elapsedNow().inWholeMicroseconds / 1000.0)
        mutable.update { it.copy(similarRun = result, message = "${result.results.size} matches · ${result.elapsedMs.toInt()} ms") }; persist()
    }
    fun runGroups(cutoff: Double) = run {
        val tick = TimeSource.Monotonic.markNow(); val input = inputs(); val previous = state.value.document.grouping
        val result = withContext(io) { groupLabPhotos(input, cutoff, previous) }
            .copy(elapsedMs = tick.elapsedNow().inWholeMicroseconds / 1000.0)
        mutable.update { it.copy(document = it.document.copy(grouping = result),
            message = "${result.groups.size} groups · ${result.unassigned.size} unassigned · ${result.elapsedMs.toInt()} ms") }; persist()
    }
    fun renameGroup(id: String, name: String) {
        if (!editable() || name.trim().isEmpty()) return
        val doc = state.value.document; val grouping = doc.grouping ?: return
        updateDocument(doc.copy(grouping = grouping.copy(groups = grouping.groups.map { if (it.id == id) it.copy(name = name.trim().take(60), edited = true) else it })))
    }
    fun movePhotos(ids: Set<String>, targetId: String?, name: String) {
        if (!editable()) return
        try { val doc = state.value.document; val grouping = doc.grouping ?: return
            updateDocument(doc.copy(grouping = moveLabPhotos(grouping, ids, targetId, name)))
        } catch (e: Exception) { fail(e) }
    }
    private fun run(action: suspend () -> Unit) {
        if (!editable()) return
        val version = generation
        mutable.update { it.copy(busy = true, error = "", message = "Running...") }
        job = scope.launch {
            try { action(); ensureActive(); check(version == generation) { "Photos changed. Run again." } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
            finally { if (version == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    private fun editable() = state.value.loaded && !state.value.busy && !state.value.saving
    private fun updateDocument(doc: LabDocument, clearRuns: Boolean = false) {
        mutable.update { it.copy(document = doc, error = "", message = "", tagRun = if (clearRuns) null else it.tagRun) }; persist()
    }
    private fun report(): LabReport {
        val s = state.value
        val ids = (s.tagRun?.inputIds.orEmpty() + s.tagRun?.referenceIds.orEmpty() + s.similarRun?.inputIds.orEmpty() +
            s.document.tags.flatMap { it.positiveIds + it.negativeIds } + s.document.decisions.map { it.photoId } +
            s.document.grouping?.revisions.orEmpty().keys).toSet()
        return LabReport(platform = host.platformLabel, backend = backend(),
            photos = photos.filter { it.id in ids }.associate { it.id to it.title }, tags = s.document.tags.map { it.copy(textVector = null) },
            decisions = s.document.decisions, tagRun = s.tagRun, similarRun = s.similarRun, grouping = s.document.grouping)
    }
    private fun persist() {
        writes++; mutable.update { it.copy(saving = true) }
        scope.launch { try { disk.withLock {
            val doc = state.value.document; val report = report()
            withContext(io) { store.save(doc); store.saveReport(report) }
        } } catch (e: Exception) { fail(e) }
        finally { writes--; mutable.update { it.copy(saving = writes > 0) } } }
    }
    private fun clearReport() { scope.launch { disk.withLock { withContext(io) { store.clearReport() } } } }
    fun exportReport() {
        if (!editable()) return
        val content = json.encodeToString(report())
        mutable.update { it.copy(busy = true, error = "") }
        host.photos.shareReport(content, object : PhotoActionCallback {
            override fun finished(cancelled: Boolean, error: String?) { scope.launch {
                mutable.update { it.copy(busy = false, error = error ?: "", message = if (error == null) "Share sheet closed" else "") }
            } }
        })
    }
    private fun fail(e: Exception) { mutable.update { it.copy(error = e.message ?: "Lab operation failed", message = "") } }
    fun close() { scope.cancel() }
}
