package app.tagplayer.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

fun writeToneWav(file: File, frequencyHz: Double, seconds: Double) {
    val sampleRate = 44_100
    val samples = (sampleRate * seconds).toInt().coerceAtLeast(1)
    val dataSize = samples * 2
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(36 + dataSize)
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(1)
    buffer.putInt(sampleRate)
    buffer.putInt(sampleRate * 2)
    buffer.putShort(2)
    buffer.putShort(16)
    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(dataSize)
    for (index in 0 until samples) {
        val value = (sin(2.0 * PI * frequencyHz * index / sampleRate) * 8000).toInt().toShort()
        buffer.putShort(value)
    }
    file.writeBytes(buffer.array())
}
