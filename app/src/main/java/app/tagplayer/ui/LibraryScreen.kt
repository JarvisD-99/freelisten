package app.tagplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tagplayer.core.IncludeMode
import app.tagplayer.core.formatClock

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onPickFolder: () -> Unit,
    onPreparePlayback: () -> Unit,
    onAllFilesAccess: () -> Unit,
) {
    val state by viewModel.ui.collectAsState()
    var editingId by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("") }
    var showPresetDialog by remember { mutableStateOf(false) }
    var batchTag by remember { mutableStateOf("") }
    var includeDraft by remember { mutableStateOf("") }
    var excludeDraft by remember { mutableStateOf("") }
    var filtersOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPickFolder) { Text("文件夹") }
            Button(
                onClick = {
                    onPreparePlayback()
                    viewModel.playFiltered()
                },
                enabled = state.filteredCount > 0,
            ) { Text("随机播放") }
            TextButton(onClick = { filtersOpen = !filtersOpen }) {
                Text(if (filtersOpen) "收起" else "筛选")
            }
        }
        Text(
            "${state.filteredCount} / ${state.totalCount} 首  ${state.folderLabel}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            state.status,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (filtersOpen) {
            FilterPanel(
                state = state,
                viewModel = viewModel,
                includeDraft = includeDraft,
                excludeDraft = excludeDraft,
                batchTag = batchTag,
                onIncludeDraft = { includeDraft = it },
                onExcludeDraft = { excludeDraft = it },
                onBatchTag = { batchTag = it },
                onSavePreset = { showPresetDialog = true },
                onAllFilesAccess = onAllFilesAccess,
            )
        }
        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(state.rows, key = { it.contentId }) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (state.selecting) viewModel.toggleSelected(row.contentId) else editingId = row.contentId
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.selecting) {
                        Checkbox(checked = row.contentId in state.selected, onCheckedChange = { viewModel.toggleSelected(row.contentId) })
                    }
                    Column(Modifier.weight(1f)) {
                        Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val detail = listOf(row.artist, row.tags.joinToString(" · ")).filter { it.isNotBlank() }.joinToString("  ")
                        if (detail.isNotBlank()) {
                            Text(
                                detail,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(onClick = {
                        onPreparePlayback()
                        viewModel.playTrack(row.contentId)
                    }) { Text("播放") }
                }
            }
        }
        PlayerBar(state, viewModel, onPreparePlayback)
    }

    editingId?.let { id ->
        val row = state.libraryRows.find { it.contentId == id } ?: state.rows.find { it.contentId == id }
        TagDialog(
            title = row?.title ?: "标签",
            tags = row?.tags ?: emptyList(),
            onToggle = { viewModel.toggleTag(id, it) },
            onAdd = { viewModel.addTag(id, it) },
            onDismiss = { editingId = null },
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
                    viewModel.savePreset(presetName)
                    presetName = ""
                    showPresetDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showPresetDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PlayerBar(state: LibraryUiState, viewModel: LibraryViewModel, onPreparePlayback: () -> Unit) {
    val player = state.player
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val fraction = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            buildString {
                append(state.currentTitle.ifBlank { "还没有在播" })
                if (state.currentArtist.isNotBlank()) append("  ·  ${state.currentArtist}")
                if (player.queueIds.isNotEmpty()) append("    ${player.index + 1}/${player.queueIds.size}")
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        player.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (player.durationMs > 0) {
            Slider(
                value = if (dragging) dragValue else fraction.coerceIn(0f, 1f),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    viewModel.seek((dragValue * player.durationMs).toLong())
                    dragging = false
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = viewModel::previous, enabled = player.queueIds.isNotEmpty()) { Text("上一首") }
            Button(onClick = {
                onPreparePlayback()
                if (player.queueIds.isEmpty()) viewModel.playFiltered() else viewModel.togglePause()
            }) { Text(if (player.playing) "暂停" else "播放") }
            TextButton(onClick = viewModel::next, enabled = player.queueIds.isNotEmpty()) { Text("下一首") }
            Text("${formatClock(player.positionMs)} / ${formatClock(player.durationMs)}")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    includeDraft: String,
    excludeDraft: String,
    batchTag: String,
    onIncludeDraft: (String) -> Unit,
    onExcludeDraft: (String) -> Unit,
    onBatchTag: (String) -> Unit,
    onSavePreset: () -> Unit,
    onAllFilesAccess: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            "点标签：加入包含，再点改为排除，再点取消。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.includeMode == IncludeMode.All,
                onClick = { viewModel.setIncludeMode(IncludeMode.All) },
                label = { Text("同时包含") },
            )
            FilterChip(
                selected = state.includeMode == IncludeMode.Any,
                onClick = { viewModel.setIncludeMode(IncludeMode.Any) },
                label = { Text("包含任一") },
            )
            if (state.hasFolder) {
                TextButton(onClick = viewModel::rescan, enabled = !state.scanning) { Text("重新扫描") }
            }
        }
        if (state.tagCounts.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.tagCounts.forEach { (tag, count) ->
                    val excluded = tag in state.exclude
                    FilterChip(
                        selected = tag in state.include || excluded,
                        onClick = { viewModel.cycleTag(tag) },
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
        OutlinedTextField(
            value = includeDraft,
            onValueChange = onIncludeDraft,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("加入包含") },
            trailingIcon = {
                TextButton(onClick = {
                    viewModel.addInclude(includeDraft)
                    onIncludeDraft("")
                }) { Text("添加") }
            },
        )
        OutlinedTextField(
            value = excludeDraft,
            onValueChange = onExcludeDraft,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("加入排除") },
            trailingIcon = {
                TextButton(onClick = {
                    viewModel.addExclude(excludeDraft)
                    onExcludeDraft("")
                }) { Text("添加") }
            },
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索") },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.presets.forEach { preset ->
                FilterChip(
                    selected = preset.include == state.include && preset.exclude == state.exclude,
                    onClick = { viewModel.applyPreset(preset.id) },
                    label = { Text(preset.name) },
                )
                TextButton(onClick = { viewModel.deletePreset(preset.id) }) { Text("删") }
            }
            TextButton(onClick = onSavePreset, enabled = state.hasFolder) { Text("保存方案") }
            TextButton(onClick = viewModel::toggleSelecting) { Text(if (state.selecting) "完成多选" else "多选打标签") }
            TextButton(onClick = onAllFilesAccess) { Text("直接读取文件夹") }
        }
        if (state.selecting) {
            OutlinedTextField(
                value = batchTag,
                onValueChange = onBatchTag,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("给选中的 ${state.selected.size} 首") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.addTagToSelected(batchTag); onBatchTag("") }, enabled = state.selected.isNotEmpty()) {
                    Text("加上")
                }
                Button(onClick = { viewModel.removeTagFromSelected(batchTag); onBatchTag("") }, enabled = state.selected.isNotEmpty()) {
                    Text("去掉")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagDialog(
    title: String,
    tags: List<String>,
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
                    tags.forEach { tag ->
                        FilterChip(selected = true, onClick = { onToggle(tag) }, label = { Text(tag) })
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("新标签") },
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
