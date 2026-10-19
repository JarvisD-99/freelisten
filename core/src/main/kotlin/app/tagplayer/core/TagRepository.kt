package app.tagplayer.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ConflictCopy(val id: String, val text: String)

interface TagIo {
    fun readMain(): String?
    fun writeMain(text: String)
    fun readConflicts(): List<ConflictCopy>
    fun clearConflicts(ids: List<String>)
    fun sourceStamp(): Long
}

class FileTagIo(val file: File) : TagIo {
    private val prefix = file.name + ".sync-conflict-"

    override fun readMain(): String? = if (file.isFile) file.readText(Charsets.UTF_8) else null

    override fun writeMain(text: String) {
        atomicWrite(file, text)
    }

    override fun readConflicts(): List<ConflictCopy> = conflictFiles().map { conflict ->
        ConflictCopy(conflict.name, conflict.readText(Charsets.UTF_8))
    }

    override fun clearConflicts(ids: List<String>) {
        val wanted = ids.toSet()
        for (conflict in conflictFiles()) {
            if (conflict.name in wanted) conflict.delete()
        }
    }

    override fun sourceStamp(): Long {
        if (conflictFiles().isNotEmpty()) return Long.MAX_VALUE
        return if (file.isFile) file.lastModified() else 0L
    }

    private fun conflictFiles(): List<File> {
        val directory = file.parentFile ?: return emptyList()
        return directory.listFiles { candidate ->
            candidate.isFile && candidate.name.startsWith(prefix)
        }?.sortedBy { it.name } ?: emptyList()
    }
}

class TagRepository(private val io: TagIo) {
    @Synchronized
    fun read(): TagFile = readMerged()

    @Synchronized
    fun update(block: (TagFile) -> TagFile): TagFile {
        val current = readMerged()
        val next = block(current)
        if (next == current) return current
        io.writeMain(TagCodec.encode(next))
        return next
    }

    fun sourceStamp(): Long = io.sourceStamp()

    private fun readMerged(): TagFile {
        val main = decodeMain(io.readMain())
        val conflicts = io.readConflicts()
        if (conflicts.isEmpty()) return main
        var merged = main
        val consumed = ArrayList<String>()
        for (conflict in conflicts) {
            val parsed = try {
                TagCodec.decode(conflict.text)
            } catch (_: TagFileException) {
                null
            }
            if (parsed != null) {
                merged = mergeTagFiles(merged, parsed)
                consumed += conflict.id
            }
        }
        if (consumed.isNotEmpty()) {
            io.writeMain(TagCodec.encode(merged))
            io.clearConflicts(consumed)
        }
        return merged
    }

    private fun decodeMain(text: String?): TagFile {
        if (text.isNullOrBlank()) return TagFile()
        return TagCodec.decode(text)
    }
}

fun atomicWrite(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, file.name + ".tmp")
    temporary.writeText(text, Charsets.UTF_8)
    try {
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
