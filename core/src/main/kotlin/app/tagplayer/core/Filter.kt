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
        if (query.isEmpty()) return@filter true
        val haystack = listOf(track.title, track.artist, track.album, record?.title, record?.artist, record?.album)
            .filterNotNull()
            .joinToString(" ")
        haystack.contains(query, ignoreCase = true) || tags.any { it.contains(query, ignoreCase = true) }
    }
}

fun displayTitle(track: LocalTrack, record: SongRecord?): String {
    return resolveTrackNames(
        track.title.ifBlank { record?.title.orEmpty() },
        track.artist.ifBlank { record?.artist.orEmpty() },
        trackFileName(track),
    ).first
}

fun displayArtist(track: LocalTrack, record: SongRecord?): String {
    return resolveTrackNames(
        track.title.ifBlank { record?.title.orEmpty() },
        track.artist.ifBlank { record?.artist.orEmpty() },
        trackFileName(track),
    ).second
}
