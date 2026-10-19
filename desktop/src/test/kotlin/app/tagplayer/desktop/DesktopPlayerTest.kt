package app.tagplayer.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPlayerTest {
    @Test
    fun ffmpegCommandSeeksBeforeDecoding() {
        val command = ffmpegDecodeCommand("ffmpeg", File("/tmp/甲.ape"), 2500)
        assertEquals("ffmpeg", command.first())
        assertEquals("-ss", command[command.indexOf("-i") - 2])
        assertTrue(command.contains("/tmp/甲.ape"))
        assertEquals("-", command.last())
    }
}
