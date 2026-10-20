package com.spawnlau.gemmapocket.shared

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SystemPhotosTest {
    private fun vector() = FloatArray(768).apply { this[0] = 1f }
    @Test fun binaryVectorsPreserveBitsAndRejectCorruption() {
        val input = vector(); val bytes = PhotoVectorCodec.encode(input)
        assertEquals(3072, bytes.size); assertContentEquals(input, PhotoVectorCodec.decode(bytes))
        assertFailsWith<IllegalArgumentException> { PhotoVectorCodec.decode(bytes.copyOf(3071)) }
        assertFailsWith<IllegalArgumentException> { PhotoVectorCodec.decode(ByteArray(3072)) }
    }
    @Test fun editsAndModelChangesInvalidateEmbeddingsButAlbumNamesDoNot() {
        val photo = SystemPhoto("system-id", "photo.jpg", "edited-1", listOf("album"), 4000, 3000, 1000, 0)
        val record = IndexedPhoto(photo.id, indexRevision(photo), vector(), 1.0, 2.0, "")
        assertNotNull(usableIndex(photo.copy(title = "renamed.jpg", albumIds = listOf("another")), record))
        assertNull(usableIndex(photo.copy(revision = "edited-2"), record))
        assertNull(usableIndex(photo, record.copy(revision = "old-model")))
    }
    @Test fun privateMigrationPreservesCopiesAndNeverSeedsOrReusesThemAsSystemAssets() {
        val directory = Files.createTempDirectory("gemma-system-migration").toFile()
        try {
            val original = "legacy private index"; directory.resolve("index.json").writeText(original)
            val copy = directory.resolve("images/user.jpg").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            migratePrivateIndex(directory.path)
            assertEquals(original, directory.resolve("legacy-private-index.json").readText())
            directory.resolve("index.json").writeText("changed")
            migratePrivateIndex(directory.path)
            assertEquals(original, directory.resolve("legacy-private-index.json").readText()); assertTrue(copy.isFile)
        } finally { directory.deleteRecursively() }
    }

    private class MemoryIndex : PhotoIndex {
        val records = mutableMapOf<String, IndexedPhoto>()
        override fun load() = records.values.toList()
        override fun save(photo: IndexedPhoto) { records[photo.id] = photo }
        override fun retain(ids: List<String>) { records.keys.retainAll(ids.toSet()) }
        override fun close() {}
    }
    private class Photos : SystemPhotoLibrary {
        var permission = "full"; var cancelled = true; var observer: PhotoChangeObserver? = null; var deleteCalls = 0
        var items = listOf(SystemPhoto("provider-id", "camera.jpg", "1", emptyList(), 4000, 3000, 4000000, 0))
        override fun requestAccess(callback: PhotoSnapshotCallback) = refresh(callback)
        override fun refresh(callback: PhotoSnapshotCallback) = callback.finished(PhotoSnapshot(permission,
            if (permission == "denied") emptyList() else items, emptyList(), permission == "full", true, "Organize", "Trash"), null)
        override fun observe(observer: PhotoChangeObserver?) { this.observer = observer }
        override fun openSettings() {}
        override fun thumbnail(id: String, pixels: Int, callback: PhotoBytesCallback) = callback.finished(null, null)
        override fun prepareImage(id: String, callback: PhotoFileCallback) = callback.finished("/temporary-input", null)
        override fun releaseImage(path: String) {}
        override fun deletePhotos(ids: List<String>, callback: PhotoActionCallback) {
            deleteCalls++
            if (!cancelled) items = items.filter { it.id !in ids }; callback.finished(cancelled, null)
        }
        override fun organizePhotos(ids: List<String>, albumId: String?, newName: String?, callback: PhotoActionCallback) = callback.finished(true, null)
        override fun sharePhotos(ids: List<String>, callback: PhotoActionCallback) = callback.finished(true, null)
        override fun close() {}
    }
    @Test fun cancelledDeletionPreservesProviderPhotosAndIndexAndRevocationHidesThem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val directory = Files.createTempDirectory("gemma-controller").toFile()
        val library = Photos(); val database = MemoryIndex()
        val p = library.items.single(); database.save(IndexedPhoto(p.id, indexRevision(p), vector(), 1.0, 2.0, ""))
        val host = object : PlatformServices {
            override val filesDirectory = directory.path
            override val platformLabel = "Test"
            override val initialBackend = "CPU"
            override val photos = library
            override val index = database
            override val runtime = object : EmbeddingRuntime {
                override fun initialize(modelPath: String, backend: String, callback: OperationCallback) = error("Unexpected inference")
                override fun embedText(text: String, callback: VectorCallback) = error("Unexpected inference")
                override fun embedImage(path: String, callback: VectorCallback) = error("Unexpected inference")
                override fun close() {}
            }
            override fun prepareModel(observer: ModelObserver): ModelJob {
                observer.finished(null, null, true); return object : ModelJob { override fun cancel() {} }
            }
            override fun pickModel(callback: ModelImportCallback) {}
            override fun log(message: String) {}
        }
        val controller = DemoController(host, ioDispatcher = StandardTestDispatcher(testScheduler))
        try {
            controller.start()
            advanceUntilIdle()
            assertEquals(1, controller.state.value.imageCount)
            controller.toggleSelection(p.id); controller.deleteSelected()
            advanceUntilIdle()
            assertEquals(1, controller.state.value.imageCount); assertEquals(1, database.records.size)
            database.save(IndexedPhoto("now-unauthorized", "old", vector(), 1.0, 2.0, ""))
            library.permission = "limited"; library.observer!!.changed(); advanceUntilIdle()
            assertEquals("limited", controller.state.value.access); assertFalse(controller.state.value.canOrganize)
            assertEquals(setOf(p.id), database.records.keys); assertEquals(1, library.deleteCalls)
            library.cancelled = false; controller.deleteSelected()
            advanceUntilIdle()
            assertEquals(0, controller.state.value.imageCount); assertTrue(database.records.isEmpty())
            database.save(IndexedPhoto(p.id, indexRevision(p), vector(), 1.0, 2.0, ""))
            library.items = listOf(p); library.permission = "denied"; library.observer!!.changed()
            advanceTimeBy(400)
            advanceUntilIdle()
            assertEquals("denied", controller.state.value.access); assertTrue(controller.state.value.results.isEmpty()); assertTrue(database.records.isEmpty())
        } finally { controller.close(); Dispatchers.resetMain(); directory.deleteRecursively() }
    }

    @Test fun pauseFinishesTheCurrentPhotoAndResumeSkipsItsSavedVector() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val directory = Files.createTempDirectory("gemma-scan-resume").toFile()
        val library = Photos().apply { items = items + items.single().copy(id = "second", title = "second.jpg") }
        val database = MemoryIndex()
        var pending: VectorCallback? = null; var calls = 0
        val runtime = object : EmbeddingRuntime {
            override fun initialize(modelPath: String, backend: String, callback: OperationCallback) = callback.finished(null)
            override fun embedText(text: String, callback: VectorCallback) = callback.finished(vector(), null)
            override fun embedImage(path: String, callback: VectorCallback) { calls++; pending = callback }
            override fun close() {}
        }
        val host = object : PlatformServices {
            override val filesDirectory = directory.path
            override val platformLabel = "Test"
            override val initialBackend = "CPU"
            override val photos = library
            override val index = database
            override val runtime = runtime
            override fun prepareModel(observer: ModelObserver): ModelJob {
                observer.finished("/verified-model", null, false); return object : ModelJob { override fun cancel() {} }
            }
            override fun pickModel(callback: ModelImportCallback) {}
            override fun log(message: String) {}
        }
        val controller = DemoController(host, ioDispatcher = StandardTestDispatcher(testScheduler))
        try {
            controller.start(); advanceUntilIdle()
            assertEquals(1, calls); assertTrue(controller.state.value.scanning)
            controller.setQuery("a dog"); controller.search(); runCurrent()
            assertTrue(controller.state.value.searching)
            controller.pauseScan(); pending!!.finished(vector(), null); advanceUntilIdle()
            assertEquals(1, calls); assertEquals(1, database.records.size); assertTrue(controller.state.value.scanPaused)
            assertTrue(controller.state.value.searched); assertFalse(controller.state.value.searching)
            controller.scan(); advanceUntilIdle(); assertEquals(2, calls)
            pending!!.finished(vector(), null); advanceUntilIdle()
            assertEquals(2, database.records.size); assertEquals(2, controller.state.value.indexedCount)
            controller.scan(); advanceUntilIdle(); assertEquals(2, calls)
        } finally { controller.close(); Dispatchers.resetMain(); directory.deleteRecursively() }
    }

    @Test fun filteringPrunesHiddenSelectionsAndSurvivesDraftEditsAndAlbumChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val directory = Files.createTempDirectory("gemma-search-filter").toFile()
        val library = Photos().apply { items = listOf(
            items.single().copy(id = "related", title = "arbitrary.jpg"),
            items.single().copy(id = "unrelated", title = "a dog.jpg", albumIds = listOf("other-album"))) }
        val database = MemoryIndex()
        library.items.forEachIndexed { i, p -> database.save(IndexedPhoto(p.id, indexRevision(p),
            FloatArray(768).apply { this[i] = 1f }, 1.0, 2.0, "")) }
        var queryVector = vector()
        val host = object : PlatformServices {
            override val filesDirectory = directory.path
            override val platformLabel = "Test"
            override val initialBackend = "CPU"
            override val photos = library
            override val index = database
            override val runtime = object : EmbeddingRuntime {
                override fun initialize(modelPath: String, backend: String, callback: OperationCallback) = callback.finished(null)
                override fun embedText(text: String, callback: VectorCallback) = callback.finished(queryVector, null)
                override fun embedImage(path: String, callback: VectorCallback) = error("Cached vectors must be reused")
                override fun close() {}
            }
            override fun prepareModel(observer: ModelObserver): ModelJob {
                observer.finished("/verified-model", null, false); return object : ModelJob { override fun cancel() {} }
            }
            override fun pickModel(callback: ModelImportCallback) {}
            override fun log(message: String) {}
        }
        val controller = DemoController(host, ioDispatcher = StandardTestDispatcher(testScheduler))
        try {
            controller.start(); advanceUntilIdle()
            controller.toggleSelection("unrelated"); controller.setQuery("a dog"); controller.search(); advanceUntilIdle()
            assertEquals(listOf("related"), controller.state.value.results.map { it.item.id })
            assertTrue(controller.state.value.selected.isEmpty(), "Hidden photos cannot remain selected for deletion")
            controller.setQuery("an unsubmitted edit"); library.observer!!.changed(); advanceUntilIdle()
            assertEquals(listOf("related"), controller.state.value.results.map { it.item.id })
            controller.selectAlbum("other-album"); advanceUntilIdle()
            assertTrue(controller.state.value.results.isEmpty(), "A weak album-local winner is still unrelated")
            controller.setQuery(""); controller.search(); advanceUntilIdle()
            assertEquals(listOf("unrelated"), controller.state.value.results.map { it.item.id }); assertFalse(controller.state.value.searched)
            controller.selectAlbum(""); queryVector = FloatArray(768).apply { this[2] = 1f }
            controller.setQuery("a computer"); controller.search(); advanceUntilIdle()
            assertTrue(controller.state.value.results.isEmpty())
            assertEquals(2, database.records.size, "Filtering must not delete or recompute photo embeddings")
        } finally { controller.close(); Dispatchers.resetMain(); directory.deleteRecursively() }
    }
}
