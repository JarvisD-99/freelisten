package app.tagplayer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import java.io.File
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tagplayer.TagPlayerApplication
import app.tagplayer.core.CoverEditException
import app.tagplayer.core.CoverUndoStore
import app.tagplayer.core.EmbeddedCover
import app.tagplayer.core.EmbeddedText
import app.tagplayer.core.FolderBrowse
import app.tagplayer.core.FolderShieldStore
import app.tagplayer.core.IncludeMode
import app.tagplayer.core.browseFolders
import app.tagplayer.core.folderBlocks
import app.tagplayer.core.folderParent
import app.tagplayer.core.toggleFolderShield
import app.tagplayer.core.TextUndoStore
import app.tagplayer.core.TrackMatch
import app.tagplayer.core.applying
import app.tagplayer.core.lookupTrackMatches
import app.tagplayer.core.recognitionQuery
import app.tagplayer.core.readEmbeddedText
import app.tagplayer.core.replaceTrackText
import app.tagplayer.core.undoTrackText
import app.tagplayer.core.LocalTrack
import app.tagplayer.core.MAX_COVER_BYTES
import app.tagplayer.core.looksLikeCoverImage
import app.tagplayer.core.embeddedCoverAt
import app.tagplayer.core.rankCoverChoices
import app.tagplayer.core.readEmbeddedCover
import app.tagplayer.core.replaceTrackCover
import app.tagplayer.core.sniffImageMime
import app.tagplayer.core.undoTrackCover
import app.tagplayer.core.PlaylistFilter
import app.tagplayer.core.Preset
import app.tagplayer.core.SongRecord
import app.tagplayer.core.TagFile
import app.tagplayer.core.addTag
import app.tagplayer.core.displayAlbum
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
    val album: String,
    val path: String,
    val tags: List<String>,
)

data class InfoUiState(
    val trackId: String? = null,
    val current: EmbeddedText = EmbeddedText(),
    val matches: List<TrackMatch> = emptyList(),
    val picked: Int = -1,
    val useTitle: Boolean = true,
    val useArtist: Boolean = true,
    val useAlbum: Boolean = true,
    val useGenre: Boolean = true,
    val note: String = "",
    val busy: Boolean = false,
    val canUndo: Boolean = false,
)

data class CoverChoiceUi(
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val thumb: ByteArray? = null,
)

data class CoverUiState(
    val trackId: String? = null,
    val bytes: ByteArray? = null,
    val proposal: ByteArray? = null,
    val choices: List<CoverChoiceUi> = emptyList(),
    val note: String = "",
    val busy: Boolean = false,
    val canUndo: Boolean = false,
)

data class SongFactUi(
    val trackId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: String = "",
    val cover: ByteArray? = null,
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
    val currentAlbum: String = "",
    val currentPath: String = "",
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val currentTags: List<String> = emptyList(),
    val showArtist: Boolean = true,
    val showAlbum: Boolean = true,
    val folders: FolderBrowse = FolderBrowse(),
)

class LibraryViewModel(private val app: TagPlayerApplication) : AndroidViewModel(app) {
    private val library = app.library
    private val prefs = app.getSharedPreferences("tagplayer", android.content.Context.MODE_PRIVATE)
    private var showArtist = prefs.getBoolean(KEY_SHOW_ARTIST, true)
    private var showAlbum = prefs.getBoolean(KEY_SHOW_ALBUM, true)
    private var yearFrom: Int? = null
    private var yearTo: Int? = null
    private val coverUndo = CoverUndoStore(File(app.filesDir, "cover-undo"))
    private val textUndo = TextUndoStore(File(app.filesDir, "text-undo"))
    private val shieldStore = FolderShieldStore(File(app.filesDir, "folder-shield.json"))
    private var shielded = shieldStore.load()
    private var shieldInside: String? = null
    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui
    private val _cover = MutableStateFlow(CoverUiState())
    val cover: StateFlow<CoverUiState> = _cover
    private val _info = MutableStateFlow(InfoUiState())
    val info: StateFlow<InfoUiState> = _info
    private val _fact = MutableStateFlow(SongFactUi())
    val fact: StateFlow<SongFactUi> = _fact
    private var coverTrackId: String? = null
    private var infoTrackId: String? = null

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
                when {
                    library.canReadDeviceMusic() -> scanDevice()
                    library.hasTree() -> rescan()
                }
            }
        }
    }

    fun setFolder(uri: Uri) {
        library.setFolder(uri)
        reloadTags()
        status = "同步文件夹只用来放 tags.json，乐库仍是整机扫描的结果。"
        publish()
    }

    fun scanDevice() {
        viewModelScope.launch {
            scanning = true
            status = "正在扫描本机音乐…"
            publish()
            try {
                withContext(Dispatchers.IO) {
                    library.scanDevice(shielded) { text ->
                        viewModelScope.launch(Dispatchers.Main) {
                            status = text
                            publish()
                        }
                    }
                }
                status = if (library.wholeDeviceScan()) {
                    "本机 ${library.tracks.size} 首。不想听的文件夹可以逐层屏蔽。"
                } else {
                    "扫到 ${library.tracks.size} 首，目前是「音乐」文件夹。允许直接读取后，再点扫描可以扫整个存储。"
                }
            } catch (error: Exception) {
                status = error.message ?: "扫描失败"
            }
            scanning = false
            publish()
        }
    }

    fun enterFolder(path: String) {
        if (path.startsWith("=")) return
        shieldInside = path
        publish()
    }

    fun folderUp() {
        val current = shieldInside ?: return
        shieldInside = folderParent(current)
        publish()
    }

    fun toggleShield(path: String) {
        shielded = toggleFolderShield(shielded, path)
        shieldStore.save(shielded)
        publish()
    }

    fun rescan() {
        if (library.canReadDeviceMusic() || library.deviceScanEnabled()) {
            scanDevice()
            return
        }
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

    fun setShowArtist(value: Boolean) {
        showArtist = value
        prefs.edit().putBoolean(KEY_SHOW_ARTIST, value).apply()
        publish()
    }

    fun setYearFrom(raw: String) {
        yearFrom = raw.toIntOrNull()?.takeIf { raw.length == 4 }
        publish()
    }

    fun setYearTo(raw: String) {
        yearTo = raw.toIntOrNull()?.takeIf { raw.length == 4 }
        publish()
    }

    fun setShowAlbum(value: Boolean) {
        showAlbum = value
        prefs.edit().putBoolean(KEY_SHOW_ALBUM, value).apply()
        publish()
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
        val filter = PlaylistFilter(include, includeMode, exclude, yearFrom = yearFrom, yearTo = yearTo)
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

    fun prepareCover(contentId: String) {
        coverTrackId = contentId
        _cover.value = CoverUiState(trackId = contentId, busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val file = writableFile(contentId)
            val bytes = file?.let { runCatching { readEmbeddedCover(it)?.bytes }.getOrNull() }
            val undo = coverUndo.has(contentId)
            withContext(Dispatchers.Main) {
                if (coverTrackId != contentId) return@withContext
                _cover.value = CoverUiState(
                    trackId = contentId,
                    bytes = bytes,
                    canUndo = undo,
                    note = if (file == null) "这个文件现在不能改。先允许直接读取音乐文件夹。封面只在你点替换后才写入。" else "",
                )
            }
        }
    }

    fun stageCover(bytes: ByteArray) {
        if (!looksLikeCoverImage(bytes)) {
            _cover.value = _cover.value.copy(note = "请选一张不超过 8MB 的 JPEG 或 PNG")
            return
        }
        _cover.value = _cover.value.copy(
            proposal = bytes,
            note = "先看这张图。点「替换封面」才会写进文件，之后可以撤销。",
        )
    }

    fun stageCoverFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = readCoverBytes(uri)
            withContext(Dispatchers.Main) {
                if (bytes == null) {
                    _cover.value = _cover.value.copy(note = "请选一张不超过 8MB 的 JPEG 或 PNG")
                } else {
                    stageCover(bytes)
                }
            }
        }
    }

    fun findCoverOnline() {
        val id = coverTrackId ?: return
        val track = library.find(id) ?: return
        _cover.value = _cover.value.copy(busy = true, choices = emptyList(), note = "正在查找相近的封面…")
        viewModelScope.launch(Dispatchers.IO) {
            val record = library.tagFile.songs[id]
            val (queryArtist, queryTitle) = recognitionQuery(track, displayArtist(track, record), displayTitle(track, record))
            val ranked = runCatching { rankCoverChoices(lookupTrackMatches(queryArtist, queryTitle, limit = 8), queryArtist, queryTitle) }.getOrDefault(emptyList())
            val choices = ranked.map { choice ->
                val thumb = runCatching { embeddedCoverAt(choice.artworkUrl, large = false)?.bytes }.getOrNull()
                CoverChoiceUi(choice.title, choice.artist, choice.album, choice.artworkUrl, thumb)
            }
            withContext(Dispatchers.Main) {
                if (coverTrackId != id) return@withContext
                _cover.value = _cover.value.copy(
                    busy = false,
                    choices = choices,
                    note = if (choices.isEmpty()) "没有查到相近的封面" else "这些是相近结果。点一张，再决定要不要替换。",
                )
            }
        }
    }

    fun pickCoverChoice(index: Int) {
        val choice = _cover.value.choices.getOrNull(index) ?: return
        _cover.value = _cover.value.copy(busy = true, note = "正在取「${choice.title}」的封面…")
        viewModelScope.launch(Dispatchers.IO) {
            val image = runCatching { embeddedCoverAt(choice.artworkUrl, large = true) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (coverTrackId == null) return@withContext
                if (image == null) {
                    _cover.value = _cover.value.copy(busy = false, note = "这张图没能下载")
                } else {
                    _cover.value = _cover.value.copy(
                        busy = false,
                        proposal = image.bytes,
                        note = "先看这张图。点「替换封面」才会写进文件，之后可以撤销。",
                    )
                }
            }
        }
    }

    fun loadSongFact(contentId: String) {
        val track = library.find(contentId)
        val record = library.tagFile.songs[contentId]
        _fact.value = SongFactUi(trackId = contentId, title = track?.let { displayTitle(it, record) }.orEmpty())
        viewModelScope.launch(Dispatchers.IO) {
            val file = writableFile(contentId)
            val coverBytes = file?.let { runCatching { readEmbeddedCover(it)?.bytes }.getOrNull() }
            val cached = library.cachedMatch(contentId)
            val genre = record?.genre?.takeIf { it.isNotBlank() } ?: cached?.genre.orEmpty()
            val year = record?.releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
            withContext(Dispatchers.Main) {
                if (_fact.value.trackId != contentId) return@withContext
                _fact.value = SongFactUi(
                    trackId = contentId,
                    title = track?.let { displayTitle(it, record) }.orEmpty(),
                    artist = track?.let { displayArtist(it, record) }.orEmpty(),
                    album = track?.let { displayAlbum(it, record) }.orEmpty(),
                    genre = genre,
                    year = year,
                    cover = coverBytes,
                )
            }
        }
    }

    fun confirmCover() {
        val id = coverTrackId ?: return
        val proposal = _cover.value.proposal ?: return
        val file = writableFile(id) ?: run {
            _cover.value = _cover.value.copy(note = "这个文件现在不能改。先允许直接读取音乐文件夹。")
            return
        }
        _cover.value = _cover.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val written = runCatching {
                replaceTrackCover(file, id, EmbeddedCover(sniffImageMime(proposal), proposal), coverUndo)
            }
            written.onSuccess { newId ->
                library.replaceTrackIdentity(id, newId, file)
                textUndo.move(id, newId)
            }
            withContext(Dispatchers.Main) {
                written.onSuccess { newId ->
                    renamePlayback(id, newId)
                    if (coverTrackId == id || coverTrackId == newId) {
                        coverTrackId = newId
                        _cover.value = CoverUiState(
                            trackId = newId,
                            bytes = proposal,
                            note = "封面已写入文件。撤销只在这台手机上。",
                            canUndo = true,
                        )
                    }
                    publish()
                }.onFailure { error ->
                    if (coverTrackId == id) {
                        _cover.value = _cover.value.copy(busy = false, note = (error as? CoverEditException)?.message ?: error.message ?: "封面没能写进文件")
                    }
                }
            }
        }
    }

    fun undoCover() {
        val id = coverTrackId ?: return
        val file = writableFile(id) ?: run {
            _cover.value = _cover.value.copy(note = "这个文件现在不能改。先允许直接读取音乐文件夹。")
            return
        }
        _cover.value = _cover.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val restored = runCatching { undoTrackCover(file, id, coverUndo) }
            val bytes = restored.getOrNull()?.let { runCatching { readEmbeddedCover(file)?.bytes }.getOrNull() }
            restored.onSuccess { newId ->
                library.replaceTrackIdentity(id, newId, file)
                textUndo.move(id, newId)
            }
            withContext(Dispatchers.Main) {
                restored.onSuccess { newId ->
                    renamePlayback(id, newId)
                    if (coverTrackId == id || coverTrackId == newId) {
                        coverTrackId = newId
                        _cover.value = CoverUiState(
                            trackId = newId,
                            bytes = bytes,
                            note = "已恢复替换前的封面",
                            canUndo = false,
                        )
                    }
                    publish()
                }.onFailure { error ->
                    if (coverTrackId == id) {
                        _cover.value = _cover.value.copy(busy = false, note = error.message ?: "没能撤销")
                    }
                }
            }
        }
    }

    fun dismissCoverProposal() {
        _cover.value = _cover.value.copy(proposal = null)
    }

    fun lookupInfo(contentId: String) {
        infoTrackId = contentId
        val track = library.find(contentId)
        _info.value = InfoUiState(trackId = contentId, busy = true, note = "正在查找歌手、专辑和流派…", canUndo = textUndo.has(contentId))
        viewModelScope.launch(Dispatchers.IO) {
            val file = writableFile(contentId)
            val current = file?.let { runCatching { readEmbeddedText(it) }.getOrNull() } ?: EmbeddedText()
            val matches = if (track == null) {
                emptyList()
            } else {
                val record = library.tagFile.songs[contentId]
                val (queryArtist, queryTitle) = recognitionQuery(track, displayArtist(track, record), displayTitle(track, record))
                runCatching { lookupTrackMatches(queryArtist, queryTitle) }.getOrDefault(emptyList())
            }
            withContext(Dispatchers.Main) {
                if (infoTrackId != contentId) return@withContext
                _info.value = InfoUiState(
                    trackId = contentId,
                    current = current,
                    matches = matches,
                    canUndo = textUndo.has(contentId),
                    note = when {
                        file == null -> "这个文件现在不能改。先允许直接读取音乐文件夹。上面仍是查到的结果，还不能写入。"
                        matches.isEmpty() -> "没有查到和这首歌对应的结果"
                        else -> "选一条结果，勾选要写入的项，再点写入文件。"
                    },
                )
            }
        }
    }

    fun pickMatch(index: Int) {
        val match = _info.value.matches.getOrNull(index) ?: return
        _info.value = _info.value.copy(
            picked = index,
            useTitle = match.title.isNotBlank(),
            useArtist = match.artist.isNotBlank(),
            useAlbum = match.album.isNotBlank(),
            useGenre = match.genre.isNotBlank(),
        )
    }

    fun setInfoField(title: Boolean? = null, artist: Boolean? = null, album: Boolean? = null, genre: Boolean? = null) {
        val current = _info.value
        _info.value = current.copy(
            useTitle = title ?: current.useTitle,
            useArtist = artist ?: current.useArtist,
            useAlbum = album ?: current.useAlbum,
            useGenre = genre ?: current.useGenre,
        )
    }

    fun confirmInfo() {
        val state = _info.value
        val id = infoTrackId ?: return
        val match = state.matches.getOrNull(state.picked) ?: run {
            _info.value = state.copy(note = "先选一条结果")
            return
        }
        if (!state.useTitle && !state.useArtist && !state.useAlbum && !state.useGenre) {
            _info.value = state.copy(note = "先勾选要写入的项")
            return
        }
        val file = writableFile(id) ?: run {
            _info.value = state.copy(note = "这个文件现在不能改。先允许直接读取音乐文件夹。")
            return
        }
        _info.value = state.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val written = runCatching {
                val current = runCatching { readEmbeddedText(file) }.getOrDefault(EmbeddedText())
                val next = current.applying(match, state.useTitle, state.useArtist, state.useAlbum, state.useGenre)
                next to replaceTrackText(file, id, next, textUndo)
            }
            written.onSuccess { (next, newId) ->
                coverUndo.move(id, newId)
                library.replaceTrackIdentity(id, newId, file, next, lockNames = state.useTitle && state.useArtist)
            }
            withContext(Dispatchers.Main) {
                written.onSuccess { (next, newId) ->
                    syncPlayback(id, newId)
                    if (infoTrackId == id || infoTrackId == newId) {
                        infoTrackId = newId
                        _info.value = InfoUiState(
                            trackId = newId,
                            current = next,
                            note = "已写入文件。撤销只在这台手机上。",
                            canUndo = true,
                        )
                    }
                    publish()
                }.onFailure { error ->
                    if (infoTrackId == id) {
                        _info.value = _info.value.copy(busy = false, note = error.message ?: "歌曲信息没能写进文件")
                    }
                }
            }
        }
    }

    fun undoInfo() {
        val id = infoTrackId ?: return
        val file = writableFile(id) ?: run {
            _info.value = _info.value.copy(note = "这个文件现在不能改。先允许直接读取音乐文件夹。")
            return
        }
        _info.value = _info.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val restored = runCatching { undoTrackText(file, id, textUndo) }
            val text = restored.getOrNull()?.let { runCatching { readEmbeddedText(file) }.getOrNull() }
            restored.onSuccess { newId ->
                coverUndo.move(id, newId)
                library.replaceTrackIdentity(id, newId, file, text ?: EmbeddedText(), clearNames = true)
            }
            withContext(Dispatchers.Main) {
                restored.onSuccess { newId ->
                    val next = text ?: EmbeddedText()
                    syncPlayback(id, newId)
                    if (infoTrackId == id || infoTrackId == newId) {
                        infoTrackId = newId
                        _info.value = InfoUiState(
                            trackId = newId,
                            current = next,
                            note = "已恢复写入前的歌手、专辑和流派",
                            canUndo = false,
                        )
                    }
                    publish()
                }.onFailure { error ->
                    if (infoTrackId == id) {
                        _info.value = _info.value.copy(busy = false, note = error.message ?: "没能撤销")
                    }
                }
            }
        }
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

    private fun visibleTracks(): List<LocalTrack> = filterTracks(
        library.tracks.filter { !folderBlocks(it, shielded) },
        library.tagFile,
        PlaylistFilter(include, includeMode, exclude, query, yearFrom, yearTo),
    )

    private fun filteredIds(): List<String> = visibleTracks().map { it.contentId }

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

    private fun writableFile(contentId: String): File? {
        val path = library.find(contentId)?.path ?: return null
        if (path.isBlank()) return null
        val file = File(path)
        return file.takeIf { it.isFile && it.canWrite() }
    }

    private fun readCoverBytes(uri: Uri): ByteArray? {
        val input = app.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_COVER_BYTES) {
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            if (output.size() > MAX_COVER_BYTES) return null
            return output.toByteArray()
        }
    }

    private fun syncPlayback(fromId: String, toId: String) {
        val ids = PlaybackBus.state.value.queueIds
        if (ids.none { it == fromId || it == toId }) return
        val intent = Intent(app, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_RENAME_ID)
            .putExtra(PlaybackService.EXTRA_FROM, fromId)
            .putExtra(PlaybackService.EXTRA_TO, toId)
        ContextCompat.startForegroundService(app, intent)
    }

    private fun renamePlayback(fromId: String, toId: String) = syncPlayback(fromId, toId)

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
            album = displayAlbum(track, record),
            path = track.path,
            tags = record?.let(::effectiveTags)?.sorted().orEmpty(),
        )
    }

    private fun publish() {
        val tagFile = library.tagFile
        val filtered = visibleTracks()
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
            currentAlbum = current?.let { displayAlbum(it, currentRecord) }.orEmpty(),
            currentPath = current?.path.orEmpty(),
            yearFrom = yearFrom,
            yearTo = yearTo,
            currentTags = current?.contentId?.let { tagFile.songs[it] }?.let(::effectiveTags)?.sorted().orEmpty(),
            showArtist = showArtist,
            showAlbum = showAlbum,
            folders = browseFolders(library.tracks, shieldInside, shielded),
        )
    }

    private companion object {
        const val KEY_SHOW_ARTIST = "show_artist"
        const val KEY_SHOW_ALBUM = "show_album"
    }

    class Factory(private val app: TagPlayerApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(app) as T
    }
}
