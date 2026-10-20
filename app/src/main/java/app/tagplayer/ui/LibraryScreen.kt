package app.tagplayer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import app.tagplayer.core.IncludeMode
import app.tagplayer.core.listDetail
import app.tagplayer.core.readEmbeddedCover
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppPage { Library, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onPickFolder: () -> Unit,
    onPreparePlayback: () -> Unit,
    onAllFilesAccess: () -> Unit,
    onPickCover: () -> Unit,
) {
    val state by viewModel.ui.collectAsState()
    val cover by viewModel.cover.collectAsState()
    val info by viewModel.info.collectAsState()
    val fact by viewModel.fact.collectAsState()
    var editingId by remember { mutableStateOf<String?>(null) }
    var coverId by remember { mutableStateOf<String?>(null) }
    var factId by remember { mutableStateOf<String?>(null) }
    var infoId by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("") }
    var showPresetDialog by remember { mutableStateOf(false) }
    var batchTag by remember { mutableStateOf("") }
    var includeDraft by remember { mutableStateOf("") }
    var excludeDraft by remember { mutableStateOf("") }
    var filtersOpen by remember { mutableStateOf(false) }
    var foldersOpen by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(AppPage.Library) }
    var yearFromDraft by remember { mutableStateOf("") }
    var yearToDraft by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (page == AppPage.Settings) {
            SettingsPage(
                state = state,
                viewModel = viewModel,
                onBack = { page = AppPage.Library },
                onAllFilesAccess = onAllFilesAccess,
                onShield = { foldersOpen = true },
                modifier = Modifier.weight(1f),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${state.filteredCount} / ${state.totalCount} 首",
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onPreparePlayback()
                        viewModel.playFiltered()
                    },
                    enabled = state.filteredCount > 0,
                ) { Text("随机播放") }
                TextButton(onClick = { page = AppPage.Settings }) { Text("设置") }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                label = { Text("搜索") },
            )
            TextButton(onClick = { filtersOpen = true }) { Text("筛选") }
            if (state.scanning) {
                Text(state.status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 4.dp))
            }
            val listState = rememberLazyListState()
            var fastIndex by remember { mutableIntStateOf(-1) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        items(state.rows, key = { it.contentId }) { row ->
                            var menu by remember { mutableStateOf(false) }
                            val playing = row.contentId == state.player.currentId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (playing) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                    .clickable {
                                        if (state.selecting) viewModel.toggleSelected(row.contentId) else {
                                            onPreparePlayback()
                                            viewModel.playTrack(row.contentId)
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.selecting) {
                                    Checkbox(checked = row.contentId in state.selected, onCheckedChange = { viewModel.toggleSelected(row.contentId) })
                                }
                                CoverThumb(row.path)
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(row.title, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val detail = listDetail(row.artist, row.album, emptyList(), state.showArtist, state.showAlbum)
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
                                Box {
                                    TextButton(onClick = { menu = true }) { Text("⋯") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("歌曲信息") }, onClick = { menu = false; factId = row.contentId })
                                        DropdownMenuItem(text = { Text("标签") }, onClick = { menu = false; editingId = row.contentId })
                                        DropdownMenuItem(text = { Text("识别") }, onClick = { menu = false; infoId = row.contentId })
                                        DropdownMenuItem(text = { Text("封面") }, onClick = { menu = false; coverId = row.contentId })
                                    }
                                }
                            }
                        }
                    }
                    if (listState.canScrollBackward || listState.canScrollForward) {
                        FastScrollRail(
                            listState = listState,
                            count = state.rows.size,
                            onDrag = { fastIndex = it },
                        )
                    }
                }
                LocatePlayingButton(listState, state.rows, state.player.currentId)
                if (fastIndex in state.rows.indices) {
                    Text(
                        state.rows[fastIndex].title,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 36.dp)
                            .widthIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        PlayerBar(state, viewModel, onPreparePlayback)
    }

    LaunchedEffect(cover.trackId) {
        val live = cover.trackId
        if (coverId != null && live != null && coverId != live) coverId = live
    }
    LaunchedEffect(info.trackId) {
        val live = info.trackId
        if (infoId != null && live != null && infoId != live) infoId = live
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
    coverId?.let { id ->
        LaunchedEffect(id) { viewModel.prepareCover(id) }
        val row = state.libraryRows.find { it.contentId == id } ?: state.rows.find { it.contentId == id }
        CoverDialog(
            title = row?.title ?: "封面",
            cover = cover,
            onPickCover = onPickCover,
            onFindCover = viewModel::findCoverOnline,
            onPickChoice = viewModel::pickCoverChoice,
            onConfirmCover = viewModel::confirmCover,
            onDismissCover = viewModel::dismissCoverProposal,
            onUndoCover = viewModel::undoCover,
            onDismiss = { coverId = null },
        )
    }
    factId?.let { id ->
        LaunchedEffect(id) { viewModel.loadSongFact(id) }
        SongFactDialog(fact, onDismiss = { factId = null })
    }
    infoId?.let { id ->
        LaunchedEffect(id) { viewModel.lookupInfo(id) }
        val row = state.libraryRows.find { it.contentId == id } ?: state.rows.find { it.contentId == id }
        InfoDialog(
            title = row?.title ?: "识别",
            info = info,
            onPick = viewModel::pickMatch,
            onTitle = { viewModel.setInfoField(title = it) },
            onArtist = { viewModel.setInfoField(artist = it) },
            onAlbum = { viewModel.setInfoField(album = it) },
            onGenre = { viewModel.setInfoField(genre = it) },
            onConfirm = viewModel::confirmInfo,
            onUndo = viewModel::undoInfo,
            onDismiss = { infoId = null },
        )
    }
    if (filtersOpen) {
        ModalBottomSheet(onDismissRequest = { filtersOpen = false }) {
            FilterPanel(
                state = state,
                viewModel = viewModel,
                includeDraft = includeDraft,
                excludeDraft = excludeDraft,
                batchTag = batchTag,
                yearFromDraft = yearFromDraft,
                yearToDraft = yearToDraft,
                onIncludeDraft = { includeDraft = it },
                onExcludeDraft = { excludeDraft = it },
                onBatchTag = { batchTag = it },
                onYearFrom = {
                    yearFromDraft = it
                    viewModel.setYearFrom(it)
                },
                onYearTo = {
                    yearToDraft = it
                    viewModel.setYearTo(it)
                },
                onSavePreset = { showPresetDialog = true },
                onPickFolder = onPickFolder,
            )
        }
    }
    if (foldersOpen) {
        FolderShieldDialog(
            browse = state.folders,
            onEnter = viewModel::enterFolder,
            onUp = viewModel::folderUp,
            onToggle = viewModel::toggleShield,
            onDismiss = { foldersOpen = false },
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
private fun BoxScope.LocatePlayingButton(listState: LazyListState, rows: List<TrackRow>, currentId: String?) {
    val scope = rememberCoroutineScope()
    val playingIndex = if (currentId == null) -1 else rows.indexOfFirst { it.contentId == currentId }
    val visible = listState.layoutInfo.visibleItemsInfo
    val onScreen = visible.any { it.index == playingIndex }
    if (playingIndex < 0 || visible.isEmpty() || onScreen) return
    Text(
        "定位在播",
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable { scope.launch { listState.animateScrollToItem(playingIndex) } }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun FastScrollRail(
    listState: LazyListState,
    count: Int,
    onDrag: (Int) -> Unit,
) {
    val density = LocalDensity.current
    var railHeight by remember { mutableIntStateOf(0) }
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val info = listState.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: first
    val visible = (last - first + 1).coerceAtLeast(1)
    val span = (count - visible).coerceAtLeast(1)
    val fraction = if (dragFraction >= 0f) dragFraction else first.coerceAtMost(span).toFloat() / span
    val thumbPx = with(density) { 56.dp.toPx() }.coerceAtMost(railHeight.toFloat().coerceAtLeast(1f))
    val travel = (railHeight - thumbPx).coerceAtLeast(0f)
    Box(
        Modifier
            .width(28.dp)
            .fillMaxHeight()
            .onSizeChanged { railHeight = it.height }
            .pointerInput(count) {
                val thumb = 56.dp.toPx()
                fun place(y: Float): Int {
                    val layout = listState.layoutInfo
                    val seen = layout.visibleItemsInfo
                    val shown = if (seen.size < 2) 1 else seen.last().index - seen.first().index + 1
                    val maxStart = (count - shown).coerceAtLeast(0)
                    val limit = (size.height - thumb).coerceAtLeast(1f)
                    val nextFraction = (y - thumb / 2f).coerceIn(0f, limit) / limit
                    dragFraction = nextFraction
                    return if (maxStart == 0) 0 else (nextFraction * maxStart).roundToInt().coerceIn(0, maxStart)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val index = place(down.position.y)
                    onDrag(index)
                    listState.requestScrollToItem(index)
                    drag(down.id) { change ->
                        val next = place(change.position.y)
                        onDrag(next)
                        listState.requestScrollToItem(next)
                        change.consume()
                    }
                    dragFraction = -1f
                    onDrag(-1)
                }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .fillMaxHeight()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (fraction * travel).roundToInt()) }
                .width(6.dp)
                .height(with(density) { thumbPx.toDp() })
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun SettingsPage(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onAllFilesAccess: () -> Unit,
    onShield: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("返回乐库") }
        Text("设置", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Text(state.folderLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(state.status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Button(onClick = viewModel::scanDevice, enabled = !state.scanning) { Text("扫描本机") }
        TextButton(onClick = viewModel::rescan, enabled = !state.scanning) { Text("重新扫描") }
        TextButton(onClick = onAllFilesAccess) { Text("允许直接读取") }
        TextButton(onClick = onShield) { Text("屏蔽文件夹") }
        Spacer(Modifier.height(16.dp))
        Text("个性化", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text("列表第二行", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.showArtist, onCheckedChange = viewModel::setShowArtist)
            Text("显示歌手")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.showAlbum, onCheckedChange = viewModel::setShowAlbum)
            Text("显示专辑")
        }
    }
}

@Composable
private fun CoverThumb(path: String) {
    var image by remember(path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(path) {
        val bitmap = withContext(Dispatchers.IO) {
            if (path.isBlank()) return@withContext null
            val bytes = runCatching { readEmbeddedCover(File(path))?.bytes }.getOrNull() ?: return@withContext null
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
        image = bitmap?.asImageBitmap()
    }
    Box(
        Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (image != null) {
            Image(image!!, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PlayerBar(state: LibraryUiState, viewModel: LibraryViewModel, onPreparePlayback: () -> Unit) {
    val player = state.player
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val fraction = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f
    Column(Modifier.fillMaxWidth()) {
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
                modifier = Modifier.height(28.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverThumb(state.currentPath)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(state.currentTitle.ifBlank { "还没有在播" }, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val playingDetail = listDetail(state.currentArtist, state.currentAlbum, emptyList(), state.showArtist, state.showAlbum)
                if (playingDetail.isNotBlank()) {
                    Text(playingDetail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = {
                onPreparePlayback()
                if (player.queueIds.isEmpty()) viewModel.playFiltered() else viewModel.togglePause()
            }) { Text(if (player.playing) "暂停" else "播放") }
            TextButton(onClick = viewModel::next, enabled = player.queueIds.isNotEmpty()) { Text("下一首") }
        }
        player.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
    yearFromDraft: String,
    yearToDraft: String,
    onIncludeDraft: (String) -> Unit,
    onExcludeDraft: (String) -> Unit,
    onBatchTag: (String) -> Unit,
    onYearFrom: (String) -> Unit,
    onYearTo: (String) -> Unit,
    onSavePreset: () -> Unit,
    onPickFolder: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
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
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = yearFromDraft,
                onValueChange = { onYearFrom(it.filter { char -> char.isDigit() }.take(4)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("从年份") },
            )
            OutlinedTextField(
                value = yearToDraft,
                onValueChange = { onYearTo(it.filter { char -> char.isDigit() }.take(4)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("到年份") },
            )
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
            TextButton(onClick = onPickFolder) { Text("选择同步文件夹") }
            TextButton(onClick = viewModel::toggleSelecting) { Text(if (state.selecting) "完成多选" else "多选打标签") }
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

@Composable
private fun FolderShieldDialog(
    browse: app.tagplayer.core.FolderBrowse,
    onEnter: (String) -> Unit,
    onUp: () -> Unit,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(browse.title) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "点文件夹进入下一层。屏蔽只在这台手机上，不会写进 tags.json。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (browse.canGoUp) {
                    TextButton(onClick = onUp) { Text("上一级") }
                }
                if (browse.entries.isEmpty()) Text("这一层没有文件夹")
                browse.entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { if (entry.canEnter) onEnter(entry.path) },
                            enabled = entry.canEnter,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("${entry.name}  ${entry.songCount} 首", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onToggle(entry.path) }) { Text(if (entry.blocked) "取消" else "屏蔽") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongFactDialog(fact: SongFactUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fact.title.ifBlank { "歌曲信息" }) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CoverPreview(fact.cover)
                Text(if (fact.year.isBlank()) "年份 还没有" else "年份 ${fact.year}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FactChip("歌手", fact.artist)
                    FactChip("专辑", fact.album)
                    FactChip("流派", fact.genre)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun FactChip(label: String, value: String) {
    val text = if (value.isBlank()) "$label 还没有" else "$label $value"
    Text(
        text,
        color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun CoverDialog(
    title: String,
    cover: CoverUiState,
    onPickCover: () -> Unit,
    onFindCover: () -> Unit,
    onPickChoice: (Int) -> Unit,
    onConfirmCover: () -> Unit,
    onDismissCover: () -> Unit,
    onUndoCover: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CoverPreview(cover.proposal ?: cover.bytes)
                if (cover.note.isNotBlank()) {
                    Text(cover.note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPickCover, enabled = !cover.busy) { Text("选择图片") }
                    TextButton(onClick = onFindCover, enabled = !cover.busy) { Text("查找封面") }
                }
                cover.choices.forEachIndexed { index, choice ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !cover.busy) { onPickChoice(index) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChoiceThumb(choice.thumb)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(choice.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val who = listOf(choice.artist, choice.album).filter { it.isNotBlank() }.joinToString(" · ")
                            if (who.isNotBlank()) {
                                Text(who, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (cover.proposal != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onConfirmCover, enabled = !cover.busy) { Text("替换封面") }
                        TextButton(onClick = onDismissCover) { Text("先不写") }
                    }
                }
                if (cover.canUndo) {
                    TextButton(onClick = onUndoCover, enabled = !cover.busy) { Text("撤销") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun InfoDialog(
    title: String,
    info: InfoUiState,
    onPick: (Int) -> Unit,
    onTitle: (Boolean) -> Unit,
    onArtist: (Boolean) -> Unit,
    onAlbum: (Boolean) -> Unit,
    onGenre: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val picked = info.matches.getOrNull(info.picked)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.note.isNotBlank()) {
                    Text(info.note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                info.matches.forEachIndexed { index, match ->
                    TextButton(onClick = { onPick(index) }, enabled = !info.busy) {
                        Text(
                            listOf(match.title, match.artist, match.album, match.genre).filter { it.isNotBlank() }.joinToString(" · "),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (picked != null) {
                    Text("写入这些项：")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = info.useTitle, onCheckedChange = onTitle)
                        Text("歌名 ${picked.title}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = info.useArtist, onCheckedChange = onArtist)
                        Text("歌手 ${picked.artist}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = info.useAlbum, onCheckedChange = onAlbum)
                        Text("专辑 ${picked.album}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = info.useGenre, onCheckedChange = onGenre)
                        Text("流派 ${picked.genre}")
                    }
                    TextButton(onClick = onConfirm, enabled = !info.busy) { Text("写入文件") }
                }
                if (info.canUndo) {
                    TextButton(onClick = onUndo, enabled = !info.busy) { Text("撤销") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ChoiceThumb(bytes: ByteArray?) {
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    if (bitmap == null) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun CoverPreview(bytes: ByteArray?) {
    if (bytes == null) {
        Text("这首歌还没有封面", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    if (bitmap == null) {
        Text("这张封面打不开")
    } else {
        Image(bitmap.asImageBitmap(), contentDescription = "封面", modifier = Modifier.size(120.dp))
    }
}
