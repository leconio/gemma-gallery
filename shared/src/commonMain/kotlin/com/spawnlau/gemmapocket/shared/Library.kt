package com.spawnlau.gemmapocket.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

object ModelSpec {
    const val filename = "embeddinggemma-2-text-vision-440m.litertlm"
    const val bytes = 387710976L
    const val sha256 = "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0"
    const val revision = "e301f74d5551b0c2641bd5cb4652a76239d5c5f8"
    const val source = "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/$revision/$filename"
    const val mirror = "https://hf-mirror.com/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/$revision/$filename"
    const val vectorVersion = "eg2-440m-92dcbea1-768d-v1"
    const val dimensions = 768
    fun query(text: String) = "task: search result | query: ${text.trim()}"
}

@Serializable
data class LibraryItem(
    val id: String, val type: String, val title: String, val path: String = "",
    val vector: FloatArray? = null, val embeddingMs: Double = 0.0, val revision: String = ""
)
@Serializable
internal data class IndexDocument(val vectorVersion: String = ModelSpec.vectorVersion, val items: List<LibraryItem>)

internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

/** The production index codec is also used by the on-device benchmark. */
object PhotoIndexCodec {
    fun encode(items: List<LibraryItem>): String = json.encodeToString(IndexDocument(items = items))
    fun decode(text: String): List<LibraryItem> = json.decodeFromString<IndexDocument>(text).items
}

fun validateVector(vector: FloatArray) {
    require(vector.size == ModelSpec.dimensions) { "Expected a 768-dimensional vector" }
    require(vector.all { it.isFinite() }) { "Embedding contains a non-finite value" }
    val norm = vector.sumOf { it.toDouble() * it }
    require(norm in 0.97..1.03) { "Embedding is not normalized" }
}

fun cosine(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size && a.isNotEmpty()) { "Vector dimensions must match" }
    var dot = 0.0; var aa = 0.0; var bb = 0.0
    for (i in a.indices) { dot += a[i].toDouble()*b[i]; aa += a[i].toDouble()*a[i]; bb += b[i].toDouble()*b[i] }
    require(aa > 0 && bb > 0 && dot.isFinite() && aa.isFinite() && bb.isFinite()) { "Invalid vector" }
    return dot/sqrt(aa*bb)
}

data class SearchResult(val item: LibraryItem, val score: Double?)
fun rankPhotos(items: List<LibraryItem>, query: FloatArray): List<SearchResult> {
    validateVector(query)
    return items.filter { it.type == "image" && it.vector != null }
        .map { SearchResult(it, cosine(query, it.vector!!)) }.sortedByDescending { it.score }
}

/** A retrieval cutoff for this pinned model, not a calibrated probability. See docs/search-relevance.md. */
object SearchRelevance {
    const val minimumSimilarity = 0.68
}

fun matchingPhotos(items: List<LibraryItem>, query: FloatArray): List<SearchResult> =
    rankPhotos(items, query).filter { (it.score ?: Double.NEGATIVE_INFINITY) >= SearchRelevance.minimumSimilarity }
