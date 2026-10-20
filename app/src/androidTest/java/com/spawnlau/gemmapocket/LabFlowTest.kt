package com.spawnlau.gemmapocket

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File

/** Drives production UI with the real native runtime and a dedicated emulator MediaStore. */
@RunWith(AndroidJUnit4::class)
class LabFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val evidence = File(context.filesDir, "lab-evidence").apply { mkdirs() }
    @After fun finalScreen() { capture("last-screen"); device.dumpWindowHierarchy(File(evidence, "last-hierarchy.xml")) }
    private fun requireEmulator() { check(Build.SUPPORTED_ABIS.first() == "x86_64") { "Lab acceptance runs only on an x86_64 emulator" } }
    private fun launch() {
        requireEmulator(); device.wakeUp()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        node("lab-open", 30_000)
    }
    private fun node(tag: String, timeout: Long = 12_000): UiObject2 = device.wait(Until.findObject(By.res(tag)), timeout) ?: error("Missing UI: $tag")
    private fun click(tag: String) {
        val item = device.wait(Until.findObject(By.res(tag).enabled(true)), 30_000) ?: error("Disabled or missing: $tag")
        item.click(); device.waitForIdle(); SystemClock.sleep(200)
    }
    private fun field(tag: String, value: String) {
        node(tag).click(); device.waitForIdle(); node(tag).text = value
        waitFor("field $tag") { node(tag).text == value }
        device.pressBack(); device.waitForIdle(); SystemClock.sleep(250)
    }
    private fun waitFor(description: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (runCatching(condition).getOrDefault(false)) return; SystemClock.sleep(150) }
        error("Timed out: $description")
    }
    private fun doc() = JSONObject(File(context.filesDir, "lab-v1.json").readText())
    private fun report() = JSONObject(File(context.filesDir, "lab-last-report.json").readText())
    private fun capture(name: String) { device.waitForIdle(); assertTrue(device.takeScreenshot(File(evidence, "$name.png"))) }
    private fun copyReport(name: String) { File(context.filesDir, "lab-last-report.json").copyTo(File(evidence, "$name.json"), overwrite = true) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun inventory(): Map<Long, Long> {
        val result = sortedMapOf<Long, Long>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id", "_size"), null, null, "_id ASC")!!.use { cursor ->
            while (cursor.moveToNext()) result[cursor.getLong(0)] = cursor.getLong(1)
        }
        return result
    }
    private fun pick(file: String) {
        field("lab-pick-filter", file)
        var item = device.findObject(By.res("lab-pick-$file"))
        repeat(8) { if (item == null) { node("lab-picker").scroll(Direction.DOWN, .7f); item = device.findObject(By.res("lab-pick-$file")) } }
        requireNotNull(item) { "Picker photo missing: $file" }.click(); click("lab-pick-done")
    }
    private fun createTag(name: String, description: String, examples: Boolean = false) {
        click("lab-new-tag"); field("lab-tag-name", name); field("lab-tag-description", description)
        field("lab-tag-example-cutoff", "0.80")
        if (examples) { click("lab-positive"); pick("GemmaLab-001.jpg"); click("lab-negative"); pick("GemmaLab-002.jpg") }
        click("lab-save-tag"); waitFor("tag saved") { doc().getJSONArray("tags").objects().any { it.getString("name") == name } }
    }
    @Test fun features() {
        val originalPhotos = inventory()
        launch()
        val expected = originalPhotos.size.toString()
        waitFor("real photos indexed", 600_000) { device.findObject(By.res("index-count"))?.text == "$expected / $expected indexed" && device.findObject(By.res("app-status"))?.text == "Ready" }
        capture("01-library"); click("lab-open")
        click("lab-scope"); field("lab-pick-filter", "GemmaLab-"); click("lab-pick-clear"); click("lab-pick-clear"); click("lab-pick-done")
        waitFor("fixture scope") { node("lab-scope-count").text.startsWith("14 indexed") }
        createTag("Dogs", "a photo of a dog", true)
        createTag("Pets", "a photo of a pet dog or cat")
        createTag("Beaches", "a photo of a sandy beach and the sea")
        click("lab-run-text")
        waitFor("text labels") { report().optJSONObject("tagRun")?.optString("mode") == "Text" }
        val text = report(); val textRun = text.getJSONObject("tagRun"); val photos = text.getJSONObject("photos")
        val dogId = photos.keys().asSequence().first { photos.getString(it) == "GemmaLab-007.jpg" }
        val dogsTag = text.getJSONArray("tags").objects().first { it.getString("name") == "Dogs" }.getString("id")
        assertTrue(textRun.getJSONArray("scores").objects().any { it.getString("photoId") == dogId && it.getString("tagId") == dogsTag && it.getBoolean("suggested") })
        val references = textRun.getJSONArray("referenceIds").strings().toSet()
        assertFalse(textRun.getJSONArray("scores").objects().any { it.getString("photoId") in references })
        copyReport("02-text-labels"); capture("02-tags-text")
        // Scroll to the held-out dog, then confirm a real result.
        var confirm = device.findObject(By.res("lab-confirm-GemmaLab-007.jpg-Dogs"))
        repeat(10) { if (confirm == null) { node("lab-tag-results").scroll(Direction.DOWN, .6f); confirm = device.findObject(By.res("lab-confirm-GemmaLab-007.jpg-Dogs")) } }
        requireNotNull(confirm).click()
        waitFor("review saved") { doc().getJSONArray("decisions").objects().any { it.getString("photoId") == dogId && it.getString("tagId") == dogsTag && it.getString("status") == "confirmed" } }
        click("lab-confirmed-only"); node("lab-tag-row-GemmaLab-007.jpg-Dogs"); capture("03-confirmed")
        click("lab-confirmed-only"); click("lab-run-examples")
        waitFor("example labels") { report().optJSONObject("tagRun")?.optString("mode") == "Examples" }
        val examples = report().getJSONObject("tagRun")
        assertEquals(2, examples.getJSONArray("skippedTagIds").length())
        assertTrue(examples.getJSONArray("scores").objects().any { it.getString("photoId") == dogId && it.getBoolean("suggested") })
        copyReport("04-example-labels"); capture("04-tags-examples")

        click("lab-tab-similar"); click("lab-reference"); pick("GemmaLab-001.jpg"); click("lab-run-similar")
        waitFor("image neighbors") { report().optJSONObject("similarRun") != null }
        val similar = report().getJSONObject("similarRun"); val neighbors = similar.getJSONArray("results").objects()
        assertTrue(neighbors.isNotEmpty()); assertEquals(dogId, neighbors.first().getString("photoId"))
        assertFalse(neighbors.any { it.getString("photoId") == similar.getString("referenceId") })
        copyReport("05-similar"); capture("05-similar")
        field("lab-similar-cutoff", "1.0"); click("lab-run-similar")
        waitFor("new similarity cutoff") { report().getJSONObject("similarRun").getDouble("cutoff") == 1.0 }
        node("lab-similar-empty"); assertEquals(0, report().getJSONObject("similarRun").getJSONArray("results").length()); capture("06-similar-empty")
        field("lab-similar-cutoff", "0.80"); click("lab-run-similar")

        click("lab-tab-groups"); field("lab-group-cutoff", "0.80"); click("lab-run-groups")
        waitFor("group run") { report().optJSONObject("grouping") != null }
        var grouping = doc().getJSONObject("grouping")
        val groups = grouping.getJSONArray("groups").objects(); assertTrue(groups.isNotEmpty())
        fun coverage(g: JSONObject): List<String> = g.getJSONArray("groups").objects().flatMap { it.getJSONArray("photoIds").strings() } + g.getJSONArray("unassigned").strings()
        val all = coverage(grouping); assertEquals(14, all.size); assertEquals(14, all.distinct().size)
        copyReport("07-auto-groups"); capture("07-groups")
        val first = groups.first(); val reviewedId = first.getString("id"); click("lab-edit-group-$reviewedId")
        field("lab-group-name", "Reviewed group"); click("lab-save-group-name")
        waitFor("rename") { doc().getJSONObject("grouping").getJSONArray("groups").objects().any { it.getString("name") == "Reviewed group" } }
        click("lab-edit-group-$reviewedId")
        val splitId = first.getJSONArray("photoIds").getString(0); val splitTitle = photos.getString(splitId)
        click("lab-group-pick-$splitTitle"); field("lab-group-name", "Split group"); click("lab-move-selected")
        waitFor("split") { doc().getJSONObject("grouping").getJSONArray("groups").objects().any { it.getString("name") == "Split group" } }
        // Split group is last; scroll to its editor.
        val splitGroupId = doc().getJSONObject("grouping").getJSONArray("groups").objects().first { it.getString("name") == "Split group" }.getString("id")
        var edit = device.findObject(By.res("lab-edit-group-$splitGroupId"))
        repeat(10) { if (edit == null) { node("lab-group-results").scroll(Direction.DOWN, .7f); edit = device.findObject(By.res("lab-edit-group-$splitGroupId")) } }
        requireNotNull(edit).click(); click("lab-group-destination"); click("lab-destination-$reviewedId"); click("lab-merge-group")
        waitFor("merge") { doc().getJSONObject("grouping").getJSONArray("groups").objects().none { it.getString("name") == "Split group" } }
        grouping = doc().getJSONObject("grouping"); assertEquals(all.toSet(), coverage(grouping).toSet()); assertEquals(14, coverage(grouping).size)
        copyReport("08-edited-groups"); capture("08-edited-groups")
        click("lab-export")
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.intentresolver")), 10_000)); capture("09-export-sheet"); device.pressBack()
        assertEquals(originalPhotos, inventory())
        File(evidence, "feature-result.json").writeText(JSONObject().put("status", "passed").put("photos", 14).put("libraryPhotos", originalPhotos.size)
            .put("photosUnchanged", true).put("runtime", "real LiteRT-LM x86_64 CPU").toString(2))
    }

    /** Called in a second instrumentation invocation after the host force-stops the app. */
    @Test fun persisted() {
        launch(); click("lab-open")
        waitFor("saved tags loaded") { doc().getJSONArray("tags").length() == 3 }
        click("lab-confirmed-only"); node("lab-tag-row-GemmaLab-007.jpg-Dogs", 30_000); capture("10-restart-tags")
        val dogs = doc().getJSONArray("tags").objects().first { it.getString("name") == "Dogs" }
        assertEquals(1, dogs.getJSONArray("positiveIds").length()); assertEquals(1, dogs.getJSONArray("negativeIds").length())
        click("lab-tab-groups")
        val reviewedId = doc().getJSONObject("grouping").getJSONArray("groups").objects().first { it.getString("name") == "Reviewed group" }.getString("id")
        node("lab-group-$reviewedId", 30_000); capture("11-restart-groups")
        assertEquals(3, doc().getJSONArray("tags").length())
        File(evidence, "restart-result.json").writeText("{\"status\":\"passed\",\"processRestart\":true}")
    }
}
