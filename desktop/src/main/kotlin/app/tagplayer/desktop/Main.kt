package app.tagplayer.desktop

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.tagplayer.core.IncludeMode
import app.tagplayer.core.displayArtist
import app.tagplayer.core.displayTitle
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
                App(model)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun App(model: AppModel) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("") }
    var showPresetDialog by remember { mutableStateOf(false) }
    var batchTag by remember { mutableStateOf("") }
    val filtered = model.filtered
    val counts = tagCounts(model.tagFile)
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = model::chooseFolder) { Text("选择文件夹") }
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
                            Text(displayTitle(track, record))
                            val artist = displayArtist(track, record)
                            val detail = listOf(artist, tags.joinToString(" · ")).filter { it.isNotBlank() }.joinToString("  ")
                            if (detail.isNotBlank()) {
                                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { model.playTrack(track.contentId) }) { Text("播放") }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.fillMaxHeight())
        }
        PlayerBar(model)
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
        Text(current?.let { displayTitle(it, record) } ?: "还没有在播")
        Text(
            buildString {
                append(current?.let { displayArtist(it, record) }.orEmpty())
                if (model.queue.ids.isNotEmpty()) {
                    append("    这一轮 ${model.queue.index + 1} / ${model.queue.ids.size}")
                }
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
