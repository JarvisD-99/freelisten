package app.tagplayer.desktop

import app.tagplayer.core.CoverUndoStore
import app.tagplayer.core.EmbeddedCover
import app.tagplayer.core.EmbeddedText
import app.tagplayer.core.TextUndoStore
import app.tagplayer.core.TrackMatch
import app.tagplayer.core.applying
import app.tagplayer.core.OnlineMatchStore
import app.tagplayer.core.applyOnlineMatches
import app.tagplayer.core.fillOnlineMatches
import app.tagplayer.core.findOnlineMatch
import app.tagplayer.core.lookupTrackMatches
import app.tagplayer.core.recognitionQuery
import app.tagplayer.core.tracksWithConfirmedNames
import app.tagplayer.core.displayAlbum
import app.tagplayer.core.displayArtist
import app.tagplayer.core.displayTitle
import app.tagplayer.core.readEmbeddedText
import app.tagplayer.core.replaceTrackText
import app.tagplayer.core.undoTrackText
import app.tagplayer.core.FileTagIo
import app.tagplayer.core.FolderShieldStore
import app.tagplayer.core.browseFolders
import app.tagplayer.core.folderBlocks
import app.tagplayer.core.folderParent
import app.tagplayer.core.toggleFolderShield
import app.tagplayer.core.looksLikeCoverImage
import app.tagplayer.core.CoverChoice
import app.tagplayer.core.embeddedCoverAt
import app.tagplayer.core.rankCoverChoices
import app.tagplayer.core.moveSong
import app.tagplayer.core.readEmbeddedCover
import app.tagplayer.core.replaceTrackCover
import app.tagplayer.core.sniffImageMime
import app.tagplayer.core.undoTrackCover
import app.tagplayer.core.IncludeMode
import app.tagplayer.core.LocalTrack
import app.tagplayer.core.PlayQueue
import app.tagplayer.core.PlaylistFilter
import app.tagplayer.core.PreviousStep
import app.tagplayer.core.QueueStep
import app.tagplayer.core.SongRecord
import app.tagplayer.core.TagFile
import app.tagplayer.core.TagRepository
import app.tagplayer.core.addTag
import app.tagplayer.core.effectiveTags
import app.tagplayer.core.filterTracks
import app.tagplayer.core.loadScanCache
import app.tagplayer.core.mode
import app.tagplayer.core.nextTrack
import app.tagplayer.core.normalizeTag
import app.tagplayer.core.previousTrack
import app.tagplayer.core.queueStartingWith
import app.tagplayer.core.removeTag
import app.tagplayer.core.saveScanCache
import app.tagplayer.core.scanAudioFolder
import app.tagplayer.core.shuffledQueue
import app.tagplayer.core.upsertMetadata
import app.tagplayer.core.upsertPreset
import app.tagplayer.core.withMetadata
import app.tagplayer.core.withSong
import app.tagplayer.core.withoutPreset
import java.io.File
import java.util.Properties
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AppModel {
    private val settingsFile = File(System.getProperty("user.home"), ".tagplayer/settings.properties")
    private val cacheFile = File(System.getProperty("user.home"), ".tagplayer/scan-cache.json")
    private val coverUndo = CoverUndoStore(File(System.getProperty("user.home"), ".tagplayer/cover-undo"))
    private val textUndo = TextUndoStore(File(System.getProperty("user.home"), ".tagplayer/text-undo"))
    private val shieldStore = FolderShieldStore(File(System.getProperty("user.home"), ".tagplayer/folder-shield.json"))
    private val matchStore = OnlineMatchStore(File(System.getProperty("user.home"), ".tagplayer/online-match.json"))

    var libraryRoot by mutableStateOf<File?>(null)
    var tracks by mutableStateOf(listOf<LocalTrack>())
    var tagFile by mutableStateOf(TagFile())
    var include by mutableStateOf(listOf<String>())
    var exclude by mutableStateOf(listOf<String>())
    var includeMode by mutableStateOf(IncludeMode.All)
    var query by mutableStateOf("")
    var scanning by mutableStateOf(false)
    var status by mutableStateOf(playbackHint())
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf(setOf<String>())
    var queue by mutableStateOf(PlayQueue())
    var currentId by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var coverBytes by mutableStateOf<ByteArray?>(null)
    var coverProposal by mutableStateOf<ByteArray?>(null)
    var coverNote by mutableStateOf("")
    var coverChoices by mutableStateOf(listOf<CoverChoice>())
    var coverBusy by mutableStateOf(false)
    var coverCanUndo by mutableStateOf(false)
    var coverTrackId by mutableStateOf<String?>(null)
    var infoTrackId by mutableStateOf<String?>(null)
    var infoBusy by mutableStateOf(false)
    var infoNote by mutableStateOf("")
    var infoMatches by mutableStateOf(listOf<TrackMatch>())
    var infoPicked by mutableStateOf(-1)
    var infoUseTitle by mutableStateOf(true)
    var infoUseArtist by mutableStateOf(true)
    var infoUseAlbum by mutableStateOf(true)
    var infoUseGenre by mutableStateOf(true)
    var infoCanUndo by mutableStateOf(false)
    var showArtist by mutableStateOf(true)
    var showAlbum by mutableStateOf(true)
    var shielded by mutableStateOf(setOf<String>())
    var shieldInside by mutableStateOf<String?>(null)

    val filtered: List<LocalTrack>
        get() = filterTracks(
            tracks.filter { !folderBlocks(it, shielded) },
            tagFile,
            PlaylistFilter(include, includeMode, exclude, query),
        )

    fun folderBrowse() = browseFolders(tracks, shieldInside, shielded)

    private var repository: TagRepository? = null
    private var tagStamp = Long.MIN_VALUE
    private var disposed = false
    private var errorSkips = 0

    private val player = DesktopPlayer(object : PlayerListener {
        override fun onProgress(positionMs: Long, durationMs: Long) {
            SwingUtilities.invokeLater {
                this@AppModel.positionMs = positionMs
                if (durationMs > 0) this@AppModel.durationMs = durationMs
            }
        }

        override fun onPlayingChanged(playing: Boolean) {
            SwingUtilities.invokeLater {
                isPlaying = playing
                if (playing) errorSkips = 0
            }
        }

        override fun onEnded() {
            SwingUtilities.invokeLater { advanceAuto() }
        }

        override fun onError(message: String) {
            SwingUtilities.invokeLater { skipAfterError(message) }
        }
    })

    init {
        shielded = shieldStore.load()
        loadDisplayPrefs()
        val saved = loadLibraryPath()
        val music = File(System.getProperty("user.home"), "Music")
        when {
            saved != null && saved.isDirectory -> openFolder(saved)
            music.isDirectory -> openFolder(music)
        }
        startReloadLoop()
    }

    fun scanLocalMusic() {
        val music = File(System.getProperty("user.home"), "Music")
        if (!music.isDirectory) {
            status = "没有找到本机的「音乐」文件夹"
            return
        }
        openFolder(music)
    }

    fun enterShieldFolder(path: String) {
        if (!path.startsWith("=")) shieldInside = path
    }

    fun shieldUp() {
        val current = shieldInside ?: return
        shieldInside = folderParent(current)
    }

    fun toggleShield(path: String) {
        shielded = toggleFolderShield(shielded, path)
        shieldStore.save(shielded)
    }

    fun changeShowArtist(value: Boolean) {
        showArtist = value
        saveDisplayPref("showArtist", value)
    }

    fun changeShowAlbum(value: Boolean) {
        showAlbum = value
        saveDisplayPref("showAlbum", value)
    }

    fun chooseFolder() {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "选择音乐文件夹"
        libraryRoot?.let { chooser.currentDirectory = it }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            openFolder(chooser.selectedFile)
        }
    }

    fun openFolder(folder: File) {
        libraryRoot = folder
        saveLibraryPath(folder)
        repository = TagRepository(FileTagIo(File(folder, "tags.json")))
        tagStamp = Long.MIN_VALUE
        reloadTags()
        rescan()
    }

    fun rescan() {
        val root = libraryRoot ?: return
        if (scanning) return
        scanning = true
        status = "正在扫描…"
        Thread {
            try {
                val found = scanAudioFolder(root, loadScanCache(cacheFile), ::readDesktopMetadata) { done, total, name ->
                    SwingUtilities.invokeLater { status = "正在读取 $done / $total · $name" }
                }
                val now = System.currentTimeMillis()
                val base = tagFile.upsertMetadata(found, now)
                val cache = matchStore.load()
                fillOnlineMatches(found, base, cache, { track, artist, title ->
                    findOnlineMatch(track, artist, title, ::lookupTrackMatches)
                }, { Thread.sleep(250) }) { text ->
                    SwingUtilities.invokeLater { status = text }
                }
                matchStore.save(cache)
                val recognized = applyOnlineMatches(base, found, cache, now)
                val named = tracksWithConfirmedNames(found, cache)
                saveScanCache(cacheFile, named)
                SwingUtilities.invokeLater {
                    tracks = named
                    scanning = false
                    updateTags { recognized }
                    status = "曲库 ${named.size} 首。歌手、专辑、流派已写成标签。文件在 ${File(root, "tags.json").path}"
                }
            } catch (error: Exception) {
                SwingUtilities.invokeLater {
                    scanning = false
                    status = error.message ?: "扫描失败"
                }
            }
        }.apply {
            isDaemon = true
            name = "tagplayer-scan"
            start()
        }
    }

    fun reloadTags() {
        val repo = repository ?: return
        val stamp = repo.sourceStamp()
        if (stamp == tagStamp) return
        try {
            tagFile = repo.read()
            tagStamp = repo.sourceStamp()
        } catch (error: Exception) {
            status = error.message ?: "标签文件读不了"
        }
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
    }

    fun addInclude(raw: String) = addTo(raw, includeList = true)

    fun addExclude(raw: String) = addTo(raw, includeList = false)

    fun playFiltered() {
        val ids = filtered.map { it.contentId }
        if (ids.isEmpty()) {
            status = "没有符合的歌"
            return
        }
        errorSkips = 0
        queue = PlayQueue(shuffledQueue(ids))
        playCurrent()
    }

    fun playTrack(id: String) {
        val ids = filtered.map { it.contentId }
        if (id !in ids) return
        errorSkips = 0
        queue = PlayQueue(queueStartingWith(ids, id))
        playCurrent()
    }

    fun togglePause() {
        when {
            isPlaying -> {
                if (!player.pause()) status = "系统播放不支持暂停。安装 ffmpeg 后可以暂停和拖动。"
            }
            player.canResume() -> player.resume()
            queue.ids.isNotEmpty() -> playCurrent()
            else -> playFiltered()
        }
    }

    fun userNext() {
        errorSkips = 0
        advanceAuto()
    }

    fun userPrevious() {
        when (val step = previousTrack(queue, positionMs)) {
            PreviousStep.Empty -> Unit
            PreviousStep.Restart -> player.seek(0)
            is PreviousStep.Move -> {
                queue = step.queue
                playCurrent()
            }
        }
    }

    fun seekTo(position: Long) {
        player.seek(position)
    }

    fun toggleTag(id: String, tag: String) {
        val now = System.currentTimeMillis()
        updateTags { file ->
            val song = recordFor(file, id)
            val next = if (tag in effectiveTags(song)) removeTag(song, tag, now) else addTag(song, tag, now)
            file.withSong(id, next)
        }
    }

    fun addTag(id: String, raw: String) {
        val now = System.currentTimeMillis()
        updateTags { file ->
            file.withSong(id, addTag(recordFor(file, id), raw, now))
        }
    }

    fun addTagToSelected(raw: String) {
        val ids = selected
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        updateTags { file ->
            ids.fold(file) { current, id -> current.withSong(id, addTag(recordFor(current, id), raw, now)) }
        }
    }

    fun removeTagFromSelected(raw: String) {
        val ids = selected
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        updateTags { file ->
            ids.fold(file) { current, id -> current.withSong(id, removeTag(recordFor(current, id), raw, now)) }
        }
    }

    fun savePreset(name: String) {
        val filter = PlaylistFilter(include, includeMode, exclude)
        updateTags { it.upsertPreset(name, filter, System.currentTimeMillis()) }
    }

    fun applyPreset(id: String) {
        val preset = tagFile.presets.find { it.id == id } ?: return
        include = preset.include
        exclude = preset.exclude
        includeMode = preset.mode()
        status = "已用方案「${preset.name}」"
    }

    fun deletePreset(id: String) {
        updateTags { it.withoutPreset(id) }
    }

    fun toggleSelected(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun dispose() {
        disposed = true
        player.stop()
    }

    fun tagsOf(id: String): Set<String> = tagFile.songs[id]?.let(::effectiveTags) ?: emptySet()

    private fun advanceAuto() {
        when (val step = nextTrack(queue)) {
            QueueStep.Empty -> Unit
            QueueStep.RoundDone -> {
                if (queue.ids.isEmpty()) return
                queue = PlayQueue(shuffledQueue(queue.ids))
                status = "这一轮播完了，已重新洗牌"
                playCurrent()
            }
            is QueueStep.Move -> {
                queue = step.queue
                playCurrent()
            }
        }
    }

    private fun playCurrent() {
        val id = queue.currentId ?: return
        val file = tracks.find { it.contentId == id }?.path?.let(::File)
        if (file == null || !file.isFile) {
            skipAfterError("找不到文件")
            return
        }
        currentId = id
        positionMs = 0
        player.play(file, 0)
    }

    private fun skipAfterError(message: String) {
        status = message
        errorSkips += 1
        if (errorSkips > queue.ids.size.coerceAtLeast(1)) {
            status = "这一轮的文件都没法播放。$message"
            return
        }
        advanceAuto()
    }

    private fun recordFor(file: TagFile, id: String): SongRecord {
        val track = tracks.find { it.contentId == id }
        val current = file.songs[id] ?: SongRecord()
        return if (track == null) {
            current
        } else {
            current.withMetadata(track.title, track.artist, track.album, track.durationMs, track.size, System.currentTimeMillis())
        }
    }

    private fun addTo(raw: String, includeList: Boolean) {
        val tag = normalizeTag(raw) ?: return
        if (includeList) {
            exclude = exclude - tag
            include = (include + tag).distinct()
        } else {
            include = include - tag
            exclude = (exclude + tag).distinct()
        }
    }

    private fun updateTags(block: (TagFile) -> TagFile) {
        val repo = repository ?: run {
            status = "先选择音乐文件夹"
            return
        }
        try {
            tagFile = repo.update(block)
            tagStamp = repo.sourceStamp()
        } catch (error: Exception) {
            status = error.message ?: "标签没能保存"
        }
    }

    private fun startReloadLoop() {
        Thread {
            while (!disposed) {
                Thread.sleep(4_000)
                SwingUtilities.invokeLater {
                    if (!disposed) reloadTags()
                }
            }
        }.apply {
            isDaemon = true
            name = "tagplayer-tags"
            start()
        }
    }

    fun prepareCover(contentId: String) {
        coverTrackId = contentId
        coverProposal = null
        coverChoices = emptyList()
        coverNote = ""
        coverBusy = true
        Thread {
            val file = writableFile(contentId)
            val bytes = file?.let { runCatching { readEmbeddedCover(it)?.bytes }.getOrNull() }
            val undo = coverUndo.has(contentId)
            SwingUtilities.invokeLater {
                if (coverTrackId != contentId) return@invokeLater
                coverBytes = bytes
                coverCanUndo = undo
                coverBusy = false
                if (file == null) coverNote = "这个文件现在不能改。封面只在你点替换后才写入。"
            }
        }.apply { isDaemon = true; start() }
    }

    fun chooseCoverFile() {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择封面"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("图片", "jpg", "jpeg", "png")
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        val bytes = chooser.selectedFile.readBytes()
        if (!looksLikeCoverImage(bytes)) {
            coverNote = "请选一张不超过 8MB 的 JPEG 或 PNG"
            return
        }
        coverProposal = bytes
        coverNote = "先看这张图。点「替换封面」才会写进文件，之后可以撤销。"
    }

    fun findCoverOnline() {
        val id = coverTrackId ?: return
        val track = tracks.find { it.contentId == id } ?: return
        coverBusy = true
        coverChoices = emptyList()
        coverNote = "正在查找相近的封面…"
        Thread {
            val record = tagFile.songs[id]
            val (queryArtist, queryTitle) = recognitionQuery(track, displayArtist(track, record), displayTitle(track, record))
            val choices = runCatching {
                rankCoverChoices(lookupTrackMatches(queryArtist, queryTitle, limit = 8), queryArtist, queryTitle)
            }.getOrDefault(emptyList())
            SwingUtilities.invokeLater {
                if (coverTrackId != id) return@invokeLater
                coverBusy = false
                coverChoices = choices
                coverNote = if (choices.isEmpty()) "没有查到相近的封面" else "这些是相近结果。点一张，再决定要不要替换。"
            }
        }.apply { isDaemon = true; start() }
    }

    fun pickCoverChoice(index: Int) {
        val choice = coverChoices.getOrNull(index) ?: return
        coverBusy = true
        coverNote = "正在取「${choice.title}」的封面…"
        Thread {
            val image = runCatching { embeddedCoverAt(choice.artworkUrl, large = true) }.getOrNull()
            SwingUtilities.invokeLater {
                coverBusy = false
                if (image == null) {
                    coverNote = "这张图没能下载"
                } else {
                    coverProposal = image.bytes
                    coverNote = "先看这张图。点「替换封面」才会写进文件，之后可以撤销。"
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun confirmCover() {
        val id = coverTrackId ?: return
        val proposal = coverProposal ?: return
        val file = writableFile(id) ?: run {
            coverNote = "这个文件现在不能改"
            return
        }
        coverBusy = true
        Thread {
            val written = runCatching {
                replaceTrackCover(file, id, EmbeddedCover(sniffImageMime(proposal), proposal), coverUndo)
            }
            SwingUtilities.invokeLater {
                coverBusy = false
                written.onSuccess { newId ->
                    adoptNewId(id, newId, file)
                    if (coverTrackId == id || coverTrackId == newId) {
                        coverBytes = proposal
                        coverProposal = null
                        coverCanUndo = true
                        coverNote = "封面已写入文件。撤销只在这台电脑上。"
                    }
                }.onFailure { error ->
                    if (coverTrackId == id) coverNote = error.message ?: "封面没能写进文件"
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun undoCover() {
        val id = coverTrackId ?: return
        val file = writableFile(id) ?: run {
            coverNote = "这个文件现在不能改"
            return
        }
        coverBusy = true
        Thread {
            val restored = runCatching { undoTrackCover(file, id, coverUndo) }
            val bytes = restored.getOrNull()?.let { runCatching { readEmbeddedCover(file)?.bytes }.getOrNull() }
            SwingUtilities.invokeLater {
                coverBusy = false
                restored.onSuccess { newId ->
                    adoptNewId(id, newId, file)
                    if (coverTrackId == id || coverTrackId == newId) {
                        coverBytes = bytes
                        coverProposal = null
                        coverCanUndo = false
                        coverNote = "已恢复替换前的封面"
                    }
                }.onFailure { error ->
                    if (coverTrackId == id) coverNote = error.message ?: "没能撤销"
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun dismissCoverProposal() {
        coverProposal = null
    }

    fun lookupInfo(contentId: String) {
        infoTrackId = contentId
        infoBusy = true
        infoNote = "正在查找歌手、专辑和流派…"
        infoMatches = emptyList()
        infoPicked = -1
        val track = tracks.find { it.contentId == contentId }
        Thread {
            val file = writableFile(contentId)
            val matches = if (track == null) {
                emptyList()
            } else {
                val record = tagFile.songs[contentId]
                val (queryArtist, queryTitle) = recognitionQuery(track, displayArtist(track, record), displayTitle(track, record))
                runCatching { lookupTrackMatches(queryArtist, queryTitle) }.getOrDefault(emptyList())
            }
            val undo = textUndo.has(contentId)
            SwingUtilities.invokeLater {
                if (infoTrackId != contentId) return@invokeLater
                infoBusy = false
                infoMatches = matches
                infoCanUndo = undo
                infoNote = when {
                    file == null -> "这个文件现在不能改。上面仍是查到的结果，还不能写入。"
                    matches.isEmpty() -> "没有查到和这首歌对应的结果"
                    else -> "选一条结果，勾选要写入的项，再点写入文件。"
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun pickInfo(index: Int) {
        val match = infoMatches.getOrNull(index) ?: return
        infoPicked = index
        infoUseTitle = match.title.isNotBlank()
        infoUseArtist = match.artist.isNotBlank()
        infoUseAlbum = match.album.isNotBlank()
        infoUseGenre = match.genre.isNotBlank()
    }

    fun confirmInfo() {
        val id = infoTrackId ?: return
        val match = infoMatches.getOrNull(infoPicked) ?: run {
            infoNote = "先选一条结果"
            return
        }
        if (!infoUseTitle && !infoUseArtist && !infoUseAlbum && !infoUseGenre) {
            infoNote = "先勾选要写入的项"
            return
        }
        val file = writableFile(id) ?: run {
            infoNote = "这个文件现在不能改"
            return
        }
        val useTitle = infoUseTitle
        val useArtist = infoUseArtist
        val useAlbum = infoUseAlbum
        val useGenre = infoUseGenre
        infoBusy = true
        Thread {
            val written = runCatching {
                val current = runCatching { readEmbeddedText(file) }.getOrDefault(EmbeddedText())
                val next = current.applying(match, useTitle, useArtist, useAlbum, useGenre)
                next to replaceTrackText(file, id, next, textUndo)
            }
            SwingUtilities.invokeLater {
                infoBusy = false
                written.onSuccess { (next, newId) ->
                    adoptNewId(id, newId, file, next, lockNames = useTitle && useArtist)
                    if (infoTrackId == id || infoTrackId == newId) {
                        infoTrackId = newId
                        infoMatches = emptyList()
                        infoPicked = -1
                        infoCanUndo = true
                        infoNote = "已写入文件。撤销只在这台电脑上。"
                    }
                }.onFailure { error ->
                    if (infoTrackId == id) infoNote = error.message ?: "歌曲信息没能写进文件"
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun undoInfo() {
        val id = infoTrackId ?: return
        val file = writableFile(id) ?: run {
            infoNote = "这个文件现在不能改"
            return
        }
        infoBusy = true
        Thread {
            val restored = runCatching { undoTrackText(file, id, textUndo) }
            val text = restored.getOrNull()?.let { runCatching { readEmbeddedText(file) }.getOrNull() } ?: EmbeddedText()
            SwingUtilities.invokeLater {
                infoBusy = false
                restored.onSuccess { newId ->
                    adoptNewId(id, newId, file, text, clearNames = true)
                    if (infoTrackId == id || infoTrackId == newId) {
                        infoTrackId = newId
                        infoMatches = emptyList()
                        infoPicked = -1
                        infoCanUndo = false
                        infoNote = "已恢复写入前的歌手、专辑和流派"
                    }
                }.onFailure { error ->
                    if (infoTrackId == id) infoNote = error.message ?: "没能撤销"
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun adoptNewId(
        oldId: String,
        newId: String,
        file: File,
        text: EmbeddedText? = null,
        lockNames: Boolean = false,
        clearNames: Boolean = false,
    ) {
        coverUndo.move(oldId, newId)
        textUndo.move(oldId, newId)
        if (oldId == newId && text == null) return
        tracks = tracks.map { track ->
            if (track.contentId != oldId) track else track.copy(
                contentId = newId,
                size = file.length(),
                modified = file.lastModified(),
                title = text?.title?.ifBlank { track.title } ?: track.title,
                artist = text?.artist?.ifBlank { track.artist } ?: track.artist,
                album = text?.album?.ifBlank { track.album } ?: track.album,
            )
        }
        if (oldId != newId) {
            queue = queue.withIdReplaced(oldId, newId)
            if (currentId == oldId) currentId = newId
            if (coverTrackId == oldId) coverTrackId = newId
            if (infoTrackId == oldId) infoTrackId = newId
        }
        saveScanCache(cacheFile, tracks)
        updateTags { tags ->
            val moved = if (oldId == newId) tags else tags.moveSong(oldId, newId)
            if (text == null) {
                moved
            } else {
                val song = moved.songs[newId] ?: SongRecord()
                val duration = tracks.find { it.contentId == newId }?.durationMs ?: song.durationMs
                moved.withSong(
                    newId,
                    song.withMetadata(
                        text.title,
                        text.artist,
                        text.album,
                        duration,
                        file.length(),
                        System.currentTimeMillis(),
                        lockNames = lockNames,
                        clearNames = clearNames,
                    ),
                )
            }
        }
    }

    private fun writableFile(contentId: String): File? {
        val path = tracks.find { it.contentId == contentId }?.path ?: return null
        val file = File(path)
        return file.takeIf { it.isFile && it.canWrite() }
    }

    private fun loadLibraryPath(): File? {
        if (!settingsFile.isFile) return null
        val properties = Properties()
        settingsFile.inputStream().use { properties.load(it) }
        return properties.getProperty("library")?.let(::File)
    }

    private fun saveLibraryPath(folder: File) {
        settingsFile.parentFile?.mkdirs()
        val properties = Properties()
        if (settingsFile.isFile) settingsFile.inputStream().use { properties.load(it) }
        properties.setProperty("library", folder.absolutePath)
        settingsFile.outputStream().use { properties.store(it, "tagplayer") }
    }

    private fun loadDisplayPrefs() {
        if (!settingsFile.isFile) return
        val properties = Properties()
        settingsFile.inputStream().use { properties.load(it) }
        showArtist = properties.getProperty("showArtist", "true") != "false"
        showAlbum = properties.getProperty("showAlbum", "true") != "false"
    }

    private fun saveDisplayPref(key: String, value: Boolean) {
        settingsFile.parentFile?.mkdirs()
        val properties = Properties()
        if (settingsFile.isFile) settingsFile.inputStream().use { properties.load(it) }
        properties.setProperty(key, value.toString())
        settingsFile.outputStream().use { properties.store(it, "tagplayer") }
    }
}

fun playbackHint(): String = if (findExecutable("ffmpeg") != null) {
    "使用 ffmpeg 播放，MP3、FLAC、APE、ALAC、WMA 等都可以。"
} else {
    "还没有 ffmpeg：WAV 可以暂停和拖动，MP3 只能整首播放。安装命令：brew install ffmpeg"
}
