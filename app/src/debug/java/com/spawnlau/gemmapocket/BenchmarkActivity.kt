package com.spawnlau.gemmapocket

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import android.widget.TextView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.spawnlau.gemmapocket.shared.LibraryItem
import com.spawnlau.gemmapocket.shared.ModelSpec
import com.spawnlau.gemmapocket.shared.PhotoIndexCodec
import com.spawnlau.gemmapocket.shared.rankPhotos
import com.spawnlau.gemmapocket.shared.validateVector
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only harness. Uses bundled public samples, never the user's photo library/index. */
class BenchmarkActivity : Activity() {
    private lateinit var display: TextView
    private lateinit var output: File
    private val cancelled = AtomicBoolean(false)
    private val peakRss = AtomicLong(0)
    private val report = JSONObject()
    private val images = JSONArray()
    private val queries = JSONArray()
    private val memory = JSONArray()
    private val options = EmbeddingOptions(normalize = true, insertSpecialTokens = true,
        outputSize = 768, visionTokensPerImage = 70)

    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun elapsed(start: Long) = (now() - start) / 1_000_000.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Repeated process-cold runs must remain foreground even if the phone
        // auto-locks during the cooldown. Otherwise Samsung can freeze the app.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        display = TextView(this).apply { textSize = 18f; setPadding(32, 64, 32, 32) }
        setContentView(display)
        val runId = (intent.getStringExtra("runId") ?: "manual-${System.currentTimeMillis()}")
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        output = File(filesDir, "benchmarks/$runId.json").apply { parentFile?.mkdirs() }
        Thread({ execute(runId) }, "gemma-benchmark").start()
    }

    override fun onDestroy() { cancelled.set(true); super.onDestroy() }

    private fun checkpoint(phase: String) {
        check(!cancelled.get()) { "Benchmark cancelled" }
        report.put("phase", phase).put("peakSampledRssKb", peakRss.get())
        val partial = File(output.parentFile, output.name + ".partial")
        partial.writeText(report.toString(2))
        check(partial.renameTo(output)) { "Cannot save benchmark report" }
        runOnUiThread { display.text = "Gemma Pocket benchmark\n\n${report.optString("configuration")}\n\n$phase\n\nPublic sample photos only.\nReport: ${output.name}" }
    }

    private fun rss(): Long = File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0
    }

    private fun environment(): JSONObject {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = getSystemService(PowerManager::class.java)
        return JSONObject().put("batteryPercent", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
            .put("batteryTemperatureC", (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0)
            .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
            .put("thermalStatus", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1)
    }

    private fun snapshot(phase: String) {
        val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
        memory.put(JSONObject().put("phase", phase).put("pssKb", info.totalPss)
            .put("rssKb", rss()).put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("environment", environment()))
    }

    private fun execute(runId: String) {
        val backendName = intent.getStringExtra("backend") ?: "CPU"
        val threads = intent.getIntExtra("threads", 4).coerceIn(1, 8)
        val rounds = intent.getIntExtra("imageRounds", 4).coerceIn(0, 100)
        val queryRounds = intent.getIntExtra("queryRounds", 4).coerceIn(0, 30)
        val scale = intent.getBooleanExtra("scale", false)
        val coldCache = intent.getBooleanExtra("coldCache", true)
        val dataset = (intent.getStringExtra("dataset") ?: "builtin")
        require(dataset.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid dataset name" }
        val externalDataset = dataset != "builtin"
        val started = now()
        val samplerRunning = AtomicBoolean(true)
        val sampler = Thread({
            while (samplerRunning.get()) {
                try { peakRss.accumulateAndGet(rss(), ::maxOf); Thread.sleep(100) }
                catch (_: InterruptedException) { break }
            }
        }, "gemma-memory-sampler").apply { isDaemon = true; start() }
        var engine: EmbeddingEngine? = null
        report.put("schemaVersion", 1).put("runId", runId).put("status", "running")
            .put("configuration", "$backendName mainThreads=$threads")
            .put("backend", backendName).put("mainThreads", threads)
            .put("visionThreads", "SDK default; Kotlin 0.18.0 does not pass vision threadCount")
            .put("coldProcess", true).put("freshRuntimeCacheDirectory", coldCache)
            .put("downloadIncluded", false).put("modelSha256", ModelSpec.sha256)
            .put("modelBytes", ModelSpec.bytes).put("dimensions", 768)
            .put("maxInputLength", 256).put("visionTokensPerImage", 70)
            .put("sdk", "litertlm-android:0.18.0").put("buildType", "debug")
            .put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE).put("sdkInt", Build.VERSION.SDK_INT)
            .put("availableProcessors", Runtime.getRuntime().availableProcessors())
            .put("dataset", dataset).put("preprocessCameraOriginals", externalDataset)
            .put("startEnvironment", environment()).put("images", images).put("queries", queries).put("memory", memory)
        try {
            checkpoint("Verifying model")
            snapshot("beforeModel")
            val model = File(filesDir, "models/${ModelSpec.filename}")
            check(model.length() == ModelSpec.bytes) { "Provision the complete model first" }
            val verifyStart = now()
            val hash = MessageDigest.getInstance("SHA-256")
            model.inputStream().buffered(4 * 1024 * 1024).use { input ->
                val block = ByteArray(4 * 1024 * 1024)
                while (true) { val count = input.read(block); if (count < 0) break; hash.update(block, 0, count) }
            }
            check(hash.digest().joinToString("") { "%02x".format(it) } == ModelSpec.sha256) { "Model checksum mismatch" }
            report.put("modelVerificationMs", elapsed(verifyStart))
            val datasetDirectory = File(filesDir, "benchmarks/datasets/$dataset")
            val sampleManifest = JSONArray(if (externalDataset) File(datasetDirectory, "manifest.json").readText()
                else assets.open("samples.json").bufferedReader().readText())
            val sampleDir = File(filesDir, "benchmarks/samples").apply { mkdirs() }
            val photos = (0 until sampleManifest.length()).map { i ->
                val sample = sampleManifest.getJSONObject(i)
                val id = sample.getString("id")
                require(id.matches(Regex("[a-zA-Z0-9_-]+")))
                val file = if (externalDataset) File(datasetDirectory, "$id.jpg") else File(sampleDir, "$id.jpg")
                if (!externalDataset) assets.open("samples/${file.name}").use { input -> file.outputStream().use { input.copyTo(it) } }
                require(file.isFile) { "Dataset photo missing: $id" }
                if (externalDataset) require(file.length() == sample.getLong("bytes")) { "Dataset photo length mismatch" }
                LibraryItem(sample.getString("id"), "image", sample.getString("title"), file.absolutePath)
            }
            report.put("sampleCount", photos.size).put("sampleInputs", JSONArray(photos.map {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.path, bounds)
                JSONObject().put("id", it.id).put("bytes", File(it.path).length())
                    .put("width", bounds.outWidth).put("height", bounds.outHeight)
            }))
            val backend = if (backendName == "GPU") Backend.GPU() else Backend.CPU(threads)
            val runtimeCache = File(cacheDir, if (coldCache) "benchmark-$runId" else "benchmark-$backendName-$threads").apply { mkdirs() }
            checkpoint("Initializing model")
            val initStart = now()
            engine = EmbeddingEngine(EmbeddingEngineConfig(model.absolutePath, backend = backend,
                visionBackend = backend, cacheDir = runtimeCache.absolutePath, maxInputLength = 256, visionTokensPerImage = 70))
            engine.initialize()
            report.put("engineInitializationMs", elapsed(initStart)).put("readySinceHarnessStartMs", elapsed(started))
            snapshot("afterInitialization")
            val indexed = photos.toMutableList()
            val preparedDir = File(output.parentFile, "$runId-prepared").apply { mkdirs() }
            val indexFile = File(output.parentFile, "$runId-index.json")
            val firstPassStart = now()
            for (round in 0..rounds) {
                for ((i, photo) in photos.withIndex()) {
                    checkpoint("Image round ${round + 1}/${rounds + 1}, ${photo.id}")
                    val allStart = now(); val cpuStart = Process.getElapsedCpuTime()
                    val preprocessStart = now()
                    val prepared = if (externalDataset && round == 0) AndroidPhotoPreprocessor.prepare(
                        contentResolver, Uri.fromFile(File(photo.path)), File(preparedDir, "${photo.id}.jpg")) else null
                    val preprocessMs = if (prepared != null) elapsed(preprocessStart) else 0.0
                    val workingPhoto = if (externalDataset) photo.copy(path = File(preparedDir, "${photo.id}.jpg").absolutePath) else photo
                    val readStart = now(); val bytes = File(workingPhoto.path).readBytes(); val readMs = elapsed(readStart)
                    val nativeStart = now()
                    val vector = engine.computeEmbedding(listOf(InputData.Image(bytes)), options).embedding
                    val nativeMs = elapsed(nativeStart)
                    val validStart = now(); validateVector(vector); val validationMs = elapsed(validStart)
                    val saveStart = now()
                    indexed[i] = workingPhoto.copy(vector = vector, embeddingMs = nativeMs)
                    val encoded = PhotoIndexCodec.encode(indexed)
                    val partial = File(indexFile.parentFile, indexFile.name + ".partial")
                    partial.writeText(encoded); check(partial.renameTo(indexFile))
                    val saveMs = elapsed(saveStart)
                    images.put(JSONObject().put("round", round).put("id", photo.id).put("readMs", readMs)
                        .put("preprocessMs", preprocessMs).put("originalBytes", File(photo.path).length())
                        .put("processedBytes", bytes.size).put("processedWidth", prepared?.width).put("processedHeight", prepared?.height)
                        .put("inferenceMs", nativeMs).put("validationMs", validationMs).put("saveMs", saveMs)
                        .put("totalMs", elapsed(allStart)).put("processCpuMs", Process.getElapsedCpuTime() - cpuStart))
                    if (i % 10 == 9) snapshot("image-${round * photos.size + i + 1}")
                }
                if (round == 0) {
                    report.put("firstPassImagesElapsedMs", elapsed(firstPassStart)).put("indexBytes", indexFile.length())
                    if (photos.size == 6) report.put("firstSixImagesElapsedMs", elapsed(firstPassStart)).put("sixImageIndexBytes", indexFile.length())
                    snapshot("afterFirstIndex")
                }
                val env = environment()
                report.put("latestEnvironment", env)
                if (env.getInt("thermalStatus") >= PowerManager.THERMAL_STATUS_MODERATE) {
                    report.put("imageRoundsStoppedForThermalStatus", env.getInt("thermalStatus")); break
                }
            }
            snapshot("afterImages")
            val tests = if (externalDataset) listOf("a person walking on a street" to "", "a building" to "",
                "a tree in a park" to "", "a car" to "", "food on a plate" to "", "a landscape" to "")
                else listOf("a dog" to "dog", "a cat" to "cat", "beach vacation" to "beach",
                    "a cup of coffee" to "coffee", "a snowy mountain" to "mountain", "a bicycle" to "bicycle")
            report.put("queryAccuracyScored", !externalDataset)
            var lastQuery: FloatArray? = null
            for (round in 0..queryRounds) {
                for ((query, expected) in tests) {
                    checkpoint("Search round ${round + 1}/${queryRounds + 1}, $query")
                    val allStart = now(); val nativeStart = now()
                    val vector = engine.computeEmbedding(listOf(InputData.Text(ModelSpec.query(query))), options).embedding
                    val nativeMs = elapsed(nativeStart)
                    validateVector(vector)
                    val rankStart = now(); val ranking = rankPhotos(indexed, vector); val rankMs = elapsed(rankStart)
                    queries.put(JSONObject().put("round", round).put("query", query).put("expected", expected)
                        .put("top1", ranking.first().item.id).put("correct", if (externalDataset) JSONObject.NULL else ranking.first().item.id == expected)
                        .put("inferenceMs", nativeMs).put("rankMs", rankMs).put("totalMs", elapsed(allStart)))
                    lastQuery = vector
                }
            }
            report.put("correctQueries", if (externalDataset) JSONObject.NULL else
                (0 until queries.length()).count { queries.getJSONObject(it).getBoolean("correct") })
            snapshot("afterQueries")
            if (scale && lastQuery != null) benchmarkScale(indexed, lastQuery)
            report.put("status", "passed").put("totalElapsedMs", elapsed(started)).put("endEnvironment", environment())
            snapshot("beforeClose")
            checkpoint("Complete")
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.stackTraceToString()).put("totalElapsedMs", elapsed(started))
            // An interrupted activity must still leave a readable error report.
            cancelled.set(false); checkpoint("Failed: ${error.message}")
        } finally {
            engine?.close(); samplerRunning.set(false); sampler.interrupt()
        }
    }

    private fun benchmarkScale(indexed: List<LibraryItem>, query: FloatArray) {
        val results = JSONArray(); report.put("rankingScale", results)
        for (count in listOf(1000, 10000, 50000)) {
            checkpoint("Ranking $count synthetic entries")
            // Unique arrays model raw vector residency. These are repeats of six embeddings,
            // suitable for timing/cardinality only, never retrieval quality claims.
            val items = List(count) { i -> indexed[i % indexed.size].copy(id = "synthetic-$i", vector = indexed[i % indexed.size].vector!!.copyOf()) }
            val measurements = JSONArray()
            repeat(2) { rankPhotos(items, query) }
            repeat(7) { val start = now(); val result = rankPhotos(items, query); check(result.size == count); measurements.put(elapsed(start)) }
            val row = JSONObject().put("entries", count).put("syntheticRepeatedVectors", true)
                .put("rawVectorBytes", count.toLong() * 768 * 4).put("rankMs", measurements)
            results.put(row)
            if (count <= 1000) {
                val encodeStart = now(); val encoded = PhotoIndexCodec.encode(items)
                row.put("jsonEncodeMs", elapsed(encodeStart)).put("jsonBytes", encoded.toByteArray(Charsets.UTF_8).size)
                val decodeStart = now(); check(PhotoIndexCodec.decode(encoded).size == count)
                row.put("jsonDecodeMs", elapsed(decodeStart))
            }
            snapshot("ranking-$count")
        }
    }
}
