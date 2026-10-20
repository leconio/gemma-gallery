package com.spawnlau.gemmapocket.shared

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal expect fun decodePhoto(bytes: ByteArray): ImageBitmap

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GemmaApp(host: PlatformServices, autoSmoke: Boolean = false, modifier: Modifier = Modifier) {
    val controller = remember(host) { DemoController(host, autoSmoke) }
    val labController = remember(host) { LabController(host, controller::embedLabText, backend = { controller.state.value.backend }) }
    val labState by labController.state.collectAsState()
    var labOpen by remember { mutableStateOf(false) }
    val state by controller.state.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var albumMenu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<LibraryItem?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var targetAlbum by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    LaunchedEffect(controller) { controller.start() }
    LaunchedEffect(labController) { labController.load() }
    LaunchedEffect(state.libraryLoaded, state.labPhotos) { if (state.libraryLoaded) labController.setPhotos(state.labPhotos) }
    LaunchedEffect(state.access, state.results) { if (state.access == "denied" || (preview != null && state.results.none { it.item.id == preview?.id })) preview = null }
    DisposableEffect(controller) { onDispose { labController.close(); controller.close() } }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff2a3036), secondaryContainer = Color(0xffedf0f2),
        onSecondaryContainer = Color(0xff2a3036), background = Color.White, surface = Color.White)) {
        Surface(modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Gemma Gallery", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { labOpen = !labOpen }, modifier = Modifier.testTag("lab-open")) { Text(if (labOpen) "Photos" else "Lab", fontSize = 12.sp) }
                    TextButton(onClick = { settings = true }, enabled = !labState.busy, modifier = Modifier.testTag("settings")) { Text("Settings", fontSize = 12.sp) }
                }
                if (state.access == "denied") {
                    Spacer(Modifier.weight(1f)); Text("Search your system photos", fontSize = 21.sp)
                    Spacer(Modifier.height(12.dp)); Text("Choose which photos this app can access. Search and indexing run on this device.", color = Color.DarkGray)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = controller::connectPhotos, enabled = !state.acting, modifier = Modifier.testTag("connect-photos")) { Text("Connect Photos") }
                    if (state.detail.isNotBlank()) Text(state.detail, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                } else if (labOpen) {
                    LabScreen(labController, controller, state)
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(state.query, controller::setQuery, enabled = !state.acting, modifier = Modifier.testTag("search-query").weight(1f), singleLine = true,
                            placeholder = { Text("Search photos...", fontSize = 14.sp) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); controller.search() }))
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { focus.clearFocus(); controller.search() }, enabled = state.ready && !state.busy && !state.searching,
                            modifier = Modifier.testTag("search-go"), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Search", fontSize = 12.sp) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            TextButton(onClick = { albumMenu = true }, modifier = Modifier.testTag("album-filter")) {
                                Text(state.albums.firstOrNull { it.id == state.albumId }?.title ?: "All photos", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                                DropdownMenuItem(text = { Text("All photos") }, onClick = { controller.selectAlbum(""); albumMenu = false })
                                state.albums.forEach { album -> DropdownMenuItem(text = { Text(album.title) }, onClick = { controller.selectAlbum(album.id); albumMenu = false }) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("${state.indexedCount} / ${state.imageCount} indexed", modifier = Modifier.testTag("index-count"), fontSize = 12.sp, color = Color.DarkGray)
                        if (state.ready && !state.acting) TextButton(onClick = { if (state.scanning) controller.pauseScan() else controller.scan() }, modifier = Modifier.testTag("scan-toggle")) {
                            Text(if (state.scanning && state.scanPaused) "Pausing..." else if (state.scanning) "Pause" else "Resume", fontSize = 12.sp)
                        }
                    }
                    Text(when {
                        state.acting -> "Waiting for System Photos..."
                        state.busy -> state.status
                        state.searching -> "Searching..."
                        state.scanning -> "Scanning system photos"
                        state.scanPaused -> "Scan paused"
                        state.ready -> "Ready"
                        else -> state.status
                    }, modifier = Modifier.testTag("app-status"), fontSize = 12.sp, color = Color.DarkGray)
                    if (state.progress != null) LinearProgressIndicator(progress = { state.progress!! }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    if (state.downloading) TextButton(onClick = controller::pauseDownload) { Text("Pause download") }
                    if (state.paused) TextButton(onClick = controller::resumeModel) { Text("Resume download") }
                    if (state.access == "limited") TextButton(onClick = controller::connectPhotos, modifier = Modifier.testTag("limited-access")) { Text("Selected photos only · Manage access", fontSize = 12.sp) }
                    if (state.detail.isNotBlank()) Text(state.detail, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                    if (state.searched) Text("${state.results.size} matches · ${state.queryMs.roundToInt()} ms", modifier = Modifier.testTag("search-timing"), fontSize = 12.sp, color = Color.DarkGray)
                    Spacer(Modifier.height(8.dp))
                    if (state.searched && !state.searching && state.results.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No matching photos", modifier = Modifier.testTag("search-empty"), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            Text(if (state.indexedCount < state.imageCount) "Try a different description or let scanning finish." else "Try a different description.",
                                fontSize = 12.sp, color = Color.DarkGray)
                        }
                    }
                    LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(state.results, key = { it.item.id }) { result ->
                            val selected = result.item.id in state.selected
                            Box(Modifier.testTag("photo-${result.item.title}").aspectRatio(1f).border(if (selected) 3.dp else 0.dp, Color(0xff2867c7))
                                .combinedClickable(onClick = { if (state.selected.isEmpty()) preview = result.item else controller.toggleSelection(result.item.id) }, onLongClick = { controller.toggleSelection(result.item.id) })) {
                                SystemPhotoImage(controller, result.item.id, result.item.revision, result.item.title, 320, Modifier.fillMaxSize(), ContentScale.Crop)
                                if (selected) Text("✓", Modifier.align(Alignment.TopEnd).background(Color(0xff2867c7)).padding(5.dp), color = Color.White)
                            }
                        }
                    }
                    if (state.selected.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = controller::clearSelection, enabled = !state.acting) { Text("${state.selected.size} · Clear") }
                        TextButton(onClick = controller::shareSelected, enabled = !state.acting, modifier = Modifier.testTag("share-selected")) { Text("Share") }
                        TextButton(onClick = { targetAlbum = ""; newName = ""; organizing = true }, enabled = !state.acting && state.canOrganize, modifier = Modifier.testTag("organize-selected")) { Text("Organize") }
                        TextButton(onClick = { deleting = true }, enabled = !state.acting && state.canDelete, modifier = Modifier.testTag("delete-selected")) { Text("Delete") }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Settings") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("System Photos · ${state.access} access\n${host.platformLabel}\nKotlin Multiplatform · 0.6.0", fontSize = 13.sp)
                TextButton(onClick = { settings = false; controller.connectPhotos() }) { Text("Manage photo access") }
                TextButton(onClick = { settings = false; host.photos.openSettings() }) { Text("Open system settings") }
                if (state.access == "limited" && !state.canOrganize) Text("Full Photos access is required to organize albums.", fontSize = 12.sp)
                Text("Search engine", fontWeight = FontWeight.SemiBold)
                Text("Minimum similarity: ${SearchRelevance.minimumSimilarity}\nLower scores are hidden. Similarity is not a probability.", fontSize = 12.sp)
                Row {
                    listOf("CPU", "GPU").forEach { backend ->
                        TextButton(onClick = { controller.switchBackend(backend) }, enabled = !state.busy && !state.acting) {
                            Text(if (state.backend == backend) "$backend ✓" else backend)
                        }
                    }
                }
                Text("Last photo\nRead + prepare: ${state.lastReadMs.roundToInt()} ms\nInference + queue: ${state.lastEmbeddingMs.roundToInt()} ms\nSQLite write: ${state.lastWriteMs.roundToInt()} ms\nSearch: ${state.queryMs.roundToInt()} ms", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text("The 388 MB model downloads once. Photo IDs and AI vectors stay on this device. Old imported copies are retained as a migration backup.", fontSize = 12.sp)
                if (!state.ready && !state.busy) TextButton(onClick = { settings = false; controller.importModel() }) { Text("Import model file") }
            }
        }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Close") } })
        preview?.let { photo -> AlertDialog(onDismissRequest = { preview = null }, title = { Text(photo.title, maxLines = 2, fontSize = 16.sp) }, text = {
            SystemPhotoImage(controller, photo.id, photo.revision, photo.title, 1280, Modifier.fillMaxWidth().height(360.dp), ContentScale.Fit)
        }, confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } }) }
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(state.deleteLabel) }, text = { Text("Continue with ${state.selected.size} selected system photos? The system will ask for confirmation.") },
            confirmButton = { TextButton(onClick = { deleting = false; controller.deleteSelected() }, modifier = Modifier.testTag("confirm-delete")) { Text("Continue") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
        if (organizing) AlertDialog(onDismissRequest = { organizing = false }, title = { Text(state.organizeLabel) }, text = {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(newName, { newName = it; targetAlbum = "" }, label = { Text("New album or folder name") }, singleLine = true, modifier = Modifier.testTag("album-name"))
                state.albums.forEach { album -> TextButton(onClick = { targetAlbum = album.id; newName = "" }, modifier = Modifier.testTag("target-${album.title}")) { Text((if (targetAlbum == album.id) "✓ " else "") + album.title) } }
            }
        }, confirmButton = { TextButton(onClick = { organizing = false; controller.organizeSelected(targetAlbum.ifEmpty { null }, newName.ifBlank { null }) }, enabled = targetAlbum.isNotEmpty() || newName.isNotBlank(), modifier = Modifier.testTag("apply-album")) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { organizing = false }) { Text("Cancel") } })
    }
}

@Composable
internal fun SystemPhotoImage(controller: DemoController, id: String, revision: String, title: String, pixels: Int, modifier: Modifier, scale: ContentScale) {
    val image by produceState<ImageBitmap?>(null, id, revision, pixels) {
        val bytes = controller.imageBytes(id, pixels)
        value = bytes?.let { withContext(Dispatchers.Default) { runCatching { decodePhoto(it) }.getOrNull() } }
    }
    image?.let { Image(it, title, modifier, contentScale = scale) }
        ?: Box(modifier.background(Color(0xfff2f3f4)), contentAlignment = Alignment.Center) { Text("Photo", fontSize = 12.sp, color = Color.Gray) }
}
