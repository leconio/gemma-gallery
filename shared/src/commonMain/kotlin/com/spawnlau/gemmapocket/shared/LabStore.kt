package com.spawnlau.gemmapocket.shared

import kotlinx.serialization.encodeToString
import okio.FileSystem
import okio.Path.Companion.toPath

class LabStore(directory: String) {
    private val fs = FileSystem.SYSTEM
    private val root = directory.toPath()
    private val file = root / "lab-v1.json"
    fun load(): LabDocument {
        if (!fs.exists(file)) return LabDocument()
        val document = json.decodeFromString<LabDocument>(fs.read(file) { readUtf8() })
        require(document.schema == 1 && document.tags.size <= LabSpec.maxTags) { "Unsupported Lab data; the original file is retained" }
        val tags = document.tags.map { tag ->
            if (tag.textVersion == LabSpec.textVersion && tag.textVector?.let { runCatching { validateVector(it) }.isSuccess } == true) tag
            else tag.copy(textVector = null, textVersion = "")
        }
        tags.forEach(::validateLabTag)
        require(tags.map { it.id }.distinct().size == tags.size)
        return document.copy(tags = tags).also(::validateLabDocument)
    }
    fun save(document: LabDocument) {
        validateLabDocument(document)
        writeAtomic("lab-v1.json", json.encodeToString(document))
    }
    fun saveReport(report: LabReport) = writeAtomic("lab-last-report.json", json.encodeToString(report))
    fun clearReport() { fs.delete(root / "lab-last-report.json", mustExist = false) }
    private fun writeAtomic(name: String, content: String) {
        fs.createDirectories(root)
        val temporary = root / "$name.part"
        fs.write(temporary) { writeUtf8(content) }
        fs.atomicMove(temporary, root / name)
    }
}

private fun validateLabDocument(doc: LabDocument) {
    require(doc.schema == 1 && doc.tags.size <= LabSpec.maxTags) { "Unsupported Lab data" }
    doc.tags.forEach(::validateLabTag)
    val tagIds = doc.tags.map { it.id }.toSet()
    require(tagIds.size == doc.tags.size) { "Duplicate tags" }
    require(doc.decisions.all { it.photoId.isNotBlank() && it.tagId in tagIds && it.status in setOf("confirmed", "rejected") } &&
        doc.decisions.map { it.photoId to it.tagId }.distinct().size == doc.decisions.size) { "Invalid saved reviews" }
    doc.grouping?.let { group ->
        val ids = group.groups.flatMap { it.photoIds } + group.unassigned
        require(group.cutoff.isFinite() && group.cutoff in 0.0..1.0 && ids.size <= LabSpec.maxPhotos && ids.distinct().size == ids.size) { "Invalid saved group membership" }
        require(group.groups.map { it.id }.distinct().size == group.groups.size && group.groups.all {
            it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= 60 && it.representativeId in it.photoIds
        }) { "Invalid saved groups" }
        require(group.revisions.keys == ids.toSet()) { "Invalid saved group revisions" }
    }
}
