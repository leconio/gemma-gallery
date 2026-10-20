package com.spawnlau.gemmapocket.shared

import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

object LabSpec {
    const val maxPhotos = 200
    const val maxTags = 20
    const val textVersion = "${ModelSpec.vectorVersion}|search-label-v1"
    const val exampleMargin = 0.02
}

@Serializable
data class LabTag(
    val id: String, val name: String, val description: String,
    val textCutoff: Double = 0.70, val exampleCutoff: Double = 0.80,
    val positiveIds: List<String> = emptyList(), val negativeIds: List<String> = emptyList(),
    val textVector: FloatArray? = null, val textVersion: String = ""
)

@Serializable
data class LabDecision(val photoId: String, val tagId: String, val status: String)

@Serializable
data class LabGroup(val id: String, val name: String, val photoIds: List<String>, val representativeId: String, val edited: Boolean = false)

@Serializable
data class LabGrouping(
    val groups: List<LabGroup>, val unassigned: List<String>, val cutoff: Double,
    val revisions: Map<String, String>, val vectorVersion: String = ModelSpec.vectorVersion,
    val elapsedMs: Double = 0.0
)

@Serializable
data class LabDocument(
    val schema: Int = 1, val tags: List<LabTag> = emptyList(),
    val decisions: List<LabDecision> = emptyList(), val grouping: LabGrouping? = null
)

@Serializable
data class LabTagScore(
    val photoId: String, val tagId: String, val score: Double, val cutoff: Double, val suggested: Boolean,
    val negativeScore: Double? = null, val mode: String
)

@Serializable
data class LabTagRun(
    val mode: String, val inputIds: List<String>, val referenceIds: List<String>,
    val scores: List<LabTagScore>, val skippedTagIds: List<String>, val elapsedMs: Double = 0.0
)

@Serializable
data class LabNeighbor(val photoId: String, val score: Double)

@Serializable
data class LabSimilarRun(val referenceId: String, val inputIds: List<String>, val cutoff: Double, val results: List<LabNeighbor>, val elapsedMs: Double = 0.0)

@Serializable
data class LabReport(
    val schema: Int = 1, val modelSha256: String = ModelSpec.sha256,
    val vectorVersion: String = ModelSpec.vectorVersion, val platform: String, val backend: String,
    val photos: Map<String, String>, val tags: List<LabTag>, val decisions: List<LabDecision>,
    val tagRun: LabTagRun? = null, val similarRun: LabSimilarRun? = null, val grouping: LabGrouping? = null
)

fun validateLabTag(tag: LabTag) {
    require(tag.id.isNotBlank() && tag.name.isNotBlank() && tag.name.length <= 40) { "Enter a tag name (up to 40 characters)" }
    require(tag.description.isNotBlank() && tag.description.length <= 240) { "Describe the tag in up to 240 characters" }
    require(tag.textCutoff.isFinite() && tag.textCutoff in 0.0..1.0 && tag.exampleCutoff.isFinite() && tag.exampleCutoff in 0.0..1.0) { "Cutoffs must be between 0 and 1" }
    require(tag.positiveIds.size <= 20 && tag.negativeIds.size <= 20) { "Use up to 20 examples of each kind" }
    require(tag.positiveIds.distinct().size == tag.positiveIds.size && tag.negativeIds.distinct().size == tag.negativeIds.size) { "Example IDs must be unique" }
    require(tag.positiveIds.none { it in tag.negativeIds }) { "A photo cannot be both a positive and a negative example" }
    tag.textVector?.let(::validateVector)
}

/** Stored tag metadata refers to system IDs. It never owns a second photo library. */
fun LabDocument.retainLabPhotos(available: Set<String>): LabDocument {
    val keptTags = tags.map { it.copy(positiveIds = it.positiveIds.filter(available::contains), negativeIds = it.negativeIds.filter(available::contains)) }
    val tagIds = keptTags.map { it.id }.toSet()
    val keptGrouping = grouping?.takeIf { it.vectorVersion == ModelSpec.vectorVersion }?.let { old ->
        old.copy(groups = old.groups.mapNotNull { group ->
            val ids = group.photoIds.filter(available::contains)
            ids.takeIf { it.isNotEmpty() }?.let { group.copy(photoIds = ids, representativeId = group.representativeId.takeIf(ids::contains) ?: ids.first()) }
        }, unassigned = old.unassigned.filter(available::contains), revisions = old.revisions.filterKeys(available::contains))
    }
    return copy(tags = keptTags, decisions = decisions.filter { it.photoId in available && it.tagId in tagIds }, grouping = keptGrouping)
}

fun classifyLabPhotos(items: List<LibraryItem>, tags: List<LabTag>, mode: String, references: List<LibraryItem> = items): LabTagRun {
    require(mode == "Text" || mode == "Examples") { "Unknown tag mode" }
    require(items.size <= LabSpec.maxPhotos) { "Select up to 200 photos per run" }
    tags.forEach(::validateLabTag)
    val indexed = items.filter { it.type == "image" && it.vector != null }
    indexed.forEach { validateVector(it.vector!!) }
    val byId = references.filter { it.type == "image" && it.vector != null }.associateBy { it.id }
    byId.values.forEach { validateVector(it.vector!!) }
    // Reference photos are excluded from both modes so the comparison cannot score its own examples.
    val referenceIds = tags.flatMap { it.positiveIds + it.negativeIds }.toSet()
    val candidates = indexed.filter { it.id !in referenceIds }
    val skipped = mutableListOf<String>()
    val scores = tags.flatMap { tag ->
        val positives = tag.positiveIds.mapNotNull { byId[it]?.vector }
        val negatives = tag.negativeIds.mapNotNull { byId[it]?.vector }
        if (mode == "Examples" && positives.isEmpty()) { skipped += tag.id; emptyList() }
        else {
            if (mode == "Text") require(tag.textVersion == LabSpec.textVersion && tag.textVector != null) { "Run text embedding for this tag first" }
            candidates.map { photo ->
                val score = if (mode == "Text") cosine(photo.vector!!, tag.textVector!!) else positives.maxOf { cosine(photo.vector!!, it) }
                val negative = if (mode == "Examples") negatives.maxOfOrNull { cosine(photo.vector!!, it) } else null
                val cutoff = if (mode == "Text") tag.textCutoff else tag.exampleCutoff
                LabTagScore(photo.id, tag.id, score.coerceIn(-1.0, 1.0), cutoff,
                    score >= cutoff && (negative == null || score - negative >= LabSpec.exampleMargin), negative, mode)
            }
        }
    }.sortedWith(compareByDescending<LabTagScore> { it.score }.thenBy { it.photoId }.thenBy { it.tagId })
    return LabTagRun(mode, indexed.map { it.id }, referenceIds.filter(byId::containsKey).sorted(), scores, skipped)
}

fun similarLabPhotos(items: List<LibraryItem>, referenceId: String, cutoff: Double): LabSimilarRun {
    require(cutoff.isFinite() && cutoff in 0.0..1.0) { "Cutoff must be between 0 and 1" }
    require(items.size <= LabSpec.maxPhotos) { "Select up to 200 photos per run" }
    val indexed = items.filter { it.type == "image" && it.vector != null }
    val reference = requireNotNull(indexed.firstOrNull { it.id == referenceId }?.vector) { "Choose an indexed reference photo" }
    validateVector(reference)
    val results = indexed.filter { it.id != referenceId }.map { photo ->
        validateVector(photo.vector!!)
        LabNeighbor(photo.id, cosine(reference, photo.vector).coerceIn(-1.0, 1.0))
    }.filter { it.score >= cutoff }.sortedWith(compareByDescending<LabNeighbor> { it.score }.thenBy { it.photoId }).take(10)
    return LabSimilarRun(referenceId, indexed.map { it.id }, cutoff, results)
}

/** Complete-link grouping avoids chains of merely adjacent matches joining unrelated ends. */
fun groupLabPhotos(items: List<LibraryItem>, cutoff: Double, previous: LabGrouping? = null): LabGrouping {
    require(cutoff.isFinite() && cutoff in 0.0..1.0) { "Cutoff must be between 0 and 1" }
    require(items.size <= LabSpec.maxPhotos) { "Select up to 200 photos per run" }
    val photos = items.filter { it.type == "image" && it.vector != null }.sortedBy { it.id }
    require(photos.isNotEmpty()) { "Choose indexed photos first" }
    photos.forEach { validateVector(it.vector!!) }
    val n = photos.size
    val original = DoubleArray(n * n) { index ->
        val a = index / n; val b = index % n
        if (a == b) 1.0 else if (a < b) cosine(photos[a].vector!!, photos[b].vector!!).coerceIn(-1.0, 1.0) else 0.0
    }
    for (i in 0 until n) for (j in 0 until i) original[i * n + j] = original[j * n + i]
    val distance = original.copyOf()
    val members = Array(n) { mutableListOf(it) }; val active = BooleanArray(n) { true }
    while (true) {
        var best = cutoff; var left = -1; var right = -1
        for (i in 0 until n) if (active[i]) for (j in i + 1 until n) if (active[j]) {
            val score = distance[i * n + j]
            if (score >= best && (left < 0 || score > best)) { best = score; left = i; right = j }
        }
        if (left < 0) break
        members[left].addAll(members[right]); active[right] = false
        for (i in 0 until n) if (active[i] && i != left) {
            val merged = minOf(distance[left * n + i], distance[right * n + i])
            distance[left * n + i] = merged; distance[i * n + left] = merged
        }
    }
    val groups = mutableListOf<LabGroup>(); val unassigned = mutableListOf<String>()
    for (i in 0 until n) if (active[i]) {
        val indices = members[i]
        if (indices.size == 1) unassigned += photos[indices.first()].id
        else {
            val ids = indices.map { photos[it].id }.sorted()
            val key = "group-" + ids.joinToString("\n").encodeUtf8().sha256().hex().take(16)
            val representative = indices.maxBy { a -> indices.sumOf { b -> original[a * n + b] } }
            val oldName = previous?.groups?.firstOrNull { it.id == key }?.name
            groups += LabGroup(key, oldName ?: "Group ${groups.size + 1}", ids, photos[representative].id)
        }
    }
    return LabGrouping(groups, unassigned, cutoff, photos.associate { it.id to it.revision })
}

/** Move also implements split (a new target). Every source photo belongs to exactly one group or Unassigned. */
fun moveLabPhotos(grouping: LabGrouping, photoIds: Set<String>, targetId: String?, newName: String): LabGrouping {
    val all = grouping.groups.flatMap { it.photoIds }.toSet() + grouping.unassigned
    require(photoIds.isNotEmpty() && all.containsAll(photoIds)) { "Select photos from this run" }
    require(targetId == null || grouping.groups.any { it.id == targetId }) { "Choose a valid group" }
    require(targetId != null || newName.trim().isNotBlank()) { "Enter a group name" }
    val id = targetId ?: "group-" + (photoIds.sorted().joinToString("\n") + "|manual|" + newName + grouping.groups.joinToString { it.id }).encodeUtf8().sha256().hex().take(16)
    var groups = grouping.groups.mapNotNull { group ->
        val ids = if (group.id == id) (group.photoIds + photoIds).distinct() else group.photoIds.filter { it !in photoIds }
        ids.takeIf { it.isNotEmpty() }?.let { group.copy(photoIds = ids, representativeId = group.representativeId.takeIf(ids::contains) ?: ids.first(), edited = group.edited || ids != group.photoIds) }
    }
    if (targetId == null) groups = groups + LabGroup(id, newName.trim().take(60), photoIds.sorted(), photoIds.sorted().first(), true)
    return grouping.copy(groups = groups, unassigned = grouping.unassigned.filter { it !in photoIds })
}
