package app.tagplayer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tagplayer.TagPlayerApplication
import app.tagplayer.core.IncludeMode
import app.tagplayer.core.LocalTrack
import app.tagplayer.core.PlaylistFilter
import app.tagplayer.core.Preset
import app.tagplayer.core.SongRecord
import app.tagplayer.core.TagFile
import app.tagplayer.core.addTag
import app.tagplayer.core.displayArtist
import app.tagplayer.core.displayTitle
import app.tagplayer.core.effectiveTags
import app.tagplayer.core.filterTracks
import app.tagplayer.core.mode
import app.tagplayer.core.normalizeTag
import app.tagplayer.core.queueStartingWith
import app.tagplayer.core.removeTag
import app.tagplayer.core.shuffledQueue
import app.tagplayer.core.tagCounts
import app.tagplayer.core.upsertPreset
import app.tagplayer.core.withMetadata
import app.tagplayer.core.withSong
import app.tagplayer.core.withoutPreset
import app.tagplayer.player.PlaybackBus
import app.tagplayer.player.PlaybackService
import app.tagplayer.player.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrackRow(
    val contentId: String,
    val title: String,
    val artist: String,
    val tags: List<String>,
)

data class LibraryUiState(
    val folderLabel: String = "还没有选择音乐文件夹",
    val hasFolder: Boolean = false,
    val scanning: Boolean = false,
    val status: String = "选择用 Syncthing 同步的音乐文件夹，标签写在里面的 tags.json。",
    val rows: List<TrackRow> = emptyList(),
    val libraryRows: List<TrackRow> = emptyList(),
    val filteredCount: Int = 0,
    val totalCount: Int = 0,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val includeMode: IncludeMode = IncludeMode.All,
    val tagCounts: List<Pair<String, Int>> = emptyList(),
    val presets: List<Preset> = emptyList(),
    val query: String = "",
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    val player: PlayerState = PlayerState(),
    val currentTitle: String = "",
    val currentArtist: String = "",
    val currentTags: List<String> = emptyList(),
)

class LibraryViewModel(private val app: TagPlayerApplication) : AndroidViewModel(app) {
    private val library = app.library
    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui

    private var include = listOf<String>()
    private var exclude = listOf<String>()
    private var includeMode = IncludeMode.All
    private var query = ""
    private var selecting = false
    private var selected = setOf<String>()
    private var scanning = false
    private var status = _ui.value.status

    init {
        viewModelScope.launch {
            PlaybackBus.state.collect { publish() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            library.loadCached()
            withContext(Dispatchers.Main) {
                publish()
                if (library.hasFolder()) rescan()
            }
        }
    }

    fun setFolder(uri: Uri) {
        library.setFolder(uri)
        rescan()
    }

    fun rescan() {
        if (!library.hasFolder() && library.treeUri() == null) return
        viewModelScope.launch {
            scanning = true
            status = "正在扫描…"
            publish()
            try {
                withContext(Dispatchers.IO) {
                    library.scan { text ->
                        viewModelScope.launch(Dispatchers.Main) {
                            status = text
                            publish()
                        }
                    }
                }
                status = "曲库 ${library.tracks.size} 首。标签在文件夹里的 tags.json"
            } catch (error: Exception) {
                status = error.message ?: "扫描失败"
            }
            scanning = false
            publish()
        }
    }

    fun reloadTags() {
        if (!library.hasFolder()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                library.reloadTags()
                withContext(Dispatchers.Main) { publish() }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    status = error.message ?: "标签文件读不了"
                    publish()
                }
            }
        }
    }

    fun setQuery(value: String) {
        query = value
        publish()
    }

    fun setIncludeMode(mode: IncludeMode) {
        includeMode = mode
        publish()
    }

    fun cycleTag(tag: String) {
        when {
            tag in include -> {
                include = include - tag
                exclude = (exclude + tag).distinct()
            }
            tag in exclude -> exclude = exclude - tag
            else -> {
                exclude = exclude - tag
                include = include + tag
            }
        }
        publish()
    }

    fun addInclude(raw: String) = addFilter(raw, toInclude = true)

    fun addExclude(raw: String) = addFilter(raw, toInclude = false)

    fun toggleSelecting() {
        selecting = !selecting
        if (!selecting) selected = emptySet()
        publish()
    }

    fun toggleSelected(id: String) {
        selected = if (id in selected) selected - id else selected + id
        publish()
    }

    fun playFiltered() {
        val ids = filteredIds()
        if (ids.isEmpty()) {
            status = "没有符合的歌"
            publish()
            return
        }
        send(PlaybackService.ACTION_PLAY_QUEUE, shuffledQueue(ids))
    }

    fun playTrack(id: String) {
        val ids = filteredIds()
        if (id !in ids) return
        send(PlaybackService.ACTION_PLAY_QUEUE, queueStartingWith(ids, id))
    }

    fun togglePause() = send(PlaybackService.ACTION_TOGGLE)

    fun next() = send(PlaybackService.ACTION_NEXT)

    fun previous() = send(PlaybackService.ACTION_PREV)

    fun seek(positionMs: Long) = send(PlaybackService.ACTION_SEEK, position = positionMs)

    fun toggleTag(id: String, tag: String) {
        val now = System.currentTimeMillis()
        edit { file ->
            val song = recordFor(file, id)
            val next = if (tag in effectiveTags(song)) removeTag(song, tag, now) else addTag(song, tag, now)
            file.withSong(id, next)
        }
    }

    fun addTag(id: String, raw: String) {
        val now = System.currentTimeMillis()
        edit { file -> file.withSong(id, addTag(recordFor(file, id), raw, now)) }
    }

    fun addTagToSelected(raw: String) = editSelected(raw, remove = false)

    fun removeTagFromSelected(raw: String) = editSelected(raw, remove = true)

    fun savePreset(name: String) {
        val filter = PlaylistFilter(include, includeMode, exclude)
        edit { it.upsertPreset(name, filter, System.currentTimeMillis()) }
    }

    fun applyPreset(id: String) {
        val preset = library.tagFile.presets.find { it.id == id } ?: return
        include = preset.include
        exclude = preset.exclude
        includeMode = preset.mode()
        status = "已用方案「${preset.name}」"
        publish()
    }

    fun deletePreset(id: String) {
        edit { it.withoutPreset(id) }
    }

    private fun editSelected(raw: String, remove: Boolean) {
        val ids = selected
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        edit { file ->
            ids.fold(file) { current, id ->
                val song = recordFor(current, id)
                current.withSong(id, if (remove) removeTag(song, raw, now) else addTag(song, raw, now))
            }
        }
    }

    private fun addFilter(raw: String, toInclude: Boolean) {
        val tag = normalizeTag(raw) ?: return
        if (toInclude) {
            exclude = exclude - tag
            include = (include + tag).distinct()
        } else {
            include = include - tag
            exclude = (exclude + tag).distinct()
        }
        publish()
    }

    private fun filteredIds(): List<String> = filterTracks(
        library.tracks,
        library.tagFile,
        PlaylistFilter(include, includeMode, exclude, query),
    ).map { it.contentId }

    private fun recordFor(file: TagFile, id: String): SongRecord {
        val track = library.tracks.find { it.contentId == id }
        val current = file.songs[id] ?: SongRecord()
        return if (track == null) {
            current
        } else {
            current.withMetadata(track.title, track.artist, track.album, track.durationMs, track.size, System.currentTimeMillis())
        }
    }

    private fun edit(block: (TagFile) -> TagFile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                library.updateTags(block)
                withContext(Dispatchers.Main) { publish() }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    status = error.message ?: "标签没能保存"
                    publish()
                }
            }
        }
    }

    private fun send(action: String, ids: List<String>? = null, position: Long? = null) {
        val intent = Intent(app, PlaybackService::class.java).setAction(action)
        if (ids != null) intent.putStringArrayListExtra(PlaybackService.EXTRA_IDS, ArrayList(ids))
        if (position != null) intent.putExtra(PlaybackService.EXTRA_POS, position)
        ContextCompat.startForegroundService(app, intent)
    }

    private fun rowFor(track: LocalTrack, tagFile: TagFile): TrackRow {
        val record = tagFile.songs[track.contentId]
        return TrackRow(
            contentId = track.contentId,
            title = displayTitle(track, record),
            artist = displayArtist(track, record),
            tags = record?.let(::effectiveTags)?.sorted().orEmpty(),
        )
    }

    private fun publish() {
        val tagFile = library.tagFile
        val filtered = filterTracks(library.tracks, tagFile, PlaylistFilter(include, includeMode, exclude, query))
        val player = PlaybackBus.state.value
        val current = library.tracks.find { it.contentId == player.currentId }
        val currentRecord = current?.let { tagFile.songs[it.contentId] }
        _ui.value = LibraryUiState(
            folderLabel = library.folderLabel,
            hasFolder = library.hasFolder(),
            scanning = scanning,
            status = status,
            rows = filtered.map { track -> rowFor(track, tagFile) },
            libraryRows = library.tracks.map { track -> rowFor(track, tagFile) },
            filteredCount = filtered.size,
            totalCount = library.tracks.size,
            include = include,
            exclude = exclude,
            includeMode = includeMode,
            tagCounts = tagCounts(tagFile),
            presets = tagFile.presets,
            query = query,
            selecting = selecting,
            selected = selected,
            player = player,
            currentTitle = current?.let { displayTitle(it, currentRecord) }.orEmpty(),
            currentArtist = current?.let { displayArtist(it, currentRecord) }.orEmpty(),
            currentTags = current?.contentId?.let { tagFile.songs[it] }?.let(::effectiveTags)?.sorted().orEmpty(),
        )
    }

    class Factory(private val app: TagPlayerApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(app) as T
    }
}
