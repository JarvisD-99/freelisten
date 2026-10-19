package app.tagplayer.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.tagplayer.core.ConflictCopy
import app.tagplayer.core.TagIo

class SafTagIo(
    private val context: Context,
    private val tree: Uri,
) : TagIo {
    private val prefix = "tags.json.sync-conflict-"

    override fun readMain(): String? {
        val document = findTags() ?: return null
        return context.contentResolver.openInputStream(document.uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    override fun writeMain(text: String) {
        val root = root() ?: error("无法打开音乐文件夹")
        var document = root.findFile("tags.json")
        if (document == null || !document.isFile) {
            document = root.createFile("application/json", "tags.json")
        }
        val target = document ?: error("无法保存 tags.json")
        val stream = context.contentResolver.openOutputStream(target.uri, "wt")
            ?: error("无法写入 tags.json")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override fun readConflicts(): List<ConflictCopy> {
        val root = root() ?: return emptyList()
        return root.listFiles().mapNotNull { document ->
            val name = document.name ?: return@mapNotNull null
            if (!document.isFile || !name.startsWith(prefix)) return@mapNotNull null
            val text = context.contentResolver.openInputStream(document.uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: return@mapNotNull null
            ConflictCopy(name, text)
        }
    }

    override fun clearConflicts(ids: List<String>) {
        val wanted = ids.toSet()
        val root = root() ?: return
        root.listFiles().forEach { document ->
            val name = document.name ?: return@forEach
            if (name in wanted && name.startsWith(prefix)) document.delete()
        }
    }

    override fun sourceStamp(): Long {
        val root = root() ?: return 0L
        if (root.listFiles().any { it.name?.startsWith(prefix) == true }) return Long.MAX_VALUE
        return findTags()?.lastModified() ?: 0L
    }

    private fun findTags(): DocumentFile? = root()?.findFile("tags.json")?.takeIf { it.isFile }

    private fun root(): DocumentFile? = DocumentFile.fromTreeUri(context, tree)
}
