package app.tagplayer.desktop

import app.tagplayer.core.TrackMetadata
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

fun readDesktopMetadata(file: File): TrackMetadata {
    Logger.getLogger("org.jaudiotagger").level = Level.OFF
    return try {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag
        TrackMetadata(
            title = tag?.getFirst(FieldKey.TITLE).orEmpty(),
            artist = tag?.getFirst(FieldKey.ARTIST).orEmpty(),
            album = tag?.getFirst(FieldKey.ALBUM).orEmpty(),
            durationMs = audio.audioHeader?.trackLength?.times(1000L) ?: 0L,
        )
    } catch (_: Exception) {
        TrackMetadata()
    }
}
