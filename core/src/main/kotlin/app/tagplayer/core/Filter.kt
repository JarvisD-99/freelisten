package app.tagplayer.core

fun matchesTags(tags: Set<String>, filter: PlaylistFilter): Boolean {
    if (filter.exclude.any { it in tags }) return false
    if (filter.include.isEmpty()) return true
    return when (filter.includeMode) {
        IncludeMode.All -> filter.include.all { it in tags }
        IncludeMode.Any -> filter.include.any { it in tags }
    }
}

fun filterTracks(tracks: List<LocalTrack>, tagFile: TagFile, filter: PlaylistFilter): List<LocalTrack> {
    val query = filter.query.trim()
    return tracks.filter { track ->
        val record = tagFile.songs[track.contentId]
        val tags = record?.let(::effectiveTags) ?: emptySet()
        if (!matchesTags(tags, filter)) return@filter false
        if (filter.yearFrom != null || filter.yearTo != null) {
            val year = record?.releaseDate?.take(4)?.toIntOrNull()
            if (year == null) return@filter false
            if (filter.yearFrom != null && year < filter.yearFrom) return@filter false
            if (filter.yearTo != null && year > filter.yearTo) return@filter false
        }
        if (query.isEmpty()) return@filter true
        val haystack = listOf(track.title, track.artist, track.album, record?.title, record?.artist, record?.album)
            .filterNotNull()
            .joinToString(" ")
        haystack.contains(query, ignoreCase = true) || tags.any { it.contains(query, ignoreCase = true) }
    }
}

fun displayTitle(track: LocalTrack, record: SongRecord?): String {
    if (record?.recognizedNames == true && record.title.isNotBlank()) return record.title
    return resolveTrackNames(
        track.title.ifBlank { record?.title.orEmpty() },
        track.artist.ifBlank { record?.artist.orEmpty() },
        trackFileName(track),
    ).first
}

fun displayArtist(track: LocalTrack, record: SongRecord?): String {
    if (record?.recognizedNames == true) return record.artist
    return resolveTrackNames(
        track.title.ifBlank { record?.title.orEmpty() },
        track.artist.ifBlank { record?.artist.orEmpty() },
        trackFileName(track),
    ).second
}

fun displayAlbum(track: LocalTrack, record: SongRecord?): String = track.album.ifBlank { record?.album.orEmpty() }

fun listDetail(
    artist: String,
    album: String,
    tags: List<String>,
    showArtist: Boolean,
    showAlbum: Boolean,
): String {
    val name = listOfNotNull(
        artist.takeIf { showArtist && it.isNotBlank() },
        album.takeIf { showAlbum && it.isNotBlank() },
    ).joinToString(" - ")
    val tagText = tags.filter { it.isNotBlank() }.joinToString(" · ")
    return listOf(name, tagText).filter { it.isNotBlank() }.joinToString("    ")
}
