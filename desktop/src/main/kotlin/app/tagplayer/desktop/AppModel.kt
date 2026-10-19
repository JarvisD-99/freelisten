package app.tagplayer.desktop

import app.tagplayer.core.FileTagIo
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

    val filtered: List<LocalTrack>
        get() = filterTracks(tracks, tagFile, PlaylistFilter(include, includeMode, exclude, query))

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
        val saved = loadLibraryPath()
        if (saved != null && saved.isDirectory) openFolder(saved)
        startReloadLoop()
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
                saveScanCache(cacheFile, found)
                SwingUtilities.invokeLater {
                    tracks = found
                    scanning = false
                    updateTags { it.upsertMetadata(found, System.currentTimeMillis()) }
                    status = "曲库 ${found.size} 首。标签写在 ${File(root, "tags.json").path}"
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
}

fun playbackHint(): String = if (findExecutable("ffmpeg") != null) {
    "使用 ffmpeg 播放，MP3、FLAC、APE、ALAC、WMA 等都可以。"
} else {
    "还没有 ffmpeg：WAV 可以暂停和拖动，MP3 只能整首播放。安装命令：brew install ffmpeg"
}
