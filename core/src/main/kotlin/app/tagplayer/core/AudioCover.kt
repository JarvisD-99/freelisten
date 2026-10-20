package app.tagplayer.core

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

data class EmbeddedCover(
    val mime: String,
    val bytes: ByteArray,
)

class CoverEditException(message: String) : RuntimeException(message)

fun readEmbeddedCover(file: File): EmbeddedCover? {
    prepareTagger()
    val tag = AudioFileIO.read(file).tag ?: return null
    val artwork = try {
        tag.firstArtwork
    } catch (_: Exception) {
        null
    } ?: return null
    val bytes = artwork.binaryData ?: return null
    if (bytes.isEmpty()) return null
    val mime = artwork.mimeType?.ifBlank { null } ?: sniffImageMime(bytes)
    return EmbeddedCover(mime, bytes)
}

fun writeEmbeddedCover(file: File, cover: EmbeddedCover) {
    if (!file.isFile || !file.canWrite()) throw CoverEditException("这个文件现在不能改")
    if (cover.bytes.isEmpty() || cover.bytes.size > MAX_COVER_BYTES) throw CoverEditException("这张封面太大或是空的")
    prepareTagger()
    val audio = AudioFileIO.read(file)
    val tag = audio.tag ?: audio.createDefaultTag().also { audio.tag = it }
    try {
        tag.deleteArtworkField()
    } catch (_: Exception) {
    }
    val artwork = ArtworkFactory.getNew()
    artwork.binaryData = cover.bytes
    artwork.mimeType = cover.mime.ifBlank { sniffImageMime(cover.bytes) }
    artwork.pictureType = 3
    try {
        artwork.setImageFromData()
    } catch (_: Exception) {
    }
    tag.setField(artwork)
    audio.commit()
}

fun replaceTrackCover(file: File, contentId: String, cover: EmbeddedCover, undo: CoverUndoStore): String {
    val previous = try {
        readEmbeddedCover(file)
    } catch (_: Exception) {
        null
    }
    undo.save(contentId, previous)
    try {
        writeEmbeddedCover(file, cover)
    } catch (error: Exception) {
        undo.delete(contentId)
        throw if (error is CoverEditException) error else CoverEditException(error.message ?: "封面没能写进文件")
    }
    val newId = contentIdOfFile(file)
    undo.move(contentId, newId)
    return newId
}

fun undoTrackCover(file: File, contentId: String, undo: CoverUndoStore): String {
    when (val saved = undo.load(contentId)) {
        SavedCover.Missing -> throw CoverEditException("这张封面没有可撤销的记录")
        SavedCover.Empty -> clearEmbeddedCover(file)
        is SavedCover.Image -> writeEmbeddedCover(file, saved.cover)
    }
    undo.delete(contentId)
    return contentIdOfFile(file)
}

fun clearEmbeddedCover(file: File) {
    if (!file.isFile || !file.canWrite()) throw CoverEditException("这个文件现在不能改")
    prepareTagger()
    val audio = AudioFileIO.read(file)
    val tag = audio.tag ?: return
    try {
        tag.deleteArtworkField()
    } catch (_: Exception) {
        return
    }
    audio.commit()
}

fun looksLikeCoverImage(bytes: ByteArray): Boolean {
    if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return false
    val jpeg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    val png = bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
    return jpeg || png
}

fun sniffImageMime(bytes: ByteArray): String = when {
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
    else -> "image/jpeg"
}

internal fun prepareTagger() {
    Logger.getLogger("org.jaudiotagger").level = Level.OFF
    val android = try {
        Class.forName("android.os.Build")
        true
    } catch (_: ClassNotFoundException) {
        false
    }
    if (android) TagOptionSingleton.getInstance().isAndroid = true
}

const val MAX_COVER_BYTES = 8 * 1024 * 1024
