package com.spawnlau.gemmapocket.shared

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal expect fun labResourceTags(): Modifier

@Composable
internal fun LabScreen(lab: LabController, controller: DemoController, demo: DemoState) {
    val state by lab.state.collectAsState()
    var tab by remember { mutableStateOf("Tags") }
    var pickingScope by remember { mutableStateOf(false) }
    val photos = demo.labPhotos
    val inputs = lab.inputs()
    val enabled = state.loaded && !state.busy && !state.saving && !demo.scanning && !demo.busy && demo.ready
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf("Tags", "Similar", "Groups").forEach { name ->
                FilterChip(tab == name, { tab = name }, label = { Text(name) }, modifier = Modifier.testTag("lab-tab-${name.lowercase()}").padding(end = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${inputs.size} indexed photos · limit 200", fontSize = 12.sp, modifier = Modifier.weight(1f).testTag("lab-scope-count"))
            LabAction({ pickingScope = true }, enabled = enabled, modifier = Modifier.testTag("lab-scope")) { Text("Choose photos", fontSize = 12.sp) }
        }
        if (demo.scanning) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pause the scan to run Lab experiments.", fontSize = 12.sp, modifier = Modifier.weight(1f))
            LabAction(controller::pauseScan, modifier = Modifier.testTag("lab-pause-scan")) { Text("Pause scan") }
        }
        if (!demo.ready) Text(demo.status, fontSize = 12.sp)
        if (state.busy || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.message.isNotBlank()) Text(state.message, fontSize = 12.sp, modifier = Modifier.testTag("lab-status"))
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.testTag("lab-error"))
        when (tab) {
            "Tags" -> TagLab(lab, controller, state, photos, enabled, Modifier.weight(1f))
            "Similar" -> SimilarLab(lab, controller, state, inputs, enabled, Modifier.weight(1f))
            "Groups" -> GroupLab(lab, controller, state, photos, enabled, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lab data stays on this device.\nPhotos remain in your system library.", fontSize = 10.sp, color = Color.DarkGray, modifier = Modifier.weight(1f))
            LabAction(lab::exportReport, enabled = enabled, modifier = Modifier.testTag("lab-export")) { Text("Export report", fontSize = 12.sp) }
        }
    }
    if (pickingScope) LabPhotoPicker(controller, photos.filter { it.vector != null }, inputs.map { it.id }.toSet(), 200, {
        lab.setScope(it); pickingScope = false
    }, { pickingScope = false })
}

@Composable
private fun TagLab(lab: LabController, controller: DemoController, state: LabState, photos: List<LibraryItem>, enabled: Boolean, modifier: Modifier) {
    var editing by remember { mutableStateOf<LabTag?>(null) }
    var tagFilter by remember { mutableStateOf("") }
    var confirmedOnly by remember { mutableStateOf(false) }
    var below by remember { mutableStateOf(false) }
    val tags = state.document.tags
    val byId = photos.associateBy { it.id }
    val decisions = state.document.decisions.associateBy { it.photoId to it.tagId }
    val rows = if (confirmedOnly) state.document.decisions.filter { it.status == "confirmed" }.map {
        state.tagRun?.scores?.firstOrNull { score -> score.photoId == it.photoId && score.tagId == it.tagId }
            ?: LabTagScore(it.photoId, it.tagId, 0.0, 0.0, false, mode = "Reviewed")
    } else state.tagRun?.scores.orEmpty().filter { below || it.suggested || decisions[it.photoId to it.tagId] != null }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            LabAction({ editing = LabTag("new", "", "") }, enabled, Modifier.testTag("lab-new-tag")) { Text("New tag") }
            LabAction({ lab.runTags("Text") }, enabled && tags.isNotEmpty(), Modifier.testTag("lab-run-text")) { Text("Run text") }
            LabAction({ lab.runTags("Examples") }, enabled && tags.isNotEmpty(), Modifier.testTag("lab-run-examples")) { Text("Run examples") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(tagFilter.isEmpty(), { tagFilter = "" }, label = { Text("All tags") })
            tags.forEach { tag ->
                InputChip(tagFilter == tag.id, { tagFilter = if (tagFilter == tag.id) "" else tag.id }, label = { Text(tag.name) },
                    trailingIcon = { Text("Edit", Modifier.testTag("lab-edit-${tag.name}").clickable(enabled) { editing = tag }, fontSize = 11.sp) }, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(confirmedOnly, { confirmedOnly = it }, Modifier.testTag("lab-confirmed-only")); Text("Confirmed only", fontSize = 12.sp)
            Checkbox(below, { below = it }, Modifier.testTag("lab-below")); Text("Below cutoff", fontSize = 12.sp)
        }
        Text("Scores are similarities, not probabilities. Reference photos are excluded from both modes.", fontSize = 11.sp, color = Color.DarkGray)
        if (tags.isEmpty()) Text("Create a tag with a description, then add examples if needed.", Modifier.padding(vertical = 16.dp), fontSize = 13.sp)
        LazyColumn(Modifier.weight(1f).testTag("lab-tag-results")) {
            items(rows.filter { (tagFilter.isEmpty() || it.tagId == tagFilter) && it.photoId in byId }, key = { "${it.photoId}|${it.tagId}" }) { row ->
                val photo = byId.getValue(row.photoId); val tag = tags.first { it.id == row.tagId }
                val decision = decisions[row.photoId to row.tagId]?.status
                Column(Modifier.fillMaxWidth().testTag("lab-tag-row-${photo.title}-${tag.name}").padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabThumb(controller, photo, Modifier.size(64.dp))
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(tag.name, fontWeight = FontWeight.Medium); Text(photo.title, fontSize = 11.sp)
                            Text((if (row.mode == "Reviewed") "" else "${score(row.score)} · ${row.mode}") +
                                (decision?.let { " · $it" } ?: if (row.suggested) " · suggested" else " · below cutoff"), fontSize = 11.sp)
                            row.negativeScore?.let { Text("Negative ${score(it)} · margin ${score(row.score - it)}", fontSize = 10.sp) }
                        }
                    }
                    Row {
                        LabAction({ lab.review(row.photoId, row.tagId, "confirmed") }, enabled, Modifier.testTag("lab-confirm-${photo.title}-${tag.name}")) { Text("Confirm") }
                        LabAction({ lab.review(row.photoId, row.tagId, "rejected") }, enabled) { Text("Reject") }
                        if (decision != null) LabAction({ lab.review(row.photoId, row.tagId, null) }, enabled) { Text("Clear review") }
                    }
                    HorizontalDivider()
                }
            }
            if (tags.isNotEmpty() && rows.isEmpty()) item { Text(if (confirmedOnly) "No confirmed labels." else if (state.tagRun == null) "Choose a mode to preview labels." else "No photos meet these cutoffs.", Modifier.padding(vertical = 16.dp), fontSize = 13.sp) }
        }
    }
    editing?.let { tag -> TagEditor(tag, photos, controller, { lab.saveTag(it); editing = null }, { lab.deleteTag(tag.id); editing = null }, { editing = null }) }
}

@Composable
private fun TagEditor(tag: LabTag, photos: List<LibraryItem>, controller: DemoController, save: (LabTag) -> Unit, delete: () -> Unit, close: () -> Unit) {
    var name by remember(tag.id) { mutableStateOf(tag.name) }; var description by remember(tag.id) { mutableStateOf(tag.description) }
    var textCutoff by remember(tag.id) { mutableStateOf(tag.textCutoff.toString()) }; var exampleCutoff by remember(tag.id) { mutableStateOf(tag.exampleCutoff.toString()) }
    var positive by remember(tag.id) { mutableStateOf(tag.positiveIds.toSet()) }; var negative by remember(tag.id) { mutableStateOf(tag.negativeIds.toSet()) }
    var pick by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && description.isNotBlank() && cutoff(textCutoff) != null && cutoff(exampleCutoff) != null
    AlertDialog(onDismissRequest = close, title = { Text(if (tag.id == "new") "New tag" else "Edit tag") }, text = {
        Column(labResourceTags().verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.testTag("lab-tag-name"))
            OutlinedTextField(description, { description = it.take(240) }, label = { Text("Description") }, minLines = 2, modifier = Modifier.testTag("lab-tag-description"))
            CutoffField(textCutoff, { textCutoff = it }, "Text cutoff", "lab-tag-text-cutoff")
            CutoffField(exampleCutoff, { exampleCutoff = it }, "Example cutoff", "lab-tag-example-cutoff")
            LabAction({ pick = "positive" }, modifier = Modifier.testTag("lab-positive")) { Text("Positive examples · ${positive.size}") }
            LabAction({ pick = "negative" }, modifier = Modifier.testTag("lab-negative")) { Text("Negative examples · ${negative.size}") }
            Text("Example mode needs positive examples. A negative example can block a match. Reviews save labels; they do not train the model.", fontSize = 11.sp)
        }
    }, confirmButton = { LabAction({ save(tag.copy(name = name, description = description, textCutoff = cutoff(textCutoff)!!, exampleCutoff = cutoff(exampleCutoff)!!,
        positiveIds = positive.toList(), negativeIds = negative.toList())) }, valid, labResourceTags().testTag("lab-save-tag")) { Text("Save") } },
        dismissButton = { Row { if (tag.id != "new") LabAction(delete, modifier = Modifier.testTag("lab-delete-tag")) { Text("Delete") }; LabAction(close) { Text("Cancel") } } })
    if (pick.isNotEmpty()) LabPhotoPicker(controller, photos.filter { it.vector != null && it.id !in (if (pick == "positive") negative else positive) },
        if (pick == "positive") positive else negative, 20, { if (pick == "positive") positive = it else negative = it; pick = "" }, { pick = "" })
}

@Composable
private fun SimilarLab(lab: LabController, controller: DemoController, state: LabState, photos: List<LibraryItem>, enabled: Boolean, modifier: Modifier) {
    var reference by remember { mutableStateOf("") }; var picking by remember { mutableStateOf(false) }; var threshold by remember { mutableStateOf("0.80") }
    val byId = photos.associateBy { it.id }
    val run = state.similarRun
    LaunchedEffect(run?.referenceId) { run?.let { reference = it.referenceId } }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            byId[reference]?.let { LabThumb(controller, it, Modifier.size(56.dp)) }
            LabAction({ picking = true }, enabled, Modifier.testTag("lab-reference")) { Text("Choose a reference") }
        }
        CutoffField(threshold, { threshold = it }, "Minimum similarity", "lab-similar-cutoff")
        Button({ lab.runSimilar(reference, cutoff(threshold)!!) }, enabled = enabled && reference in byId && cutoff(threshold) != null, modifier = Modifier.testTag("lab-run-similar")) { Text("Find similar") }
        Text("Top 10 matches. The reference photo is excluded.", fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f).testTag("lab-similar-results")) {
            items(run?.results.orEmpty().filter { it.photoId in byId }, key = { it.photoId }) { row ->
                val photo = byId.getValue(row.photoId)
                Row(Modifier.fillMaxWidth().testTag("lab-neighbor-${photo.title}").padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LabThumb(controller, photo, Modifier.size(84.dp)); Text("${photo.title}\n${score(row.score)}", Modifier.padding(start = 12.dp), fontSize = 13.sp)
                }
            }
            if (run != null && run.results.isEmpty()) item { Text("No photos meet this cutoff.", Modifier.testTag("lab-similar-empty").padding(vertical = 16.dp)) }
        }
    }
    if (picking) LabPhotoPicker(controller, photos, setOf(reference), 1, { reference = it.firstOrNull().orEmpty(); picking = false }, { picking = false })
}

@Composable
private fun GroupLab(lab: LabController, controller: DemoController, state: LabState, photos: List<LibraryItem>, enabled: Boolean, modifier: Modifier) {
    var threshold by remember { mutableStateOf("0.80") }; var editing by remember { mutableStateOf<String?>(null) }
    val grouping = state.document.grouping
    val byId = photos.associateBy { it.id }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CutoffField(threshold, { threshold = it }, "Within-group similarity", "lab-group-cutoff", Modifier.weight(1f))
            LabAction({ lab.runGroups(cutoff(threshold)!!) }, enabled && cutoff(threshold) != null, Modifier.testTag("lab-run-groups")) { Text("Group photos") }
        }
        Text("Automatic groups require every pair to meet this cutoff. Manual edits are marked.", fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f).testTag("lab-group-results")) {
            items(grouping?.groups.orEmpty(), key = { it.id }) { group ->
                Column(Modifier.fillMaxWidth().testTag("lab-group-${group.id}").padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        byId[group.representativeId]?.let { LabThumb(controller, it, Modifier.size(64.dp)) }
                        Text("${group.name} · ${group.photoIds.size}" + if (group.edited) " · edited" else "", Modifier.padding(start = 10.dp).weight(1f), fontSize = 14.sp)
                        LabAction({ editing = group.id }, enabled, Modifier.testTag("lab-edit-group-${group.id}")) { Text("Edit") }
                    }
                    LazyRow(Modifier.padding(top = 6.dp)) {
                        items(group.photoIds, key = { it }) { id -> byId[id]?.let { LabThumb(controller, it, Modifier.padding(end = 4.dp).size(48.dp)) } }
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
            if (grouping != null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Unassigned · ${grouping.unassigned.size}", modifier = Modifier.weight(1f).testTag("lab-unassigned"))
                    if (grouping.unassigned.isNotEmpty()) LabAction({ editing = "unassigned" }, enabled, Modifier.testTag("lab-edit-unassigned")) { Text("Edit") }
                }
                LazyRow { items(grouping.unassigned, key = { it }) { id -> byId[id]?.let { LabThumb(controller, it, Modifier.padding(end = 4.dp).size(52.dp)) } } }
            }
        }
    }
    if (grouping != null && editing != null) {
        val group = grouping.groups.firstOrNull { it.id == editing }
        GroupEditor(group, grouping, photos, controller, { name -> if (group != null) lab.renameGroup(group.id, name); editing = null },
            { ids, target, name -> lab.movePhotos(ids, target, name); editing = null }, { editing = null })
    }
}

@Composable
private fun GroupEditor(group: LabGroup?, grouping: LabGrouping, photos: List<LibraryItem>, controller: DemoController,
    rename: (String) -> Unit, move: (Set<String>, String?, String) -> Unit, close: () -> Unit) {
    var name by remember { mutableStateOf(group?.name ?: "New group") }; var selected by remember { mutableStateOf(emptySet<String>()) }
    var target by remember { mutableStateOf<String?>(null) }; var destinations by remember { mutableStateOf(false) }
    val ids = group?.photoIds ?: grouping.unassigned
    AlertDialog(onDismissRequest = close, title = { Text("Edit group") }, text = {
        Column(labResourceTags()) {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Group name") }, modifier = Modifier.testTag("lab-group-name"), singleLine = true)
            if (group != null) LabAction({ rename(name) }, name.isNotBlank(), Modifier.testTag("lab-save-group-name")) { Text("Save name") }
            Text("Select photos to move or split", fontSize = 12.sp)
            LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.heightIn(max = 220.dp)) {
                items(photos.filter { it.id in ids }, key = { it.id }) { photo ->
                    Box(Modifier.aspectRatio(1f).testTag("lab-group-pick-${photo.title}").padding(2.dp).border(if (photo.id in selected) 3.dp else 0.dp, Color(0xff2867c7))
                        .clickable { selected = if (photo.id in selected) selected - photo.id else selected + photo.id }) { LabThumb(controller, photo, Modifier.fillMaxSize()) }
                }
            }
            Box {
                LabAction({ destinations = true }, modifier = Modifier.testTag("lab-group-destination")) { Text(grouping.groups.firstOrNull { it.id == target }?.name ?: "New group") }
                DropdownMenu(destinations, { destinations = false }, modifier = labResourceTags()) {
                    DropdownMenuItem({ Text("New group") }, { target = null; destinations = false })
                    grouping.groups.filter { it.id != group?.id }.forEach { other -> DropdownMenuItem({ Text(other.name) }, { target = other.id; destinations = false }, modifier = Modifier.testTag("lab-destination-${other.id}")) }
                }
            }
            LabAction({ move(selected, target, name) }, selected.isNotEmpty() && (target != null || name.isNotBlank()), Modifier.testTag("lab-move-selected")) { Text("Move selected · ${selected.size}") }
            if (group != null) LabAction({ move(group.photoIds.toSet(), target, name) }, target != null, Modifier.testTag("lab-merge-group")) { Text("Merge whole group") }
        }
    }, confirmButton = { LabAction(close) { Text("Done") } })
}

@Composable
private fun LabPhotoPicker(controller: DemoController, photos: List<LibraryItem>, initial: Set<String>, maximum: Int,
    done: (Set<String>) -> Unit, close: () -> Unit) {
    var selected by remember { mutableStateOf(initial.intersect(photos.map { it.id }.toSet())) }
    var query by remember { mutableStateOf("") }
    val visible = photos.filter { it.title.contains(query.trim(), ignoreCase = true) }
    AlertDialog(onDismissRequest = close, title = { Text("Choose photos · ${selected.size} / $maximum") }, text = {
        Column(labResourceTags()) {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Filter filenames") }, modifier = Modifier.testTag("lab-pick-filter"))
            if (maximum > 1) LabAction({ selected = if (selected.isEmpty()) visible.take(maximum).map { it.id }.toSet() else emptySet() }, modifier = Modifier.testTag("lab-pick-clear")) { Text(if (selected.isEmpty()) "Select all" else "Clear") }
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.heightIn(max = 400.dp).testTag("lab-picker"), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(visible, key = { it.id }) { photo ->
                    Box(Modifier.testTag("lab-pick-${photo.title}").aspectRatio(1f).border(if (photo.id in selected) 3.dp else 0.dp, Color(0xff2867c7)).clickable {
                        selected = if (photo.id in selected) selected - photo.id else if (maximum == 1) setOf(photo.id) else if (selected.size < maximum) selected + photo.id else selected
                    }) { LabThumb(controller, photo, Modifier.fillMaxSize()); if (photo.id in selected) Text("✓", Modifier.align(Alignment.TopEnd).background(Color(0xff2867c7)).padding(3.dp), color = Color.White) }
                }
            }
        }
    }, confirmButton = { LabAction({ done(selected) }, modifier = labResourceTags().testTag("lab-pick-done")) { Text("Done") } }, dismissButton = { LabAction(close) { Text("Cancel") } })
}

@Composable
private fun LabThumb(controller: DemoController, photo: LibraryItem, modifier: Modifier) = SystemPhotoImage(controller, photo.id, photo.revision, photo.title, 240, modifier, ContentScale.Crop)

@Composable
private fun CutoffField(value: String, change: (String) -> Unit, label: String, tag: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { change(it.take(8)) }, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        isError = cutoff(value) == null, supportingText = if (cutoff(value) == null) ({ Text("Enter 0 to 1") }) else null,
        modifier = modifier.testTag(tag).padding(vertical = 3.dp))
}
private fun cutoff(value: String): Double? = value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
private fun score(value: Double) = ((value * 1000).toInt() / 1000.0).toString()

@Composable
private fun LabAction(onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.then(labResourceTags()), content = content)
}
