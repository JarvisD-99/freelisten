package app.tagplayer.core

import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

val AUDIO_EXTENSIONS: Set<String> = setOf(
    "mp3", "flac", "wav", "wave", "ogg", "oga", "opus",
    "m4a", "aac", "mp4", "m4b", "alac",
    "ape", "aiff", "aif", "wma", "wv", "mka",
    "dsf", "dff", "caf", "mpc", "tta", "ac3", "tak", "spx",
)

fun isAudioFileName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in AUDIO_EXTENSIONS
}

fun shouldSkipFileName(name: String): Boolean = name.startsWith(".") || ".sync-conflict-" in name

fun artistAndTitleFromFileName(fileName: String): Pair<String, String> {
    val base = fileName.substringBeforeLast('.', fileName).trim()
    val split = splitArtistAndTitle(base)
    return if (split != null) split else "" to base
}

/**
 * 文件名按「歌手 - 歌名」切开。标签里的歌名如果其实是歌手，就改用文件名里的歌名。
 * 很多手机读出来的标题字段写的是歌手，不这样处理列表就会整列都是人名。
 */
fun resolveTrackNames(metaTitle: String, metaArtist: String, fileName: String): Pair<String, String> {
    val (artistFromName, titleFromName) = artistAndTitleFromFileName(fileName)
    val named = artistFromName.isNotBlank() && titleFromName.isNotBlank()
    val title = when {
        named && (metaTitle.isBlank() || metaTitle == metaArtist || metaTitle == artistFromName) -> titleFromName
        metaTitle.isNotBlank() -> metaTitle
        else -> titleFromName
    }.ifBlank { "未命名" }
    val artist = when {
        named && (metaArtist.isBlank() || metaArtist == title || metaArtist == titleFromName) -> artistFromName
        metaArtist.isNotBlank() -> metaArtist
        else -> artistFromName
    }
    return title to artist
}

fun trackFileName(track: LocalTrack): String {
    val raw = track.path.ifBlank { track.uri }
    val decoded = try {
        java.net.URLDecoder.decode(raw, Charsets.UTF_8)
    } catch (_: Exception) {
        raw
    }
    return decoded.substringAfterLast('/').substringAfterLast(':')
}

private fun splitArtistAndTitle(base: String): Pair<String, String>? {
    val separators = listOf(" - ", " – ", " — ", "－")
    for (separator in separators) {
        val index = base.indexOf(separator)
        if (index <= 0 || index + separator.length >= base.length) continue
        val artist = base.substring(0, index).trim()
        val title = base.substring(index + separator.length).trim()
        if (artist.isNotEmpty() && title.isNotEmpty()) return artist to title
    }
    return null
}

fun buildLocalTrack(
    fileName: String,
    path: String,
    uri: String,
    size: Long,
    modified: Long,
    contentId: String,
    metadata: TrackMetadata,
): LocalTrack {
    val (title, artist) = resolveTrackNames(metadata.title, metadata.artist, fileName)
    return LocalTrack(
        contentId = contentId,
        title = title,
        artist = artist,
        album = metadata.album,
        durationMs = metadata.durationMs,
        size = size,
        modified = modified,
        path = path,
        uri = uri,
    )
}

fun dedupeTracks(tracks: List<LocalTrack>): List<LocalTrack> {
    val map = linkedMapOf<String, LocalTrack>()
    for (track in tracks) {
        val existing = map[track.contentId]
        if (existing == null || (existing.path.isBlank() && track.path.isNotBlank())) {
            map[track.contentId] = track
        }
    }
    return map.values.toList()
}

fun listAudioFiles(root: File): List<File> {
    if (!root.isDirectory) return emptyList()
    val found = ArrayList<File>()
    val seen = HashSet<String>()
    fun walk(directory: File) {
        val key = try {
            directory.canonicalPath
        } catch (_: IOException) {
            directory.absolutePath
        }
        if (!seen.add(key)) return
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (shouldSkipFileName(child.name)) continue
            when {
                child.isDirectory -> walk(child)
                child.isFile && isAudioFileName(child.name) && child.length() > 0L -> found += child
            }
        }
    }
    walk(root)
    return found.sortedBy { it.absolutePath.lowercase() }
}

fun trackFromFile(file: File, metadata: TrackMetadata): LocalTrack = buildLocalTrack(
    fileName = file.name,
    path = file.absolutePath,
    uri = file.toURI().toString(),
    size = file.length(),
    modified = file.lastModified(),
    contentId = contentIdOfFile(file),
    metadata = metadata,
)

fun scanAudioFolder(
    root: File,
    cache: Map<String, LocalTrack>,
    metadataOf: (File) -> TrackMetadata,
    onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
): List<LocalTrack> {
    val files = listAudioFiles(root)
    val tracks = ArrayList<LocalTrack>(files.size)
    files.forEachIndexed { index, file ->
        if (index % 20 == 0 || index == files.lastIndex) onProgress(index + 1, files.size, file.name)
        val key = cacheKey(file.absolutePath, file.length(), file.lastModified())
        val cached = cache[key]
        tracks += if (cached != null && cached.contentId.isNotBlank()) {
            cached.copy(path = file.absolutePath, uri = file.toURI().toString())
        } else {
            val metadata = try {
                metadataOf(file)
            } catch (_: Exception) {
                TrackMetadata()
            }
            trackFromFile(file, metadata)
        }
    }
    return dedupeTracks(tracks)
}

@Serializable
data class ScanCache(val tracks: List<LocalTrack> = emptyList())

fun cacheKey(locator: String, size: Long, modified: Long): String = "$locator\u0000$size\u0000$modified"

fun cacheKey(track: LocalTrack): String = cacheKey(track.path.ifBlank { track.uri }, track.size, track.modified)

fun loadScanCache(file: File): Map<String, LocalTrack> {
    if (!file.isFile) return emptyMap()
    return try {
        TagCodec.json.decodeFromString(ScanCache.serializer(), file.readText(Charsets.UTF_8))
            .tracks
            .associateBy(::cacheKey)
    } catch (_: Exception) {
        emptyMap()
    }
}

fun saveScanCache(file: File, tracks: List<LocalTrack>) {
    atomicWrite(file, TagCodec.json.encodeToString(ScanCache.serializer(), ScanCache(tracks)))
}
