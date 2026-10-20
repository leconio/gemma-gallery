package com.spawnlau.gemmapocket.shared

import kotlin.test.*

class LibraryTest {
    private fun unit(axis: Int) = FloatArray(768).apply { this[axis] = 1f }
    @Test fun cosineUsesDirectionAndRejectsInvalidVectors() {
        assertEquals(1.0, cosine(floatArrayOf(2f, 0f), floatArrayOf(9f, 0f)), 1e-9)
        assertEquals(0.0, cosine(floatArrayOf(2f, 0f), floatArrayOf(0f, 9f)), 1e-9)
        assertFailsWith<IllegalArgumentException> { cosine(floatArrayOf(0f), floatArrayOf(1f)) }
        assertFailsWith<IllegalArgumentException> { cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)) }
    }
    @Test fun invalidModelOutputsAreRejectedBeforeTheyReachTheIndex() {
        validateVector(unit(0))
        listOf(FloatArray(767), FloatArray(768), unit(0).apply { this[1] = Float.NaN }, unit(0).apply { this[0] = 2f })
            .forEach { assertFailsWith<IllegalArgumentException> { validateVector(it) } }
    }
    @Test fun ranksPhotosAndSkipsLegacyContentAndUnindexedImages() {
        val items = listOf(
            LibraryItem("other", "image", "Other", vector = unit(1)),
            LibraryItem("note", "note", "Note", vector = unit(0)),
            LibraryItem("best", "image", "Best", vector = unit(0)),
            LibraryItem("pending", "image", "Pending"))
        assertEquals(listOf("best", "other"), rankPhotos(items, unit(0)).map { it.item.id })
    }
    @Test fun readsTheExistingAndroidIndexWithoutChangingItsVectorVersion() {
        val old = """{"vectorVersion":"eg2-440m-92dcbea1-768d-v1","items":[{"id":"dog","type":"image","title":"Dog","body":"","path":"/private/images/dog.jpg","vector":null,"embeddingMs":123.5}]}"""
        val document = json.decodeFromString<IndexDocument>(old)
        assertEquals(ModelSpec.vectorVersion, document.vectorVersion)
        assertEquals(123.5, document.items.single().embeddingMs)
        assertNull(document.items.single().vector)
    }
    @Test fun queriesKeepTheModelsRetrievalInstructions() {
        assertEquals("task: search result | query: beach", ModelSpec.query("  beach  "))
    }
    private fun scored(id: String, similarity: Double) = LibraryItem(id, "image", id, vector = FloatArray(768).apply {
        this[0] = similarity.toFloat(); this[1] = kotlin.math.sqrt(1.0 - similarity * similarity).toFloat()
    })
    @Test fun relevanceCutoffKeepsMultipleMatchesAndRemovesTheLowScoringTail() {
        val items = listOf(scored("weak", 0.65), scored("pet-2", 0.69), scored("pet-1", 0.72),
            LibraryItem("pending", "image", "pending"))
        assertEquals(listOf("pet-1", "pet-2"), matchingPhotos(items, unit(0)).map { it.item.id })
        assertEquals(3, rankPhotos(items, unit(0)).size, "Benchmark ranking must retain the full score distribution")
    }
    @Test fun unrelatedQueriesCanReturnZeroEvenWhenTheyHaveABestCandidate() {
        assertTrue(matchingPhotos(listOf(scored("best-but-unrelated", 0.6765), scored("other", 0.60)), unit(0)).isEmpty())
        assertTrue(matchingPhotos(emptyList(), unit(0)).isEmpty())
    }
    @Test fun cutoffBoundaryIsStableWithoutRoundingScoresToPercentages() {
        assertEquals(listOf("above"), matchingPhotos(listOf(scored("below", 0.67999), scored("above", 0.68001)), unit(0)).map { it.item.id })
        assertFailsWith<IllegalArgumentException> { matchingPhotos(emptyList(), FloatArray(768)) }
    }
}
