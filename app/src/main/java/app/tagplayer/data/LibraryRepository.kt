package app.tagplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.tagplayer.core.FileTagIo
import app.tagplayer.core.LocalTrack
import app.tagplayer.core.TagFile
import app.tagplayer.core.TagIo
import app.tagplayer.core.TagRepository
import app.tagplayer.core.TrackMetadata
import app.tagplayer.core.buildLocalTrack
import app.tagplayer.core.cacheKey
import app.tagplayer.core.contentIdFromReader
import app.tagplayer.core.contentIdOfFile
import app.tagplayer.core.dedupeTracks
import app.tagplayer.core.isAudioFileName
import app.tagplayer.core.loadScanCache
import app.tagplayer.core.saveScanCache
import app.tagplayer.core.scanAudioFolder
import app.tagplayer.core.shouldSkipFileName
import app.tagplayer.core.upsertMetadata
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

class LibraryRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("tagplayer", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "scan-cache.json")

    var tracks: List<LocalTrack> = emptyList()
        private set
    var tagFile: TagFile = TagFile()
        private set
    var folderLabel: String = "还没有选择音乐文件夹"
        private set

    fun treeUri(): Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    fun hasFolder(): Boolean = treeUri()?.let { uri ->
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    } == true

    fun setFolder(uri: Uri) {
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        folderLabel = labelFor(uri)
    }

    fun loadCached() {
        val uri = treeUri() ?: return
        folderLabel = labelFor(uri)
        tracks = loadScanCache(cacheFile).values.toList()
        if (hasFolder()) reloadTags()
    }

    fun reloadTags() {
        if (!hasFolder()) return
        tagFile = repository().read()
    }

    fun updateTags(block: (TagFile) -> TagFile): TagFile {
        val next = repository().update(block)
        tagFile = next
        return next
    }

    fun find(contentId: String): LocalTrack? = tracks.find { it.contentId == contentId }

    fun scan(onProgress: (String) -> Unit) {
        val uri = treeUri() ?: error("还没有选择音乐文件夹")
        val directory = resolveTreeDirectory(uri)?.takeIf { it.isDirectory && it.canRead() }
        val cache = loadScanCache(cacheFile)
        val scanned = if (directory != null) {
            scanAudioFolder(directory, cache, { file -> readMetadata(null, file.absolutePath) }) { done, total, name ->
                onProgress("正在读取 $done / $total · $name")
            }
        } else {
            scanTree(uri, cache, onProgress)
        }
        tracks = dedupeTracks(scanned)
        saveScanCache(cacheFile, tracks)
        tagFile = repository().update { it.upsertMetadata(tracks, System.currentTimeMillis()) }
        folderLabel = directory?.absolutePath ?: labelFor(uri)
    }

    private fun repository(): TagRepository = TagRepository(openIo())

    private fun openIo(): TagIo {
        val uri = treeUri() ?: error("还没有选择音乐文件夹")
        val directory = resolveTreeDirectory(uri)
        if (directory != null && directory.isDirectory && directory.canWrite()) {
            return FileTagIo(File(directory, "tags.json"))
        }
        return SafTagIo(context, uri)
    }

    private fun scanTree(tree: Uri, cache: Map<String, LocalTrack>, onProgress: (String) -> Unit): List<LocalTrack> {
        val root = DocumentFile.fromTreeUri(context, tree) ?: error("无法打开音乐文件夹")
        val documents = ArrayList<DocumentFile>()
        collectAudio(root, documents)
        val tracks = ArrayList<LocalTrack>(documents.size)
        documents.forEachIndexed { index, document ->
            val name = document.name ?: return@forEachIndexed
            if (index % 10 == 0) onProgress("正在读取 ${index + 1} / ${documents.size} · $name")
            val path = externalPath(document.uri)?.takeIf { File(it).canRead() }.orEmpty()
            val size = document.length().takeIf { it > 0 } ?: path.takeIf { it.isNotBlank() }?.let { File(it).length() } ?: 0L
            val modified = document.lastModified()
            val locator = path.ifBlank { document.uri.toString() }
            val cached = cache[cacheKey(locator, size, modified)]
            if (cached != null && cached.contentId.isNotBlank()) {
                tracks += cached.copy(path = path.ifBlank { cached.path }, uri = document.uri.toString())
                return@forEachIndexed
            }
            val contentId = if (path.isNotBlank()) {
                contentIdOfFile(File(path))
            } else {
                val descriptor = context.contentResolver.openFileDescriptor(document.uri, "r") ?: return@forEachIndexed
                descriptor.use { contentIdOfDescriptor(it) }
            }
            val metadata = readMetadata(document.uri, path.ifBlank { null })
            tracks += buildLocalTrack(
                fileName = name,
                path = path,
                uri = document.uri.toString(),
                size = size,
                modified = modified,
                contentId = contentId,
                metadata = metadata,
            )
        }
        onProgress("正在读取 ${documents.size} / ${documents.size}")
        return tracks
    }

    private fun collectAudio(directory: DocumentFile, sink: MutableList<DocumentFile>) {
        val children = directory.listFiles()
        for (child in children) {
            val name = child.name ?: continue
            if (shouldSkipFileName(name)) continue
            when {
                child.isDirectory -> collectAudio(child, sink)
                child.isFile && isAudioFileName(name) && child.length() != 0L -> sink += child
            }
        }
    }

    private fun readMetadata(uri: Uri?, path: String?): TrackMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            when {
                !path.isNullOrBlank() && File(path).canRead() -> retriever.setDataSource(path)
                uri != null -> retriever.setDataSource(context, uri)
                else -> return TrackMetadata()
            }
            TrackMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            TrackMetadata()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun labelFor(uri: Uri): String = resolveTreeDirectory(uri)?.absolutePath ?: decodeLabel(uri)

    private fun resolveTreeDirectory(uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        return externalStorageFile(documentId)
    }

    private fun externalPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        return externalStorageFile(documentId)?.absolutePath
    }

    private fun externalStorageFile(documentId: String): File? {
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2) return null
        val root = if (parts[0] == "primary") {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/${parts[0]}")
        }
        return File(root, parts[1])
    }

    private fun decodeLabel(uri: Uri): String = Uri.decode(uri.lastPathSegment ?: uri.toString())

    companion object {
        private const val KEY_TREE = "tree_uri"
    }
}

private fun contentIdOfDescriptor(descriptor: android.os.ParcelFileDescriptor): String {
    val channel = FileInputStream(descriptor.fileDescriptor).channel
    return contentIdFromReader(descriptor.statSize) { position, dest ->
        val buffer = ByteBuffer.wrap(dest)
        var cursor = position
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, cursor)
            if (count < 0) throw EOFException("音频文件读不完整")
            cursor += count
        }
    }
}
