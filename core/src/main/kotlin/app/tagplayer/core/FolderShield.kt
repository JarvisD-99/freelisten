package app.tagplayer.core

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class FolderShieldFile(val blocked: List<String> = emptyList())

class FolderShieldStore(private val file: File) {
    fun load(): Set<String> {
        if (!file.isFile) return emptySet()
        return try {
            TagCodec.json.decodeFromString(FolderShieldFile.serializer(), file.readText()).blocked.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun save(blocked: Set<String>) {
        file.parentFile?.mkdirs()
        atomicWrite(file, TagCodec.json.encodeToString(FolderShieldFile.serializer(), FolderShieldFile(blocked.sorted())))
    }
}

fun normalizePath(path: String): String {
    val absolute = File(path).absolutePath.replace('\\', '/')
    return absolute.trimEnd('/').ifEmpty { "/" }
}

fun trackFolder(track: LocalTrack): String? {
    if (track.path.isBlank()) return null
    val parent = File(track.path).parentFile ?: return null
    return normalizePath(parent.path)
}

fun folderBlocks(track: LocalTrack, blocked: Set<String>): Boolean {
    val folder = trackFolder(track) ?: return false
    return blocked.any { shield -> shieldCovers(shield, folder) }
}

fun toggleFolderShield(blocked: Set<String>, entryPath: String): Set<String> {
    val key = shieldKey(entryPath)
    val covering = blocked.filter { shieldCovers(it, key) }
    return if (covering.isNotEmpty()) blocked - covering.toSet() else blocked + key
}

data class FolderEntry(
    val path: String,
    val name: String,
    val songCount: Int,
    val blocked: Boolean,
    val canEnter: Boolean,
)

data class FolderBrowse(
    val current: String? = null,
    val title: String = "文件夹",
    val canGoUp: Boolean = false,
    val entries: List<FolderEntry> = emptyList(),
)

fun browseFolders(tracks: List<LocalTrack>, inside: String?, blocked: Set<String>): FolderBrowse {
    val folders = tracks.mapNotNull(::trackFolder)
    if (folders.isEmpty()) return FolderBrowse(title = "还没有扫到带路径的歌")
    val root = commonDirectory(folders) ?: return FolderBrowse(title = "文件夹")
    val current = inside?.let(::normalizePath)?.takeIf { it == root || it.startsWith("$root/") } ?: root
    val direct = tracks.count { trackFolder(it) == current }
    val childCounts = linkedMapOf<String, Int>()
    val childDeeper = linkedMapOf<String, Boolean>()
    for (track in tracks) {
        val folder = trackFolder(track) ?: continue
        if (folder == current || !folder.startsWith("$current/")) continue
        val childName = folder.removePrefix("$current/").substringBefore('/')
        val childPath = "$current/$childName"
        childCounts[childPath] = (childCounts[childPath] ?: 0) + 1
        if (folder != childPath) childDeeper[childPath] = true
    }
    val entries = ArrayList<FolderEntry>()
    if (direct > 0) {
        val key = "=$current"
        entries += FolderEntry(key, "这一层的歌", direct, blocked.any { shieldCovers(it, key) }, canEnter = false)
    }
    childCounts.keys.sorted().forEach { path ->
        entries += FolderEntry(
            path = path,
            name = File(path).name,
            songCount = childCounts.getValue(path),
            blocked = blocked.any { shieldCovers(it, path) },
            canEnter = childDeeper[path] == true,
        )
    }
    return FolderBrowse(
        current = current,
        title = folderTitle(current),
        canGoUp = current != root,
        entries = entries,
    )
}

fun folderParent(current: String): String? = File(current).parentFile?.path?.let(::normalizePath)

fun shieldScanSkips(blocked: Set<String>): Pair<Set<String>, Set<String>> {
    val trees = linkedSetOf<String>()
    val exact = linkedSetOf<String>()
    for (shield in blocked) {
        if (shield.startsWith("=")) exact += normalizePath(shield.removePrefix("="))
        else trees += normalizePath(shield)
    }
    return trees to exact
}

private fun shieldKey(path: String): String {
    return if (path.startsWith("=")) "=${normalizePath(path.removePrefix("="))}" else normalizePath(path)
}

private fun shieldCovers(shield: String, entryKey: String): Boolean {
    val shieldExact = shield.startsWith("=")
    val entryExact = entryKey.startsWith("=")
    val shieldPath = normalizePath(shield.removePrefix("="))
    val entryPath = normalizePath(entryKey.removePrefix("="))
    if (shieldExact) return entryExact && shieldPath == entryPath
    return entryPath == shieldPath || entryPath.startsWith("$shieldPath/")
}

private fun commonDirectory(folders: List<String>): String? {
    val split = folders.map { normalizePath(it).split('/').filter { part -> part.isNotEmpty() } }
    if (split.isEmpty()) return null
    val shared = ArrayList<String>()
    val shortest = split.minOf { it.size }
    for (index in 0 until shortest) {
        val part = split[0][index]
        if (split.all { it[index] == part }) shared += part else break
    }
    if (shared.isEmpty()) return "/"
    return "/" + shared.joinToString("/")
}

private fun folderTitle(path: String): String = when {
    path.endsWith("/emulated/0") -> "内部存储"
    File(path).name == "Music" -> "音乐"
    else -> File(path).name.ifBlank { path }
}
