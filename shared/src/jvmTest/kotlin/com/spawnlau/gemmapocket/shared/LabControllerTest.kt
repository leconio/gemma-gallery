package com.spawnlau.gemmapocket.shared

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LabControllerTest {
    private fun vector() = FloatArray(768).apply { this[0] = 1f }
    private fun photo(id: String = "photo") = LibraryItem(id, "image", "$id.jpg", vector = vector(), revision = "1")
    private fun host(directory: String) = object : PlatformServices {
        override val filesDirectory = directory
        override val platformLabel = "Test"
        override val initialBackend = "CPU"
        override val runtime: EmbeddingRuntime get() = error("Lab must share the supplied embedding callback")
        override val photos: SystemPhotoLibrary get() = error("No system operation expected")
        override val index: PhotoIndex get() = error("Lab must reuse supplied photo vectors")
        override fun prepareModel(observer: ModelObserver): ModelJob = error("No second engine")
        override fun pickModel(callback: ModelImportCallback) = error("No import")
        override fun log(message: String) {}
    }
    @Test fun cachesDescriptionEmbeddingsAndPreservesReviewsAndReferencesAcrossRestart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val dir = Files.createTempDirectory("gemma-lab-controller").toFile(); var calls = 0
        val controller = LabController(host(dir.path), { calls++; vector() }, dispatcher)
        try {
            controller.load(); advanceUntilIdle(); controller.setPhotos(listOf(photo(), photo("reference"))); advanceUntilIdle()
            controller.saveTag(LabTag("new", "Pets", "a pet", positiveIds = listOf("reference"))); advanceUntilIdle()
            controller.runTags("Text"); advanceUntilIdle(); assertEquals(1, calls)
            controller.runTags("Text"); advanceUntilIdle(); assertEquals(1, calls)
            val tag = controller.state.value.document.tags.single()
            controller.review("photo", tag.id, "confirmed"); advanceUntilIdle()
            controller.saveTag(tag.copy(description = "a dog")); advanceUntilIdle()
            controller.runTags("Text"); advanceUntilIdle(); assertEquals(2, calls)
            val restored = LabController(host(dir.path), { error("No new text inference") }, dispatcher)
            try {
                restored.load(); advanceUntilIdle()
                assertEquals(listOf("reference"), restored.state.value.document.tags.single().positiveIds)
                // Vector loading may lag behind provider metadata. Existing references must survive.
                restored.setPhotos(listOf(photo().copy(vector = null), photo("reference").copy(vector = null))); advanceUntilIdle()
                assertEquals(listOf("reference"), restored.state.value.document.tags.single().positiveIds)
                assertEquals("confirmed", restored.state.value.document.decisions.single().status)
                restored.setPhotos(emptyList()); advanceUntilIdle()
                assertTrue(restored.state.value.document.tags.single().positiveIds.isEmpty()); assertTrue(restored.state.value.document.decisions.isEmpty())
            } finally { restored.close() }
        } finally { controller.close(); Dispatchers.resetMain(); dir.deleteRecursively() }
    }
    @Test fun photoRevocationCancelsPendingTaggingAndCannotPublishStaleResults() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val dir = Files.createTempDirectory("gemma-lab-cancel").toFile(); val pending = CompletableDeferred<FloatArray>()
        val controller = LabController(host(dir.path), { pending.await() }, dispatcher)
        try {
            controller.load(); advanceUntilIdle(); controller.setPhotos(listOf(photo())); advanceUntilIdle()
            controller.saveTag(LabTag("new", "Pets", "a pet")); advanceUntilIdle()
            controller.runTags("Text"); runCurrent(); assertTrue(controller.state.value.busy)
            controller.setPhotos(emptyList()); pending.complete(vector()); advanceUntilIdle()
            assertFalse(controller.state.value.busy); assertNull(controller.state.value.tagRun)
            assertNull(controller.state.value.document.tags.single().textVector)
        } finally { controller.close(); Dispatchers.resetMain(); dir.deleteRecursively() }
    }
    @Test fun corruptDataDisablesWritesAndRetainsOriginalFile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val dir = Files.createTempDirectory("gemma-lab-corrupt").toFile(); val original = "{not valid json"
        dir.resolve("lab-v1.json").writeText(original)
        val controller = LabController(host(dir.path), { vector() }, dispatcher)
        try {
            controller.load(); advanceUntilIdle(); assertFalse(controller.state.value.loaded)
            controller.saveTag(LabTag("new", "Pets", "a pet")); advanceUntilIdle()
            assertEquals(original, dir.resolve("lab-v1.json").readText()); assertTrue(controller.state.value.error.isNotBlank())
        } finally { controller.close(); Dispatchers.resetMain(); dir.deleteRecursively() }
    }
}
