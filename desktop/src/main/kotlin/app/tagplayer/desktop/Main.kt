package app.tagplayer.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.tagplayer.core.IncludeMode
import app.tagplayer.core.displayAlbum
import app.tagplayer.core.displayArtist
import app.tagplayer.core.displayTitle
import app.tagplayer.core.listDetail
import app.tagplayer.core.formatClock
import app.tagplayer.core.tagCounts

private val AppColors = darkColorScheme(
    primary = Color(0xFFE0A15A),
    onPrimary = Color(0xFF2A1C0C),
    background = Color(0xFF14120F),
    surface = Color(0xFF221F1B),
    onBackground = Color(0xFFF4EFE8),
    onSurface = Color(0xFFF4EFE8),
    surfaceVariant = Color(0xFF3A342C),
    onSurfaceVariant = Color(0xFFD9CBB8),
    error = Color(0xFFE07A5F),
)

fun main(args: Array<String>) {
    if ("--self-test" in args) {
        kotlin.system.exitProcess(runSelfTest())
    }
    application {
        val model = remember { AppModel() }
        Window(
            onCloseRequest = {
                model.dispose()
                exitApplication()
            },
            title = "随播",
            state = rememberWindowState(width = 1080.dp, height = 760.dp),
        ) {
            MaterialTheme(colorScheme = AppColors) {
                CompositionLocalProvider(LocalContentColor provides AppColors.onBackground) {
                    App(model)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun App(model: AppModel) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var coverDialogId by remember { mutableStateOf<String?>(null) }
    var infoDialogId by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("") }
    var showPresetDialog by remember { mutableStateOf(false) }
    var foldersOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var batchTag by remember { mutableStateOf("") }
    val filtered = model.filtered
    val counts = tagCounts(model.tagFile)
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = model::chooseFolder) { Text("选择文件夹") }
            Button(onClick = model::scanLocalMusic, enabled = !model.scanning) { Text("扫描本机") }
            TextButton(onClick = { foldersOpen = true }) { Text("屏蔽文件夹") }
            TextButton(onClick = { settingsOpen = true }) { Text("设置") }
            Button(onClick = model::rescan, enabled = model.libraryRoot != null && !model.scanning) { Text("重新扫描") }
            Text(
                model.libraryRoot?.path ?: "选择一个音乐文件夹。标签会写在里面的 tags.json，用 Syncthing 和手机同步。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "筛出 ${filtered.size} / ${model.tracks.size} 首。点标签：加入包含，再点改为排除，再点取消。${model.status}",
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = model.includeMode == IncludeMode.All,
                onClick = { model.includeMode = IncludeMode.All },
                label = { Text("同时包含") },
            )
            FilterChip(
                selected = model.includeMode == IncludeMode.Any,
                onClick = { model.includeMode = IncludeMode.Any },
                label = { Text("包含任一") },
            )
            Button(onClick = model::playFiltered, enabled = filtered.isNotEmpty()) { Text("随机播放") }
            TextButton(onClick = { model.selecting = !model.selecting }) {
                Text(if (model.selecting) "完成多选" else "多选打标签")
            }
        }
        if (counts.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                counts.forEach { (tag, count) ->
                    val excluded = tag in model.exclude
                    val included = tag in model.include
                    FilterChip(
                        selected = included || excluded,
                        onClick = { model.cycleTag(tag) },
                        label = { Text("$tag $count") },
                        colors = if (excluded) {
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF6A3A32),
                                selectedLabelColor = Color(0xFFFFE8E2),
                            )
                        } else {
                            FilterChipDefaults.filterChipColors()
                        },
                    )
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagEntry("加入包含", model::addInclude, Modifier.weight(1f))
            TagEntry("加入排除", model::addExclude, Modifier.weight(1f))
            OutlinedTextField(
                value = model.query,
                onValueChange = { model.query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("搜索") },
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            model.tagFile.presets.forEach { preset ->
                FilterChip(
                    selected = preset.include == model.include && preset.exclude == model.exclude,
                    onClick = { model.applyPreset(preset.id) },
                    label = { Text(preset.name) },
                )
                TextButton(onClick = { model.deletePreset(preset.id) }) { Text("删除") }
            }
            TextButton(onClick = { showPresetDialog = true }, enabled = model.libraryRoot != null) { Text("保存方案") }
        }
        if (model.selecting) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = batchTag,
                    onValueChange = { batchTag = it },
                    singleLine = true,
                    label = { Text("给选中的 ${model.selected.size} 首") },
                )
                Button(onClick = { model.addTagToSelected(batchTag); batchTag = "" }, enabled = model.selected.isNotEmpty()) {
                    Text("加上")
                }
                Button(onClick = { model.removeTagFromSelected(batchTag); batchTag = "" }, enabled = model.selected.isNotEmpty()) {
                    Text("去掉")
                }
            }
        }
        if (model.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Row(Modifier.weight(1f).padding(top = 8.dp).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                items(filtered, key = { it.contentId }) { track ->
                    val record = model.tagFile.songs[track.contentId]
                    val tags = model.tagsOf(track.contentId)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (model.selecting) model.toggleSelected(track.contentId) else editingId = track.contentId
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (model.selecting) {
                            Checkbox(checked = track.contentId in model.selected, onCheckedChange = { model.toggleSelected(track.contentId) })
                        }
                        Column(Modifier.weight(1f)) {
                            Text(displayTitle(track, record), maxLines = 1)
                            val detail = listDetail(
                                displayArtist(track, record),
                                displayAlbum(track, record),
                                emptyList(),
                                model.showArtist,
                                model.showAlbum,
                            )
                            if (detail.isNotBlank()) {
                                Text(
                                    detail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                        }
                        TextButton(onClick = { infoDialogId = track.contentId }) { Text("识别") }
                        TextButton(onClick = { coverDialogId = track.contentId }) { Text("封面") }
                        TextButton(onClick = { model.playTrack(track.contentId) }) { Text("播放") }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.fillMaxHeight())
        }
        PlayerBar(model)
    }

    val liveCoverId = model.coverTrackId
    LaunchedEffect(liveCoverId) {
        if (coverDialogId != null && liveCoverId != null && coverDialogId != liveCoverId) coverDialogId = liveCoverId
    }
    val liveInfoId = model.infoTrackId
    LaunchedEffect(liveInfoId) {
        if (infoDialogId != null && liveInfoId != null && infoDialogId != liveInfoId) infoDialogId = liveInfoId
    }
    editingId?.let { id ->
        val track = model.tracks.find { it.contentId == id }
        if (track != null) {
            TagDialog(
                title = displayTitle(track, model.tagFile.songs[id]),
                tags = model.tagsOf(id),
                onToggle = { model.toggleTag(id, it) },
                onAdd = { model.addTag(id, it) },
                onDismiss = { editingId = null },
            )
        }
    }
    coverDialogId?.let { id ->
        val track = model.tracks.find { it.contentId == id }
        if (track != null) {
            CoverDialog(displayTitle(track, model.tagFile.songs[id]), model, id) { coverDialogId = null }
        }
    }
    infoDialogId?.let { id ->
        val track = model.tracks.find { it.contentId == id }
        if (track != null) {
            InfoDialog(displayTitle(track, model.tagFile.songs[id]), model, id) { infoDialogId = null }
        }
    }
    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("个性化") },
            text = {
                Column {
                    Text("列表第二行", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = model.showArtist, onCheckedChange = model::changeShowArtist)
                        Text("显示歌手")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = model.showAlbum, onCheckedChange = model::changeShowAlbum)
                        Text("显示专辑")
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { settingsOpen = false }) { Text("关闭") } },
        )
    }
    if (foldersOpen) {
        val browse = model.folderBrowse()
        AlertDialog(
            onDismissRequest = { foldersOpen = false },
            title = { Text(browse.title) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "点文件夹进入下一层。屏蔽只在这台电脑上，不会写进 tags.json。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (browse.canGoUp) TextButton(onClick = model::shieldUp) { Text("上一级") }
                    if (browse.entries.isEmpty()) Text("这一层没有文件夹")
                    browse.entries.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { if (entry.canEnter) model.enterShieldFolder(entry.path) },
                                enabled = entry.canEnter,
                                modifier = Modifier.weight(1f),
                            ) { Text("${entry.name}  ${entry.songCount} 首") }
                            TextButton(onClick = { model.toggleShield(entry.path) }) {
                                Text(if (entry.blocked) "取消" else "屏蔽")
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { foldersOpen = false }) { Text("关闭") } },
        )
    }
    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            title = { Text("保存听歌方案") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    singleLine = true,
                    label = { Text("方案名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    model.savePreset(presetName)
                    presetName = ""
                    showPresetDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showPresetDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TagEntry(label: String, onSubmit: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        trailingIcon = {
            TextButton(onClick = {
                onSubmit(text)
                text = ""
            }) { Text("添加") }
        },
    )
}

@Composable
private fun PlayerBar(model: AppModel) {
    val current = model.tracks.find { it.contentId == model.currentId }
    val record = current?.let { model.tagFile.songs[it.contentId] }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val fraction = if (model.durationMs > 0) model.positionMs.toFloat() / model.durationMs else 0f
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(current?.let { displayTitle(it, record) } ?: "还没有在播", maxLines = 1)
        val playingDetail = current?.let {
            listDetail(displayArtist(it, record), displayAlbum(it, record), emptyList(), model.showArtist, model.showAlbum)
        }.orEmpty()
        val round = if (model.queue.ids.isNotEmpty()) "这一轮 ${model.queue.index + 1} / ${model.queue.ids.size}" else ""
        val playingLine = listOf(playingDetail, round).filter { it.isNotBlank() }.joinToString("    ")
        if (playingLine.isNotBlank()) {
            Text(playingLine, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (model.durationMs > 0) {
            Slider(
                value = if (dragging) dragValue else fraction.coerceIn(0f, 1f),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    model.seekTo((dragValue * model.durationMs).toLong())
                    dragging = false
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = model::userPrevious, enabled = model.queue.ids.isNotEmpty()) { Text("上一首") }
            Button(onClick = model::togglePause) { Text(if (model.isPlaying) "暂停" else "播放") }
            TextButton(onClick = model::userNext, enabled = model.queue.ids.isNotEmpty()) { Text("下一首") }
            Text("${formatClock(model.positionMs)} / ${formatClock(model.durationMs)}")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagDialog(
    title: String,
    tags: Set<String>,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tags.isEmpty()) Text("还没有标签")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.sorted().forEach { tag ->
                        FilterChip(selected = true, onClick = { onToggle(tag) }, label = { Text(tag) })
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("新标签") },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAdd(draft)
                draft = ""
            }) { Text("加上") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun CoverDialog(title: String, model: AppModel, trackId: String, onDismiss: () -> Unit) {
    LaunchedEffect(trackId) { model.prepareCover(trackId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CoverPreview(model.coverProposal ?: model.coverBytes)
                if (model.coverNote.isNotBlank()) {
                    Text(model.coverNote, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = model::chooseCoverFile, enabled = !model.coverBusy) { Text("选择图片") }
                    TextButton(onClick = model::findCoverOnline, enabled = !model.coverBusy) { Text("查找封面") }
                }
                model.coverChoices.forEachIndexed { index, choice ->
                    TextButton(onClick = { model.pickCoverChoice(index) }, enabled = !model.coverBusy) {
                        Text(listOf(choice.title, choice.artist, choice.album).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                }
                if (model.coverProposal != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = model::confirmCover, enabled = !model.coverBusy) { Text("替换封面") }
                        TextButton(onClick = model::dismissCoverProposal) { Text("先不写") }
                    }
                }
                if (model.coverCanUndo) {
                    TextButton(onClick = model::undoCover, enabled = !model.coverBusy) { Text("撤销") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun InfoDialog(title: String, model: AppModel, trackId: String, onDismiss: () -> Unit) {
    LaunchedEffect(trackId) { model.lookupInfo(trackId) }
    val picked = model.infoMatches.getOrNull(model.infoPicked)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.infoNote.isNotBlank()) {
                    Text(model.infoNote, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                model.infoMatches.forEachIndexed { index, match ->
                    TextButton(onClick = { model.pickInfo(index) }, enabled = !model.infoBusy) {
                        Text(listOf(match.title, match.artist, match.album, match.genre).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                }
                if (picked != null) {
                    Text("写入这些项：")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = model.infoUseTitle, onCheckedChange = { model.infoUseTitle = it })
                        Text("歌名 ${picked.title}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = model.infoUseArtist, onCheckedChange = { model.infoUseArtist = it })
                        Text("歌手 ${picked.artist}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = model.infoUseAlbum, onCheckedChange = { model.infoUseAlbum = it })
                        Text("专辑 ${picked.album}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = model.infoUseGenre, onCheckedChange = { model.infoUseGenre = it })
                        Text("流派 ${picked.genre}")
                    }
                    TextButton(onClick = model::confirmInfo, enabled = !model.infoBusy) { Text("写入文件") }
                }
                if (model.infoCanUndo) {
                    TextButton(onClick = model::undoInfo, enabled = !model.infoBusy) { Text("撤销") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun CoverPreview(bytes: ByteArray?) {
    if (bytes == null) {
        Text("这首歌还没有封面", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val bitmap = remember(bytes) {
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }
    if (bitmap == null) {
        Text("这张封面打不开")
    } else {
        Image(bitmap, contentDescription = "封面", modifier = Modifier.size(120.dp))
    }
}
