package com.spawnlau.gemmapocket

import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.spawnlau.gemmapocket.shared.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

internal class AndroidServices(private val activity: MainActivity) : PlatformServices {
    override val filesDirectory = activity.filesDir.absolutePath
    override val platformLabel = "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"
    private val preferences = activity.getPreferences(0)
    override val initialBackend: String = activity.intent.getStringExtra("backend")?.uppercase()
        ?.takeIf { it == "CPU" || it == "GPU" } ?: if (preferences.getBoolean("gpu", false)) "GPU" else "CPU"
    override val runtime: EmbeddingRuntime = AndroidRuntime(activity, preferences)
    private val io = Executors.newSingleThreadExecutor()
    override val photos: SystemPhotoLibrary = AndroidPhotos(activity)
    override val index: PhotoIndex = AndroidPhotoIndex(activity)
    private var importCallback: ModelImportCallback? = null
    private var download: ModelDownloader.Cancellation? = null
    private val model get() = File(activity.filesDir, "models/${ModelSpec.filename}")

    private val modelPicker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = importCallback ?: return@registerForActivityResult
        importCallback = null
        if (uri == null) callback.finished(false, null) else io.execute {
            val partial = File(model.parentFile, "${ModelSpec.filename}.import.part")
            try {
                model.parentFile!!.mkdirs()
                activity.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot open file" }
                    partial.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024); var total = 0L
                        while (true) { val n = input.read(buffer); if (n < 0) break
                            total += n; require(total <= ModelSpec.bytes) { "Select the original 388 MB model file" }; out.write(buffer, 0, n) }
                    }
                }
                ModelDownloader().installVerified(partial, model, ModelDownloader.Cancellation())
                callback.finished(true, null)
            } catch (e: Exception) { partial.delete(); callback.finished(false, "Model import failed: ${e.message}") }
        }
    }
    override fun prepareModel(observer: ModelObserver): ModelJob {
        val cancellation = ModelDownloader.Cancellation(); download = cancellation
        io.execute {
            try {
                if (model.isFile) {
                    observer.status("Verifying local model...")
                    if (!verified(model, cancellation)) model.delete()
                }
                if (!model.isFile) ModelDownloader().download(model, object : ModelDownloader.Listener {
                    override fun connecting(host: String) { observer.status("Connecting to $host...") }
                    override fun progress(received: Long, total: Long) { observer.progress(received, total) }
                    override fun verifying() { observer.status("Verifying download...") }
                }, cancellation)
                cancellation.check()
                observer.finished(model.absolutePath, null, false)
            } catch (e: Exception) { observer.finished(null, if (cancellation.isCancelled) null else e.message, cancellation.isCancelled) }
        }
        return object : ModelJob { override fun cancel() { cancellation.cancel() } }
    }
    private fun verified(file: File, cancellation: ModelDownloader.Cancellation): Boolean {
        if (file.length() != ModelSpec.bytes) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(1024 * 1024)
            while (true) { cancellation.check(); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) } == ModelSpec.sha256
    }
    override fun pickModel(callback: ModelImportCallback) {
        importCallback = callback
        activity.runOnUiThread { modelPicker.launch(arrayOf("*/*")) }
    }
    override fun log(message: String) { Log.i("GemmaPocket", message) }
    fun resumed() { (photos as AndroidPhotos).notifyChange() }
    fun dispose() { download?.cancel(); io.shutdown(); photos.close() }
}

private class AndroidRuntime(private val activity: MainActivity, private val preferences: android.content.SharedPreferences) : EmbeddingRuntime {
    // GPU initialization and every inference call stay on the same native thread.
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: EmbeddingEngine? = null
    override fun initialize(modelPath: String, backend: String, callback: OperationCallback) { worker.execute {
        try {
            engine?.close(); engine = null
            val selected = if (backend == "GPU") Backend.GPU() else Backend.CPU(4)
            val next = EmbeddingEngine(EmbeddingEngineConfig(modelPath = modelPath, backend = selected,
                visionBackend = selected, cacheDir = activity.cacheDir.absolutePath, maxInputLength = 256, visionTokensPerImage = 70))
            try { next.initialize(); engine = next } catch (e: Exception) { next.close(); throw e }
            preferences.edit().putBoolean("gpu", backend == "GPU").apply()
            callback.finished(null)
        } catch (e: Throwable) { callback.finished("$backend initialization failed: ${e.message}") }
    } }
    override fun embedText(text: String, callback: VectorCallback) = compute(InputData.Text(text), callback)
    override fun embedImage(path: String, callback: VectorCallback) = compute(InputData.Image(File(path).readBytes()), callback)
    private fun compute(content: InputData, callback: VectorCallback) { worker.execute {
        try { callback.finished(requireNotNull(engine).computeEmbedding(listOf(content), EmbeddingOptions(
            normalize = true, insertSpecialTokens = true, outputSize = 768, visionTokensPerImage = 70)).embedding, null) }
        catch (e: Throwable) { callback.finished(null, e.message ?: "Inference failed") }
    } }
    override fun close() { worker.execute { engine?.close(); engine = null }; worker.shutdown() }
}
