package app.tagplayer.core

import kotlinx.serialization.Serializable

/**
 * 手机和电脑共用的标签文件。
 *
 * 歌曲编号是 SHA-256 的十六进制。输入依次是：8 字节大端的文件长度、
 * 文件开头 64KB；文件大于 64KB 时，再加文件结尾 64KB。
 * 同一份音频不管放在哪个路径，编号都一样，标签就能对上。
 *
 * 每个标签的添加和删除各自记时间。两台设备改过同一首歌时，保留较新的那次操作。
 * 扫描曲库只补充歌名和歌手，不会因为这首歌不在当前设备上就把它的标签删掉。
 */
@Serializable
data class TagFile(
    val version: Int = 1,
    val songs: Map<String, SongRecord> = emptyMap(),
    val presets: List<Preset> = emptyList(),
)

@Serializable
data class SongRecord(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val size: Long = 0,
    val metaUpdatedAt: Long = 0,
    val tags: Map<String, Long> = emptyMap(),
    val removedTags: Map<String, Long> = emptyMap(),
)

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val include: List<String> = emptyList(),
    val includeMode: String = "all",
    val exclude: List<String> = emptyList(),
    val updatedAt: Long = 0,
)

@Serializable
data class LocalTrack(
    val contentId: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val size: Long = 0,
    val modified: Long = 0,
    val path: String = "",
    val uri: String = "",
)

data class TrackMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
)

enum class IncludeMode {
    All,
    Any,
    ;

    companion object {
        fun parse(raw: String): IncludeMode = if (raw.equals("any", ignoreCase = true)) Any else All
    }
}

data class PlaylistFilter(
    val include: List<String> = emptyList(),
    val includeMode: IncludeMode = IncludeMode.All,
    val exclude: List<String> = emptyList(),
    val query: String = "",
)

fun Preset.mode(): IncludeMode = IncludeMode.parse(includeMode)

fun Preset.toFilter(query: String = ""): PlaylistFilter = PlaylistFilter(
    include = include,
    includeMode = mode(),
    exclude = exclude,
    query = query,
)

fun formatClock(ms: Long): String {
    val safe = ms.coerceAtLeast(0) / 1000
    val seconds = safe % 60
    val minutes = (safe / 60) % 60
    val hours = safe / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
