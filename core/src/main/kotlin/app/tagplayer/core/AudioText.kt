package app.tagplayer.core

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

data class EmbeddedText(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
)

fun readEmbeddedText(file: File): EmbeddedText {
    prepareTagger()
    val tag = AudioFileIO.read(file).tag ?: return EmbeddedText()
    return EmbeddedText(
        title = tag.getFirst(FieldKey.TITLE).orEmpty(),
        artist = tag.getFirst(FieldKey.ARTIST).orEmpty(),
        album = tag.getFirst(FieldKey.ALBUM).orEmpty(),
        genre = tag.getFirst(FieldKey.GENRE).orEmpty(),
    )
}

fun writeEmbeddedText(file: File, text: EmbeddedText) {
    if (!file.isFile || !file.canWrite()) throw CoverEditException("这个文件现在不能改")
    prepareTagger()
    val audio = AudioFileIO.read(file)
    val tag = audio.tag ?: audio.createDefaultTag().also { audio.tag = it }
    putField(tag, FieldKey.TITLE, text.title)
    putField(tag, FieldKey.ARTIST, text.artist)
    putField(tag, FieldKey.ALBUM, text.album)
    putField(tag, FieldKey.GENRE, text.genre)
    audio.commit()
}

fun replaceTrackText(file: File, contentId: String, next: EmbeddedText, undo: TextUndoStore): String {
    val previous = try {
        readEmbeddedText(file)
    } catch (_: Exception) {
        EmbeddedText()
    }
    undo.save(contentId, previous)
    try {
        writeEmbeddedText(file, next)
    } catch (error: Exception) {
        undo.delete(contentId)
        throw if (error is CoverEditException) error else CoverEditException(error.message ?: "歌曲信息没能写进文件")
    }
    val newId = contentIdOfFile(file)
    undo.move(contentId, newId)
    return newId
}

fun undoTrackText(file: File, contentId: String, undo: TextUndoStore): String {
    val saved = undo.load(contentId) ?: throw CoverEditException("这次修改没有可撤销的记录")
    writeEmbeddedText(file, saved)
    undo.delete(contentId)
    return contentIdOfFile(file)
}

fun EmbeddedText.applying(match: TrackMatch, title: Boolean, artist: Boolean, album: Boolean, genre: Boolean): EmbeddedText =
    copy(
        title = if (title && match.title.isNotBlank()) match.title else this.title,
        artist = if (artist && match.artist.isNotBlank()) match.artist else this.artist,
        album = if (album && match.album.isNotBlank()) match.album else this.album,
        genre = if (genre && match.genre.isNotBlank()) match.genre else this.genre,
    )

private fun putField(tag: org.jaudiotagger.tag.Tag, key: FieldKey, value: String) {
    if (value.isBlank()) {
        try {
            tag.deleteField(key)
        } catch (_: Exception) {
        }
    } else {
        tag.setField(key, value)
    }
}
