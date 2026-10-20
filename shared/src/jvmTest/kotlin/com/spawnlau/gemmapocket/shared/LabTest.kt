package com.spawnlau.gemmapocket.shared

import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class LabTest {
    private fun v(angle: Double = 0.0) = FloatArray(768).apply { this[0] = cos(angle).toFloat(); this[1] = sin(angle).toFloat() }
    private fun p(id: String, angle: Double = 0.0) = LibraryItem(id, "image", "$id.jpg", vector = v(angle), revision = "1")
    private fun tag(id: String = "dogs") = LabTag(id, id, "a dog", textCutoff = .8, exampleCutoff = .8, positiveIds = listOf("positive"), negativeIds = listOf("negative"), textVector = v(), textVersion = LabSpec.textVersion)

    @Test fun labelsAreMultiLabelAndReferencesAreExcludedInBothModes() {
        val photos = listOf(p("positive"), p("negative", 1.5), p("candidate", .1))
        for (mode in listOf("Text", "Examples")) {
            val run = classifyLabPhotos(photos, listOf(tag(), tag("pets")), mode)
            assertEquals(2, run.scores.size); assertTrue(run.scores.all { it.photoId == "candidate" && it.suggested })
            assertEquals(setOf("positive", "negative"), run.referenceIds.toSet())
        }
    }
    @Test fun negativeExamplesVetoAndMissingPositiveNeverFallsBackToText() {
        val photos = listOf(p("positive"), p("negative", .15), p("candidate", .14))
        val result = classifyLabPhotos(photos, listOf(tag()), "Examples").scores.single()
        assertTrue(result.score > .8); assertFalse(result.suggested); assertNotNull(result.negativeScore)
        val noExamples = classifyLabPhotos(photos, listOf(tag().copy(positiveIds = emptyList())), "Examples")
        assertTrue(noExamples.scores.isEmpty()); assertEquals(listOf("dogs"), noExamples.skippedTagIds)
    }
    @Test fun examplesOutsideRunScopeStillWorkAndTextNeedsCurrentModelVector() {
        val run = classifyLabPhotos(listOf(p("heldout", .1)), listOf(tag()), "Examples", listOf(p("positive"), p("negative", 1.5)))
        assertTrue(run.scores.single().suggested)
        assertFailsWith<IllegalArgumentException> { classifyLabPhotos(listOf(p("candidate")), listOf(tag().copy(textVersion = "old")), "Text") }
        assertFailsWith<IllegalArgumentException> { validateLabTag(tag().copy(negativeIds = listOf("positive"))) }
    }
    @Test fun similarSearchExcludesSelfUsesCutoffAndReturnsTenStableNeighbors() {
        val photos = listOf(p("query")) + (1..12).map { p("candidate-${it.toString().padStart(2, '0')}", .1) } + p("unrelated", 1.0)
        val run = similarLabPhotos(photos.reversed(), "query", .9)
        assertEquals(10, run.results.size); assertEquals("candidate-01", run.results.first().photoId)
        assertTrue(run.results.none { it.photoId == "query" || it.photoId == "unrelated" })
        assertTrue(similarLabPhotos(photos, "query", 1.0).results.isEmpty())
    }
    @Test fun completeLinkDoesNotChainAndSingletonsRemainUnassigned() {
        // A-B and B-C pass, A-C fails. Complete-link must not merge all three.
        val photos = listOf(p("a", 0.0), p("b", .4), p("c", .8), p("far", 2.2))
        val result = groupLabPhotos(photos, .9)
        assertEquals(1, result.groups.size); assertEquals(2, result.groups.single().photoIds.size)
        assertEquals(2, result.unassigned.size)
        assertEquals(result, groupLabPhotos(photos.reversed(), .9))
        val group = result.groups.single(); assertTrue(group.representativeId in group.photoIds)
        val renamed = result.copy(groups = listOf(group.copy(name = "Keep this name")))
        assertEquals("Keep this name", groupLabPhotos(photos, .9, renamed).groups.single().name)
    }
    @Test fun splitMoveAndMergePreserveUniqueCoverageAndNames() {
        val original = groupLabPhotos(listOf(p("a"), p("b", .1), p("c", 2.0)), .9)
        val split = moveLabPhotos(original, setOf("a"), null, "My split")
        assertEquals(2, split.groups.size); assertTrue(split.groups.all { it.edited })
        val target = split.groups.first { it.name == "My split" }.id
        val merged = moveLabPhotos(split, setOf("b", "c"), target, "unused")
        assertEquals(listOf("My split"), merged.groups.map { it.name }); assertTrue(merged.unassigned.isEmpty())
        val all = merged.groups.flatMap { it.photoIds } + merged.unassigned
        assertEquals(setOf("a", "b", "c"), all.toSet()); assertEquals(3, all.size)
        assertFailsWith<IllegalArgumentException> { moveLabPhotos(merged, setOf("missing"), target, "") }
    }
    @Test fun permissionsPruneReferencesReviewsAndGroupsWithoutNeedingVectors() {
        val doc = LabDocument(tags = listOf(tag()), decisions = listOf(LabDecision("candidate", "dogs", "confirmed"), LabDecision("revoked", "dogs", "rejected")),
            grouping = groupLabPhotos(listOf(p("candidate"), p("revoked", .1)), .9))
        val retained = doc.retainLabPhotos(setOf("candidate", "positive"))
        assertEquals(listOf("positive"), retained.tags.single().positiveIds); assertTrue(retained.tags.single().negativeIds.isEmpty())
        assertEquals(listOf("candidate"), retained.decisions.map { it.photoId })
        assertEquals(listOf("candidate"), retained.grouping!!.groups.single().photoIds)
    }
    @Test fun movingPhotosDoesNotMarkUnchangedGroupsAsEdited() {
        val original = groupLabPhotos(listOf(p("a"), p("b", .1), p("c", 1.5), p("d", 1.6)), .9)
        val changed = moveLabPhotos(original, setOf("a"), null, "Split")
        val untouched = changed.groups.single { "c" in it.photoIds }
        assertFalse(untouched.edited); assertEquals(listOf("c", "d"), untouched.photoIds)
    }
    @Test fun storeRoundTripsAndInvalidatesOldVectorsWithoutLosingDefinitions() {
        val dir = Files.createTempDirectory("gemma-lab-store").toFile()
        try {
            val store = LabStore(dir.path)
            val old = LabDocument(tags = listOf(tag().copy(textVersion = "old")), decisions = listOf(LabDecision("candidate", "dogs", "confirmed")))
            store.save(old); val loaded = store.load()
            assertEquals("a dog", loaded.tags.single().description); assertNull(loaded.tags.single().textVector)
            assertEquals(old.decisions, loaded.decisions)
            store.save(loaded.copy(tags = listOf(tag())))
            assertContentEquals(v(), store.load().tags.single().textVector)
            assertFalse(dir.resolve("lab-v1.json.part").exists())
            val corrupt = "{broken json"; dir.resolve("lab-v1.json").writeText(corrupt)
            assertFails { store.load() }; assertEquals(corrupt, dir.resolve("lab-v1.json").readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun oversizedAndInvalidExperimentsAreRejected() {
        assertFailsWith<IllegalArgumentException> { similarLabPhotos(listOf(p("a")), "a", Double.NaN) }
        assertFailsWith<IllegalArgumentException> { groupLabPhotos((1..201).map { p("$it") }, .8) }
        assertFailsWith<IllegalArgumentException> { classifyLabPhotos(listOf(p("a").copy(vector = FloatArray(768))), listOf(tag()), "Text") }
    }
}
