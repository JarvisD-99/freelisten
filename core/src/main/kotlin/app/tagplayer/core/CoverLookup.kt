package app.tagplayer.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CoverCandidate(
    val artist: String,
    val title: String,
    val artworkUrl: String,
)

fun chooseCoverUrl(candidates: List<CoverCandidate>, artist: String, title: String): String? {
    val wantedTitle = title.trim()
    if (wantedTitle.isEmpty()) return null
    val wantedArtist = artist.trim()
    val hit = candidates.firstOrNull { candidate ->
        val titleMatches = candidate.title.contains(wantedTitle, ignoreCase = true) ||
            wantedTitle.contains(candidate.title, ignoreCase = true)
        val artistMatches = wantedArtist.isEmpty() ||
            candidate.artist.contains(wantedArtist, ignoreCase = true) ||
            wantedArtist.contains(candidate.artist, ignoreCase = true)
        titleMatches && artistMatches && candidate.artworkUrl.isNotBlank()
    } ?: return null
    return hit.artworkUrl.replace("100x100bb", "600x600bb")
}

fun lookupOnlineCover(artist: String, title: String): EmbeddedCover? {
    val url = chooseCoverUrl(searchItunes(artist, title), artist, title) ?: return null
    return embeddedCoverAt(url, large = true)
}

data class CoverChoice(
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
)

/** 相近的封面都留下，按接近程度排序，交给用户选。没有图的结果不列出。 */
fun rankCoverChoices(matches: List<TrackMatch>, artist: String, title: String): List<CoverChoice> {
    return matches
        .filter { it.artworkUrl.isNotBlank() && it.title.isNotBlank() }
        .sortedByDescending { nameLikeness(title, it.title) * 10 + nameLikeness(artist, it.artist) }
        .map { CoverChoice(it.title, it.artist, it.album, it.artworkUrl) }
}

fun embeddedCoverAt(url: String, large: Boolean): EmbeddedCover? {
    val sized = if (large) url.replace("100x100bb", "600x600bb") else url.replace("600x600bb", "100x100bb")
    val bytes = downloadBytes(sized)
    if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES || !looksLikeCoverImage(bytes)) return null
    return EmbeddedCover(sniffImageMime(bytes), bytes)
}

fun nameLikeness(wanted: String, candidate: String): Int {
    val left = wanted.trim().lowercase()
    val right = candidate.trim().lowercase()
    if (left.isEmpty() || right.isEmpty()) return 0
    if (left == right) return 100
    if (right.contains(left) || left.contains(right)) return 80
    val common = longestCommonLength(left, right)
    if (common < 2) return 0
    return (common * 60 / maxOf(left.length, right.length)).coerceAtMost(60)
}

private fun longestCommonLength(left: String, right: String): Int {
    var best = 0
    for (start in left.indices) {
        for (end in (start + 1)..left.length) {
            if (end - start <= best) continue
            if (right.contains(left.substring(start, end))) best = end - start
        }
    }
    return best
}

data class TrackMatch(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val artworkUrl: String = "",
    val releaseDate: String = "",
)

fun parseItunesMatches(body: String): List<TrackMatch> = try {
    val results = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return emptyList()
    results.mapNotNull { element ->
        val item = element.jsonObject
        val title = item["trackName"]?.jsonPrimitive?.content.orEmpty().trim()
        if (title.isEmpty()) return@mapNotNull null
        TrackMatch(
            title = title,
            artist = item["artistName"]?.jsonPrimitive?.content.orEmpty().trim(),
            album = item["collectionName"]?.jsonPrimitive?.content.orEmpty().trim(),
            genre = item["primaryGenreName"]?.jsonPrimitive?.content.orEmpty().trim(),
            artworkUrl = item["artworkUrl100"]?.jsonPrimitive?.content.orEmpty().trim(),
            releaseDate = releaseDay(item["releaseDate"]?.jsonPrimitive?.content.orEmpty()),
        )
    }
} catch (_: Exception) {
    emptyList()
}

fun releaseDay(raw: String): String {
    val day = raw.trim().take(10)
    return if (day.length == 10 && day[4] == '-' && day[7] == '-') day else ""
}

fun chooseTrackMatch(matches: List<TrackMatch>, artist: String, title: String): TrackMatch? =
    bestScoredMatch(matches.mapNotNull { match -> scoreMatch(match, artist, title)?.let { match to it } })

/**
 * 文件名两边都可能是歌名或歌手。两种顺序都打分，取得分更高的那条。
 * 同分时保留「左边是歌手」这一侧，和原来的文件名习惯一致。
 */
fun chooseEitherOrder(matches: List<TrackMatch>, left: String, right: String): TrackMatch? {
    val straight = matches.mapNotNull { match -> scoreMatch(match, left, right)?.let { match to it } }
    val swapped = matches.mapNotNull { match -> scoreMatch(match, right, left)?.let { match to it } }
    return bestScoredMatch(straight + swapped)
}

private fun bestScoredMatch(scored: List<Pair<TrackMatch, Int>>): TrackMatch? =
    scored.maxByOrNull { it.second }?.first

private fun scoreMatch(match: TrackMatch, artist: String, title: String): Int? {
    val wantedTitle = title.trim().lowercase()
    if (wantedTitle.isEmpty()) return null
    val candidateTitle = match.title.trim().lowercase()
    val titleScore = when {
        candidateTitle == wantedTitle -> 3
        candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle) -> 2
        else -> 0
    }
    if (titleScore == 0) return null
    val wantedArtist = artist.trim().lowercase()
    val candidateArtist = match.artist.trim().lowercase()
    val artistScore = if (
        wantedArtist.isNotEmpty() &&
        (candidateArtist == wantedArtist || candidateArtist.contains(wantedArtist) || wantedArtist.contains(candidateArtist))
    ) 1 else 0
    return titleScore * 10 + artistScore
}

fun lookupTrackMatches(artist: String, title: String, limit: Int = 5): List<TrackMatch> {
    if (title.isBlank()) return emptyList()
    val capped = limit.coerceIn(1, 10)
    val term = URLEncoder.encode("${artist.trim()} ${title.trim()}".trim(), Charsets.UTF_8)
    val text = httpGet("https://itunes.apple.com/search?term=$term&entity=song&limit=$capped") ?: return emptyList()
    return parseItunesMatches(text).take(capped)
}

private fun searchItunes(artist: String, title: String): List<CoverCandidate> =
    lookupTrackMatches(artist, title).mapNotNull { match ->
        match.artworkUrl.takeIf { it.isNotBlank() }?.let { CoverCandidate(match.artist, match.title, it) }
    }

private fun httpGet(url: String): String? {
    val connection = open(url) ?: return null
    return try {
        if (connection.responseCode !in 200..299) null else connection.inputStream.bufferedReader().readText()
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun downloadBytes(url: String): ByteArray {
    val connection = open(url) ?: return ByteArray(0)
    return try {
        if (connection.responseCode !in 200..299) return ByteArray(0)
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            while (output.size() <= MAX_COVER_BYTES) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            if (output.size() > MAX_COVER_BYTES) ByteArray(0) else output.toByteArray()
        }
    } catch (_: Exception) {
        ByteArray(0)
    } finally {
        connection.disconnect()
    }
}

private fun open(url: String): HttpURLConnection? = try {
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Suibo/0.1")
    }
} catch (_: Exception) {
    null
}
