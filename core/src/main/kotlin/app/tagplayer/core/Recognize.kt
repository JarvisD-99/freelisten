package app.tagplayer.core

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CachedMatch(
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val releaseDate: String = "",
    val title: String = "",
)

@Serializable
data class OnlineMatchFile(val songs: Map<String, CachedMatch> = emptyMap())

class OnlineMatchStore(private val file: File) {
    fun load(): MutableMap<String, CachedMatch> {
        if (!file.isFile) return linkedMapOf()
        return try {
            TagCodec.json.decodeFromString(OnlineMatchFile.serializer(), file.readText()).songs.toMutableMap()
        } catch (_: Exception) {
            linkedMapOf()
        }
    }

    fun save(songs: Map<String, CachedMatch>) {
        file.parentFile?.mkdirs()
        atomicWrite(file, TagCodec.json.encodeToString(OnlineMatchFile.serializer(), OnlineMatchFile(songs.toSortedMap())))
    }
}

fun SongRecord.ensureAutoTag(raw: String, now: Long): SongRecord {
    val tag = normalizeTag(raw) ?: return this
    if (tag in tags || tag in removedTags) return this
    return addTag(this, tag, now)
}

fun TagFile.applyOnlineMatch(contentId: String, match: CachedMatch, now: Long): TagFile {
    var song = songs[contentId] ?: SongRecord()
    song = song.ensureAutoTag(match.artist, now)
    song = song.ensureAutoTag(match.album, now)
    song = song.ensureAutoTag(match.genre, now)
    if (match.genre.isNotBlank() && song.genre.isBlank()) {
        song = song.copy(genre = match.genre, metaUpdatedAt = now)
    }
    if (match.releaseDate.isNotBlank() && song.releaseDate.isBlank()) {
        song = song.copy(releaseDate = match.releaseDate, metaUpdatedAt = now)
    }
    if (!song.recognizedNames && match.title.isNotBlank()) {
        val newArtist = match.artist.ifBlank { song.artist }
        song = if (song.title == match.title && song.artist == newArtist) {
            song.copy(recognizedNames = true)
        } else {
            song.copy(title = match.title, artist = newArtist, recognizedNames = true, metaUpdatedAt = now)
        }
    }
    return withSong(contentId, song)
}

fun recognitionQuery(track: LocalTrack, artist: String, title: String): Pair<String, String> {
    return filenameSides(trackFileName(track)) ?: (artist to title)
}

fun findOnlineMatch(
    track: LocalTrack,
    artist: String,
    title: String,
    search: (artist: String, title: String) -> List<TrackMatch>,
): TrackMatch? {
    val sides = filenameSides(trackFileName(track))
    if (sides != null) {
        val found = chooseEitherOrder(search(sides.first, sides.second), sides.first, sides.second)
        if (found != null) return found
        if (sameNamePair(sides, artist to title)) return null
    }
    return chooseTrackMatch(search(artist, title), artist, title)
}

fun tracksWithConfirmedNames(tracks: List<LocalTrack>, cache: Map<String, CachedMatch>): List<LocalTrack> {
    return tracks.map { track ->
        val match = cache[track.contentId] ?: return@map track
        if (match.title.isBlank()) track else track.copy(title = match.title, artist = match.artist.ifBlank { track.artist })
    }
}

private fun sameNamePair(left: Pair<String, String>, right: Pair<String, String>): Boolean {
    val a = setOf(left.first.trim().lowercase(), left.second.trim().lowercase())
    val b = setOf(right.first.trim().lowercase(), right.second.trim().lowercase())
    return a == b
}

fun fillOnlineMatches(
    tracks: List<LocalTrack>,
    tagFile: TagFile,
    cache: MutableMap<String, CachedMatch>,
    lookup: (track: LocalTrack, artist: String, title: String) -> TrackMatch?,
    pause: () -> Unit = {},
    onProgress: (String) -> Unit = {},
) {
    tracks.forEachIndexed { index, track ->
        val record = tagFile.songs[track.contentId]
        val title = displayTitle(track, record)
        onProgress("正在识别 ${index + 1} / ${tracks.size} · $title")
        val cached = cache[track.contentId]
        if (cached != null && cached.title.isNotBlank()) return@forEachIndexed
        val artist = displayArtist(track, record)
        val found = try {
            lookup(track, artist, title)
        } catch (_: Exception) {
            null
        }
        pause()
        if (found != null && found.title.isNotBlank()) {
            cache[track.contentId] = CachedMatch(found.artist, found.album, found.genre, releaseDay(found.releaseDate), found.title)
        }
    }
}

fun applyOnlineMatches(tagFile: TagFile, tracks: List<LocalTrack>, cache: Map<String, CachedMatch>, now: Long): TagFile {
    var current = tagFile
    for (track in tracks) {
        val match = cache[track.contentId] ?: continue
        current = current.applyOnlineMatch(track.contentId, match, now)
    }
    return current
}
