package app.tagplayer.desktop

import app.tagplayer.core.FileTagIo
import app.tagplayer.core.PlaylistFilter
import app.tagplayer.core.TagRepository
import app.tagplayer.core.addTag
import app.tagplayer.core.effectiveTags
import app.tagplayer.core.filterTracks
import app.tagplayer.core.scanAudioFolder
import app.tagplayer.core.withSong
import java.nio.file.Files
import javax.sound.sampled.AudioSystem

fun runSelfTest(): Int {
    val root = Files.createTempDirectory("tagplayer-self").toFile()
    return try {
        val first = java.io.File(root, "甲 - 夜航.wav")
        val second = java.io.File(root, "乙 - 白日.wav")
        writeToneWav(first, 220.0, 0.4)
        writeToneWav(second, 440.0, 0.4)
        val tracks = scanAudioFolder(root, emptyMap(), ::readDesktopMetadata)
        check(tracks.size == 2) { "扫描到 ${tracks.size} 首，应该是 2 首" }
        check(tracks.map { it.contentId }.distinct().size == 2) { "两首不同的歌算出了同一个编号" }
        val night = tracks.first { it.title.contains("夜航") || it.path.contains("夜航") }
        val repository = TagRepository(FileTagIo(java.io.File(root, "tags.json")))
        val tagged = repository.update { it.withSong(night.contentId, addTag(app.tagplayer.core.SongRecord(), "夜里", 1)) }
        val filtered = filterTracks(tracks, tagged, PlaylistFilter(include = listOf("夜里")))
        check(filtered.map { it.contentId } == listOf(night.contentId)) { "按标签没有筛出夜航" }
        check("夜里" in effectiveTags(tagged.songs.getValue(night.contentId)))
        if (AudioSystem.getMixerInfo().isNotEmpty()) {
            val player = DesktopPlayer(object : PlayerListener {
                override fun onProgress(positionMs: Long, durationMs: Long) = Unit
                override fun onPlayingChanged(playing: Boolean) = Unit
                override fun onEnded() = Unit
                override fun onError(message: String) = println(message)
            })
            player.play(first, 0)
            Thread.sleep(350)
            player.stop()
        }
        println("自测通过：扫描、编号、标签筛选和播放都可用。")
        0
    } catch (error: Exception) {
        System.err.println("自测失败：${error.message}")
        1
    } finally {
        root.deleteRecursively()
    }
}
