package app.tagplayer.core

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

const val CONTENT_ID_SAMPLE_BYTES = 65_536

fun contentId(size: Long, head: ByteArray, tail: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val sizeBytes = ByteBuffer.allocate(8).putLong(size).array()
    digest.update(sizeBytes)
    if (size <= CONTENT_ID_SAMPLE_BYTES) {
        val count = minOf(head.size.toLong(), size.coerceAtLeast(0)).toInt()
        if (count > 0) digest.update(head, 0, count)
    } else {
        val headCount = minOf(head.size, CONTENT_ID_SAMPLE_BYTES)
        val tailCount = minOf(tail.size, CONTENT_ID_SAMPLE_BYTES)
        if (headCount > 0) digest.update(head, 0, headCount)
        if (tailCount > 0) digest.update(tail, 0, tailCount)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun contentIdFromReader(size: Long, readFullyAt: (position: Long, dest: ByteArray) -> Unit): String {
    if (size <= 0L) return contentId(0, ByteArray(0), ByteArray(0))
    if (size <= CONTENT_ID_SAMPLE_BYTES) {
        val all = ByteArray(size.toInt())
        readFullyAt(0, all)
        return contentId(size, all, ByteArray(0))
    }
    val head = ByteArray(CONTENT_ID_SAMPLE_BYTES)
    val tail = ByteArray(CONTENT_ID_SAMPLE_BYTES)
    readFullyAt(0, head)
    readFullyAt(size - CONTENT_ID_SAMPLE_BYTES, tail)
    return contentId(size, head, tail)
}

fun contentIdOfFile(file: java.io.File): String = RandomAccessFile(file, "r").use { raf ->
    contentIdFromReader(raf.length()) { position, dest ->
        raf.seek(position)
        raf.readFully(dest)
    }
}
