package app.tagplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import app.tagplayer.MainActivity
import app.tagplayer.R
import app.tagplayer.TagPlayerApplication
import app.tagplayer.core.LocalTrack
import app.tagplayer.core.PlayQueue
import app.tagplayer.core.displayArtist
import app.tagplayer.core.displayTitle
import app.tagplayer.core.QueueStep
import app.tagplayer.core.nextTrack
import app.tagplayer.core.previousTrack
import app.tagplayer.core.shuffledQueue
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File

class PlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val library by lazy { (application as TagPlayerApplication).library }
    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var session: MediaSessionCompat
    private lateinit var focusRequest: AudioFocusRequest
    private var queue = PlayQueue()
    private var playing = false
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = 0L
    private var title = "随播"
    private var artist = ""
    private var errorSkips = 0
    private var resumeAfterFocus = false
    private var lastUiUpdate = 0L
    private var ignoreEndUntil = 0L
    private var hasMedia = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        libVlc = LibVLC(this, arrayListOf("--no-video"))
        player = MediaPlayer(libVlc)
        player.setEventListener { event -> onVlcEvent(event) }
        session = MediaSessionCompat(this, "tagplayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = playOrResume()
                override fun onPause() = pause()
                override fun onSkipToNext() = userNext()
                override fun onSkipToPrevious() = userPrevious()
                override fun onSeekTo(pos: Long) = seek(pos)
            })
            isActive = true
        }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change -> main.post { onFocus(change) } }
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_PLAY_QUEUE -> {
                val ids = intent.getStringArrayListExtra(EXTRA_IDS).orEmpty()
                if (ids.isNotEmpty()) {
                    errorSkips = 0
                    queue = PlayQueue(ids)
                    startCurrent()
                }
            }
            ACTION_TOGGLE -> togglePlayback()
            ACTION_NEXT -> userNext()
            ACTION_PREV -> userPrevious()
            ACTION_SEEK -> seek(intent.getLongExtra(EXTRA_POS, 0L))
            ACTION_PAUSE -> pause()
            ACTION_RENAME_ID -> {
                val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
                val to = intent.getStringExtra(EXTRA_TO).orEmpty()
                if (from.isNotBlank() && to.isNotBlank() && from != to) {
                    queue = queue.withIdReplaced(from, to)
                }
                refreshLabels()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        abandonFocus()
        session.release()
        player.release()
        libVlc.release()
        super.onDestroy()
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.EndReached -> main.post {
                if (android.os.SystemClock.elapsedRealtime() >= ignoreEndUntil) onNaturalEnd()
            }
            MediaPlayer.Event.EncounteredError -> main.post { onPlaybackError("播放失败，已跳过") }
            MediaPlayer.Event.Playing -> main.post {
                playing = true
                publish()
                updateNotification()
            }
            MediaPlayer.Event.Paused -> main.post {
                playing = false
                publish()
                updateNotification()
            }
            MediaPlayer.Event.TimeChanged -> {
                positionMs = event.timeChanged
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastUiUpdate > 400) {
                    lastUiUpdate = now
                    main.post { publish() }
                }
            }
            MediaPlayer.Event.LengthChanged -> {
                durationMs = event.lengthChanged
                main.post { publish() }
            }
        }
    }

    private fun togglePlayback() {
        if (playing || player.isPlaying) pause() else playOrResume()
    }

    private fun playOrResume() {
        if (queue.currentId == null) return
        if (!hasMedia) {
            startCurrent()
            return
        }
        if (!player.isPlaying) {
            requestFocus()
            player.play()
        }
    }

    private fun pause() {
        ignoreEndUntil = android.os.SystemClock.elapsedRealtime() + 800
        try {
            player.pause()
        } catch (_: Exception) {
        }
        playing = false
        publish()
        updateNotification()
    }

    private fun userNext() {
        errorSkips = 0
        advance()
    }

    private fun userPrevious() {
        when (val step = previousTrack(queue, positionMs)) {
            app.tagplayer.core.PreviousStep.Empty -> Unit
            app.tagplayer.core.PreviousStep.Restart -> seek(0)
            is app.tagplayer.core.PreviousStep.Move -> {
                queue = step.queue
                startCurrent()
            }
        }
    }

    private fun seek(position: Long) {
        player.time = position.coerceAtLeast(0)
        positionMs = position.coerceAtLeast(0)
        publish()
    }

    private fun onNaturalEnd() {
        errorSkips = 0
        advance()
    }

    private fun advance() {
        when (val step = nextTrack(queue)) {
            QueueStep.Empty -> Unit
            QueueStep.RoundDone -> {
                if (queue.ids.isEmpty()) return
                queue = PlayQueue(shuffledQueue(queue.ids))
                startCurrent()
            }
            is QueueStep.Move -> {
                queue = step.queue
                startCurrent()
            }
        }
    }

    private fun startCurrent() {
        val id = queue.currentId ?: return
        val track = library.find(id)
        if (track == null) {
            onPlaybackError("找不到文件")
            return
        }
        try {
            ignoreEndUntil = android.os.SystemClock.elapsedRealtime() + 800
            try {
                player.stop()
            } catch (_: Exception) {
            }
            val media = openMedia(track)
            media.addOption(":no-video")
            player.media = media
            media.release()
            requestFocus()
            player.play()
            hasMedia = true
            val record = library.tagFile.songs[track.contentId]
            title = displayTitle(track, record)
            artist = displayArtist(track, record)
            positionMs = 0
            publish()
            updateNotification()
        } catch (error: Exception) {
            onPlaybackError(error.message ?: "播放失败")
        }
    }

    private fun onPlaybackError(message: String) {
        errorSkips += 1
        PlaybackBus.state.value = PlaybackBus.state.value.copy(error = message)
        if (errorSkips > queue.ids.size.coerceAtLeast(1)) {
            playing = false
            publish()
            updateNotification()
            return
        }
        main.post { advance() }
    }

    private fun openMedia(track: LocalTrack): Media {
        val file = track.path.takeIf { it.isNotBlank() }?.let(::File)
        if (file != null && file.canRead()) return Media(libVlc, file.absolutePath)
        val descriptor = contentResolver.openFileDescriptor(Uri.parse(track.uri), "r")
            ?: error("无法打开音频")
        val duplicate = descriptor.dup()
        descriptor.close()
        return Media(libVlc, duplicate.fileDescriptor).also { duplicate.close() }
    }

    private fun requestFocus() {
        val manager = getSystemService(AudioManager::class.java)
        manager.requestAudioFocus(focusRequest)
    }

    private fun abandonFocus() {
        val manager = getSystemService(AudioManager::class.java)
        manager.abandonAudioFocusRequest(focusRequest)
    }

    private fun onFocus(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeAfterFocus = playing || player.isPlaying
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.volume = 40
            AudioManager.AUDIOFOCUS_GAIN -> {
                player.volume = 100
                if (resumeAfterFocus) playOrResume()
            }
        }
    }

    private fun publish() {
        PlaybackBus.state.value = PlayerState(
            queueIds = queue.ids,
            index = queue.index,
            playing = playing,
            positionMs = positionMs,
            durationMs = durationMs,
            currentId = queue.currentId,
            error = PlaybackBus.state.value.error,
        )
        val playbackState = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .setState(playbackState, positionMs, if (playing) 1f else 0f)
                .build(),
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .build(),
        )
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshLabels() {
        val id = queue.currentId ?: return
        val track = library.find(id) ?: return
        val record = library.tagFile.songs[track.contentId]
        title = displayTitle(track, record)
        artist = displayArtist(track, record)
        publish()
        updateNotification()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(artist.ifBlank { "本地随机播放" })
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(R.drawable.ic_prev, "上一首", pending(ACTION_PREV))
            .addAction(if (playing) R.drawable.ic_pause else R.drawable.ic_notification, if (playing) "暂停" else "播放", pending(ACTION_TOGGLE))
            .addAction(R.drawable.ic_next, "下一首", pending(ACTION_NEXT))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun pending(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "播放", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_PLAY_QUEUE = "app.tagplayer.PLAY_QUEUE"
        const val ACTION_TOGGLE = "app.tagplayer.TOGGLE"
        const val ACTION_NEXT = "app.tagplayer.NEXT"
        const val ACTION_PREV = "app.tagplayer.PREV"
        const val ACTION_SEEK = "app.tagplayer.SEEK"
        const val ACTION_PAUSE = "app.tagplayer.PAUSE"
        const val ACTION_RENAME_ID = "app.tagplayer.RENAME_ID"
        const val EXTRA_IDS = "ids"
        const val EXTRA_POS = "pos"
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
    }
}
