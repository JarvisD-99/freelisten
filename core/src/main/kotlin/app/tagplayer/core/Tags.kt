package app.tagplayer.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

object TagCodec {
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(file: TagFile): String = json.encodeToString(TagFile.serializer(), normalized(file))

    fun decode(text: String): TagFile = try {
        json.decodeFromString(TagFile.serializer(), text)
    } catch (error: SerializationException) {
        throw TagFileException("标签文件损坏，没有覆盖它", error)
    } catch (error: IllegalArgumentException) {
        throw TagFileException("标签文件损坏，没有覆盖它", error)
    }
}

class TagFileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

fun normalizeTag(raw: String): String? {
    val tag = raw.trim().replace(Regex("\\s+"), " ")
    if (tag.isEmpty() || tag.length > 30) return null
    if (tag.any { it == '\n' || it == '\r' }) return null
    return tag
}

fun effectiveTags(song: SongRecord): Set<String> = song.tags
    .filter { (name, addedAt) -> addedAt > (song.removedTags[name] ?: Long.MIN_VALUE) }
    .keys

fun addTag(song: SongRecord, raw: String, now: Long): SongRecord {
    val tag = normalizeTag(raw) ?: return song
    return song.copy(tags = song.tags + (tag to now))
}

fun removeTag(song: SongRecord, raw: String, now: Long): SongRecord {
    val tag = normalizeTag(raw) ?: return song
    return song.copy(removedTags = song.removedTags + (tag to now))
}

fun SongRecord.withMetadata(
    title: String,
    artist: String,
    album: String,
    durationMs: Long,
    size: Long,
    now: Long,
): SongRecord {
    val newTitle = title.ifBlank { this.title }
    val newArtist = artist.ifBlank { this.artist }
    val newAlbum = album.ifBlank { this.album }
    val newDuration = if (durationMs > 0) durationMs else this.durationMs
    val newSize = if (size > 0) size else this.size
    if (
        newTitle == this.title &&
        newArtist == this.artist &&
        newAlbum == this.album &&
        newDuration == this.durationMs &&
        newSize == this.size
    ) {
        return this
    }
    return copy(
        title = newTitle,
        artist = newArtist,
        album = newAlbum,
        durationMs = newDuration,
        size = newSize,
        metaUpdatedAt = now,
    )
}

fun TagFile.withSong(id: String, record: SongRecord): TagFile = copy(songs = songs + (id to record))

fun TagFile.upsertMetadata(tracks: List<LocalTrack>, now: Long): TagFile {
    var current = this
    for (track in tracks) {
        val existing = current.songs[track.contentId] ?: SongRecord()
        val updated = existing.withMetadata(track.title, track.artist, track.album, track.durationMs, track.size, now)
        if (updated != existing) current = current.withSong(track.contentId, updated)
    }
    return current
}

fun TagFile.upsertPreset(name: String, filter: PlaylistFilter, now: Long): TagFile {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return this
    val existing = presets.find { it.name == trimmed }
    val preset = Preset(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = trimmed,
        include = filter.include.mapNotNull(::normalizeTag).distinct(),
        includeMode = filter.includeMode.name.lowercase(),
        exclude = filter.exclude.mapNotNull(::normalizeTag).distinct(),
        updatedAt = now,
    )
    return copy(presets = presets.filterNot { it.name == trimmed } + preset)
}

fun TagFile.withoutPreset(id: String): TagFile = copy(presets = presets.filterNot { it.id == id })

fun mergeSong(left: SongRecord, right: SongRecord): SongRecord {
    val tags = mergeTimes(left.tags, right.tags)
    val removed = mergeTimes(left.removedTags, right.removedTags)
    val meta = if (right.metaUpdatedAt >= left.metaUpdatedAt) right else left
    return meta.copy(tags = tags, removedTags = removed)
}

fun mergeTagFiles(left: TagFile, right: TagFile): TagFile {
    val songIds = left.songs.keys + right.songs.keys
    val songs = songIds.associateWith { id ->
        val a = left.songs[id]
        val b = right.songs[id]
        when {
            a == null -> b!!
            b == null -> a
            else -> mergeSong(a, b)
        }
    }
    val presets = (left.presets + right.presets)
        .groupBy { it.id }
        .map { (_, versions) -> versions.maxBy { it.updatedAt } }
        .groupBy { it.name }
        .map { (_, versions) -> versions.maxBy { it.updatedAt } }
    return TagFile(
        version = maxOf(left.version, right.version),
        songs = songs,
        presets = presets,
    )
}

fun tagCounts(file: TagFile): List<Pair<String, Int>> {
    val counts = linkedMapOf<String, Int>()
    for (song in file.songs.values) {
        for (tag in effectiveTags(song)) {
            counts[tag] = (counts[tag] ?: 0) + 1
        }
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key to it.value }
}

fun normalized(file: TagFile): TagFile = file.copy(
    songs = file.songs.toSortedMap().mapValues { (_, song) ->
        song.copy(
            tags = song.tags.toSortedMap(),
            removedTags = song.removedTags.toSortedMap(),
        )
    },
    presets = file.presets.sortedWith(compareBy({ it.name }, { it.id })),
)

private fun mergeTimes(left: Map<String, Long>, right: Map<String, Long>): Map<String, Long> {
    val names = left.keys + right.keys
    return names.associateWith { name ->
        maxOf(left[name] ?: Long.MIN_VALUE, right[name] ?: Long.MIN_VALUE)
    }
}
