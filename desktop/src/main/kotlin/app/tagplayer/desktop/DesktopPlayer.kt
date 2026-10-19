package app.tagplayer.desktop

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

interface PlayerListener {
    fun onProgress(positionMs: Long, durationMs: Long)
    fun onPlayingChanged(playing: Boolean)
    fun onEnded()
    fun onError(message: String)
}

enum class PlaybackBackend {
    Ffmpeg,
    JavaSound,
    Afplay,
    None,
}

class DesktopPlayer(
    private val listener: PlayerListener,
) {
    @Volatile private var generation = 0
    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    @Volatile private var suppressEnd = false
    @Volatile var positionMs: Long = 0
    @Volatile var durationMs: Long = 0
    @Volatile var backend: PlaybackBackend = PlaybackBackend.None

    private var process: Process? = null
    private var line: SourceDataLine? = null
    private var worker: Thread? = null
    private var currentFile: File? = null

    fun canResume(): Boolean = paused && currentFile != null

    fun play(file: File, startMs: Long = 0) {
        stop()
        stopRequested = false
        paused = false
        suppressEnd = false
        currentFile = file
        positionMs = startMs
        val token = generation
        val thread = Thread {
            try {
                when {
                    findExecutable("ffmpeg") != null -> playFfmpeg(token, file, startMs)
                    canUseJavaSound(file) -> playJavaSound(token, file, startMs)
                    canUseAfplay(file) -> playAfplay(token, file)
                    else -> listener.onError("这个格式需要 ffmpeg。安装命令：brew install ffmpeg")
                }
            } catch (error: Exception) {
                if (isCurrent(token)) listener.onError(error.message ?: "播放失败")
            }
        }
        thread.isDaemon = true
        thread.name = "tagplayer"
        worker = thread
        thread.start()
    }

    fun pause(): Boolean {
        if (backend == PlaybackBackend.Afplay) {
            suppressEnd = true
            process?.destroyForcibly()
            listener.onPlayingChanged(false)
            return false
        }
        if (worker?.isAlive != true) return false
        paused = true
        try {
            line?.stop()
        } catch (_: Exception) {
        }
        listener.onPlayingChanged(false)
        return true
    }

    fun resume() {
        if (!paused) return
        paused = false
        try {
            line?.start()
        } catch (_: Exception) {
        }
        listener.onPlayingChanged(true)
    }

    fun seek(positionMs: Long) {
        val file = currentFile ?: return
        play(file, positionMs.coerceAtLeast(0))
    }

    fun stop() {
        generation += 1
        stopRequested = true
        paused = false
        suppressEnd = true
        process?.destroyForcibly()
        process = null
        closeLine()
        val running = worker
        worker = null
        if (running != null && running !== Thread.currentThread()) {
            running.join(1500)
        }
    }

    private fun playFfmpeg(token: Int, file: File, startMs: Long) {
        backend = PlaybackBackend.Ffmpeg
        durationMs = probeDuration(file)
        val ffmpeg = findExecutable("ffmpeg") ?: return
        val started = ProcessBuilder(ffmpegDecodeCommand(ffmpeg, file, startMs))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process = started
        val format = AudioFormat(44_100f, 16, 2, true, false)
        val output = AudioSystem.getSourceDataLine(format)
        line = output
        output.open(format)
        output.start()
        if (!isCurrent(token)) return
        listener.onPlayingChanged(true)
        listener.onProgress(startMs, durationMs)
        val buffer = ByteArray(4096)
        var playedBytes = 0L
        var lastCallback = 0L
        val input = started.inputStream
        while (isCurrent(token)) {
            while (paused && isCurrent(token)) {
                Thread.sleep(40)
            }
            if (!isCurrent(token)) break
            if (!output.isRunning) output.start()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            playedBytes += count
            val position = startMs + playedBytes * 1000 / (44_100 * 4)
            positionMs = position
            val now = System.currentTimeMillis()
            if (now - lastCallback > 200) {
                lastCallback = now
                listener.onProgress(position, durationMs)
            }
        }
        val exit = if (isCurrent(token)) started.waitFor() else -1
        if (!isCurrent(token)) return
        listener.onPlayingChanged(false)
        if (suppressEnd) return
        if (exit != 0 && playedBytes == 0L) listener.onError("ffmpeg 无法播放这个文件")
        else listener.onEnded()
    }

    private fun playJavaSound(token: Int, file: File, startMs: Long) {
        backend = PlaybackBackend.JavaSound
        AudioSystem.getAudioInputStream(file).use { stream ->
            val format = stream.format
            val output = AudioSystem.getSourceDataLine(format)
            line = output
            output.open(format)
            output.start()
            val frameSize = format.frameSize.coerceAtLeast(1)
            val totalFrames = stream.frameLength
            durationMs = if (totalFrames > 0 && format.frameRate > 0) {
                (totalFrames * 1000.0 / format.frameRate).toLong()
            } else {
                0L
            }
            skipStart(stream, format, startMs)
            if (!isCurrent(token)) return
            listener.onPlayingChanged(true)
            listener.onProgress(startMs, durationMs)
            val buffer = ByteArray(4096)
            var playedFrames = (startMs * format.frameRate / 1000.0).toLong()
            var lastCallback = 0L
            while (isCurrent(token)) {
                while (paused && isCurrent(token)) {
                    Thread.sleep(40)
                }
                if (!isCurrent(token)) break
                if (!output.isRunning) output.start()
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                playedFrames += count / frameSize
                val position = (playedFrames * 1000.0 / format.frameRate).toLong()
                positionMs = position
                val now = System.currentTimeMillis()
                if (now - lastCallback > 200) {
                    lastCallback = now
                    listener.onProgress(position, durationMs)
                }
            }
        }
        if (!isCurrent(token)) return
        listener.onPlayingChanged(false)
        if (!suppressEnd) listener.onEnded()
    }

    private fun playAfplay(token: Int, file: File) {
        backend = PlaybackBackend.Afplay
        durationMs = probeDuration(file)
        val started = ProcessBuilder("afplay", file.absolutePath)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process = started
        if (!isCurrent(token)) return
        listener.onPlayingChanged(true)
        val exit = started.waitFor()
        if (!isCurrent(token)) return
        listener.onPlayingChanged(false)
        if (suppressEnd) return
        if (exit == 0) listener.onEnded() else listener.onError("系统播放器无法播放这个格式")
    }

    private fun skipStart(stream: AudioInputStream, format: AudioFormat, startMs: Long) {
        if (startMs <= 0 || format.frameRate <= 0) return
        var remaining = (startMs * format.frameRate / 1000.0).toLong() * format.frameSize.coerceAtLeast(1)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun probeDuration(file: File): Long {
        val ffprobe = findExecutable("ffprobe")
        if (ffprobe != null) {
            val probe = ProcessBuilder(ffprobeDurationCommand(ffprobe, file))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val text = probe.inputStream.bufferedReader().readText().trim()
            probe.waitFor()
            val seconds = text.toDoubleOrNull()
            if (seconds != null && seconds > 0) return (seconds * 1000).toLong()
        }
        return try {
            val format = AudioSystem.getAudioFileFormat(file)
            val frames = format.frameLength
            val rate = format.format.frameRate
            if (frames > 0 && rate > 0) (frames * 1000.0 / rate).toLong() else 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun isCurrent(token: Int): Boolean = token == generation && !stopRequested

    private fun closeLine() {
        try {
            line?.stop()
            line?.close()
        } catch (_: Exception) {
        }
        line = null
    }
}

internal fun ffmpegDecodeCommand(ffmpeg: String, file: File, startMs: Long): List<String> {
    val startSeconds = startMs.coerceAtLeast(0) / 1000.0
    return listOf(
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-ss",
        startSeconds.toString(),
        "-i",
        file.absolutePath,
        "-f",
        "s16le",
        "-acodec",
        "pcm_s16le",
        "-ac",
        "2",
        "-ar",
        "44100",
        "-",
    )
}

internal fun ffprobeDurationCommand(ffprobe: String, file: File): List<String> = listOf(
    ffprobe,
    "-v",
    "error",
    "-show_entries",
    "format=duration",
    "-of",
    "default=noprint_wrappers=1:nokey=1",
    file.absolutePath,
)

fun findExecutable(name: String): String? {
    val directories = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin") +
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
    return directories
        .map { File(it, name) }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
}

private fun canUseJavaSound(file: File): Boolean {
    val extension = file.extension.lowercase()
    if (extension !in setOf("wav", "wave", "aiff", "aif", "au")) return false
    return try {
        AudioSystem.getAudioFileFormat(file)
        true
    } catch (_: Exception) {
        false
    }
}

private fun canUseAfplay(file: File): Boolean {
    val extension = file.extension.lowercase()
    return extension in setOf("mp3", "m4a", "aac", "wav", "wave", "aiff", "aif", "caf", "mp4") &&
        File("/usr/bin/afplay").canExecute()
}
