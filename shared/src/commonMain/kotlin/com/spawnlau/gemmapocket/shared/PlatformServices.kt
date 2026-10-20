package com.spawnlau.gemmapocket.shared

/** Native inference stays in the official Kotlin/Swift SDKs; all app behavior is shared. */
interface OperationCallback { fun finished(error: String?) }
interface VectorCallback { fun finished(vector: FloatArray?, error: String?) }
interface ModelJob { fun cancel() }
interface ModelObserver {
    fun status(message: String)
    fun progress(received: Long, total: Long)
    fun finished(modelPath: String?, error: String?, cancelled: Boolean)
}
interface ModelImportCallback { fun finished(imported: Boolean, error: String?) }

interface EmbeddingRuntime {
    fun initialize(modelPath: String, backend: String, callback: OperationCallback)
    fun embedText(text: String, callback: VectorCallback)
    fun embedImage(path: String, callback: VectorCallback)
    fun close()
}

interface PlatformServices {
    val filesDirectory: String
    val platformLabel: String
    val initialBackend: String
    val runtime: EmbeddingRuntime
    val photos: SystemPhotoLibrary
    val index: PhotoIndex
    fun prepareModel(observer: ModelObserver): ModelJob
    fun pickModel(callback: ModelImportCallback)
    fun log(message: String)
}
