package app.tagplayer.core

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class TextUndoRecord(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
)

class TextUndoStore(private val root: File) {
    fun has(contentId: String): Boolean = recordFile(contentId).isFile

    fun save(contentId: String, previous: EmbeddedText) {
        val folder = File(root, contentId)
        folder.mkdirs()
        File(folder, "record.json").writeText(
            TagCodec.json.encodeToString(TextUndoRecord.serializer(), previous.toRecord()),
        )
    }

    fun load(contentId: String): EmbeddedText? {
        val file = recordFile(contentId)
        if (!file.isFile) return null
        return try {
            TagCodec.json.decodeFromString(TextUndoRecord.serializer(), file.readText()).toText()
        } catch (_: Exception) {
            null
        }
    }

    fun move(fromId: String, toId: String) {
        if (fromId == toId || !has(fromId)) return
        val source = File(root, fromId)
        val target = File(root, toId)
        target.deleteRecursively()
        if (!source.renameTo(target)) {
            source.copyRecursively(target, overwrite = true)
            source.deleteRecursively()
        }
    }

    fun delete(contentId: String) {
        File(root, contentId).deleteRecursively()
    }

    private fun recordFile(contentId: String) = File(root, "$contentId/record.json")

    private fun EmbeddedText.toRecord() = TextUndoRecord(title, artist, album, genre)

    private fun TextUndoRecord.toText() = EmbeddedText(title, artist, album, genre)
}
