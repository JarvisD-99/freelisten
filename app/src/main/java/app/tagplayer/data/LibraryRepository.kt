package app.tagplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
import app.tagplayer.core.EmbeddedText
import app.tagplayer.core.SongRecord
import app.tagplayer.core.moveSong
import app.tagplayer.core.withMetadata
import app.tagplayer.core.withSong
import app.tagplayer.core.CachedMatch
import app.tagplayer.core.OnlineMatchStore
import app.tagplayer.core.applyOnlineMatches
import app.tagplayer.core.fillOnlineMatches
import app.tagplayer.core.findOnlineMatch
import app.tagplayer.core.shieldScanSkips
import app.tagplayer.core.lookupTrackMatches
import app.tagplayer.core.tracksWithConfirmedNames
import app.tagplayer.core.upsertMetadata
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

class LibraryRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("tagplayer", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "scan-cache.json")
    private val matchStore = OnlineMatchStore(File(context.filesDir, "online-match.json"))

    var tracks: List<LocalTrack> = emptyList()
        private set
    var tagFile: TagFile = TagFile()
        private set
    var folderLabel: String = "还没有选择音乐文件夹"
        private set

    fun treeUri(): Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    fun hasFolder(): Boolean = hasTree() || deviceScanEnabled()

    fun hasTree(): Boolean = treeUri()?.let { uri ->
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    } == true

    fun deviceScanEnabled(): Boolean = prefs.getBoolean(KEY_DEVICE, false)

    fun wholeDeviceScan(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun canReadDeviceMusic(): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return music.isDirectory && music.canRead()
    }

    fun setFolder(uri: Uri) {
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun loadCached() {
        if (!hasFolder() && !canReadDeviceMusic()) return
        folderLabel = if (deviceScanEnabled() || !hasTree()) "本机音乐" else treeUri()?.let(::labelFor) ?: "本机音乐"
        tracks = loadScanCache(cacheFile).values.toList()
        reloadTags()
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

    fun cachedMatch(contentId: String): CachedMatch? = matchStore.load()[contentId]

    fun replaceTrackIdentity(
        oldId: String,
        newId: String,
        file: File,
        text: EmbeddedText? = null,
        lockNames: Boolean = false,
        clearNames: Boolean = false,
    ) {
        if (oldId == newId && text == null) return
        tracks = tracks.map { track ->
            if (track.contentId != oldId) track else track.copy(
                contentId = newId,
                size = file.length(),
                modified = file.lastModified(),
                title = text?.title?.ifBlank { track.title } ?: track.title,
                artist = text?.artist?.ifBlank { track.artist } ?: track.artist,
                album = text?.album?.ifBlank { track.album } ?: track.album,
            )
        }
        saveScanCache(cacheFile, tracks)
        tagFile = repository().update { tags ->
            val moved = if (oldId == newId) tags else tags.moveSong(oldId, newId)
            if (text == null) {
                moved
            } else {
                val song = moved.songs[newId] ?: SongRecord()
                val duration = tracks.find { it.contentId == newId }?.durationMs ?: song.durationMs
                moved.withSong(
                    newId,
                    song.withMetadata(
                        text.title,
                        text.artist,
                        text.album,
                        duration,
                        file.length(),
                        System.currentTimeMillis(),
                        lockNames = lockNames,
                        clearNames = clearNames,
                    ),
                )
            }
        }
    }

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
        rememberScan(dedupeTracks(scanned), onProgress)
        folderLabel = directory?.absolutePath ?: labelFor(uri)
    }

    fun scanDevice(blocked: Set<String>, onProgress: (String) -> Unit) {
        val roots = deviceRoots()
        if (roots.isEmpty()) error("还读不到本机音乐。先允许直接读取音乐文件夹。")
        prefs.edit().putBoolean(KEY_DEVICE, true).apply()
        val (skipTrees, skipExact) = shieldScanSkips(blocked)
        val cache = loadScanCache(cacheFile)
        val found = ArrayList<LocalTrack>()
        roots.forEach { root ->
            found += scanAudioFolder(
                root,
                cache,
                { file -> readMetadata(null, file.absolutePath) },
                skipDirectories = setOf("android"),
                skipFolderPaths = skipTrees,
                skipExactFolders = skipExact,
            ) { done, total, name ->
                onProgress("正在读取 $done / $total · $name")
            }
        }
        rememberScan(dedupeTracks(found), onProgress)
        folderLabel = roots.joinToString("、") { it.absolutePath }
    }

    private fun rememberScan(scanned: List<LocalTrack>, onProgress: (String) -> Unit) {
        val now = System.currentTimeMillis()
        var matches = matchStore.load()
        tagFile = repository().update { file ->
            val base = file.upsertMetadata(scanned, now)
            matches = matchStore.load()
            fillOnlineMatches(scanned, base, matches, { track, artist, title ->
                findOnlineMatch(track, artist, title, ::lookupTrackMatches)
            }, { Thread.sleep(250) }, onProgress)
            matchStore.save(matches)
            applyOnlineMatches(base, scanned, matches, now)
        }
        tracks = tracksWithConfirmedNames(scanned, matches)
        saveScanCache(cacheFile, tracks)
    }

    private fun deviceRoots(): List<File> {
        val primary = Environment.getExternalStorageDirectory()
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager() && primary.isDirectory && primary.canRead()) {
            return listOf(primary)
        }
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return listOf(music).filter { it.isDirectory && it.canRead() }
    }

    private fun repository(): TagRepository = TagRepository(openIo())

    private fun openIo(): TagIo {
        val uri = treeUri()
        val chosen = uri?.let { resolveTreeDirectory(it) }?.takeIf { it.isDirectory && it.canWrite() }
        if (chosen != null) return FileTagIo(File(chosen, "tags.json"))
        if (uri != null && !deviceScanEnabled()) return SafTagIo(context, uri)
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (music.isDirectory && music.canWrite()) return FileTagIo(File(music, "tags.json"))
        if (uri != null) return SafTagIo(context, uri)
        return FileTagIo(File(context.filesDir, "tags.json"))
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
        private const val KEY_DEVICE = "device_scan"
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
