package app.tagplayer.core

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CoverUndoRecord(
    val hadArtwork: Boolean,
    val mime: String = "",
)

sealed interface SavedCover {
    data object Missing : SavedCover
    data object Empty : SavedCover
    data class Image(val cover: EmbeddedCover) : SavedCover
}

class CoverUndoStore(private val root: File) {
    fun has(contentId: String): Boolean = recordFile(contentId).isFile

    fun save(contentId: String, previous: EmbeddedCover?) {
        val folder = folder(contentId)
        folder.mkdirs()
        val image = File(folder, "image.bin")
        if (previous == null) {
            image.delete()
            writeRecord(contentId, CoverUndoRecord(hadArtwork = false))
        } else {
            image.writeBytes(previous.bytes)
            writeRecord(contentId, CoverUndoRecord(hadArtwork = true, mime = previous.mime))
        }
    }

    fun load(contentId: String): SavedCover {
        val record = readRecord(contentId) ?: return SavedCover.Missing
        if (!record.hadArtwork) return SavedCover.Empty
        val bytes = File(folder(contentId), "image.bin").takeIf { it.isFile }?.readBytes()
            ?: return SavedCover.Empty
        return SavedCover.Image(EmbeddedCover(record.mime.ifBlank { sniffImageMime(bytes) }, bytes))
    }

    fun move(fromId: String, toId: String) {
        if (fromId == toId || !has(fromId)) return
        val source = folder(fromId)
        val target = folder(toId)
        target.deleteRecursively()
        if (!source.renameTo(target)) {
            source.copyRecursively(target, overwrite = true)
            source.deleteRecursively()
        }
    }

    fun delete(contentId: String) {
        folder(contentId).deleteRecursively()
    }

    private fun folder(contentId: String): File = File(root, contentId)

    private fun recordFile(contentId: String) = File(folder(contentId), "record.json")

    private fun writeRecord(contentId: String, record: CoverUndoRecord) {
        recordFile(contentId).writeText(TagCodec.json.encodeToString(CoverUndoRecord.serializer(), record))
    }

    private fun readRecord(contentId: String): CoverUndoRecord? {
        val file = recordFile(contentId)
        if (!file.isFile) return null
        return try {
            TagCodec.json.decodeFromString(CoverUndoRecord.serializer(), file.readText())
        } catch (_: Exception) {
            null
        }
    }
}
