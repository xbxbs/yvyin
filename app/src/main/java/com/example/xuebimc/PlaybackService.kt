package com.example.xuebimc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Native transport session; no binding, dependency, artwork fetch, or automatic process restart. */
class PlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: PlaybackController
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager
    private var attached = false
    private var foreground = false
    private var stopping = false
    private var idleStopScheduled = false
    private var latestStartId = 0
    private var publishedTrack: Track? = null
    private var publishedDuration = -1L
    // Song cover for the notification / lock screen (embedded art is not reachable by URI).
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var artworkJob: Job? = null
    private var artworkKey: String? = null
    private var artwork: android.graphics.Bitmap? = null
    private val repository by lazy { LocalMusicRepository(applicationContext) }
    private val observer: () -> Unit = { refresh() }

    private val idleStop = Runnable {
        idleStopScheduled = false
        if (!controller.playing && !controller.playbackRequested) stopPlaybackService()
    }
    private val progress = object : Runnable {
        override fun run() {
            if (stopping || !attached) return
            controller.tick()
            publishPlaybackState()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW).apply {
                description = "后台播放与媒体控制"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )

        // Satisfy startForegroundService's deadline BEFORE store attachment, URI I/O, or audio focus.
        try {
            val initial = notificationBuilder()
                .setContentTitle("音乐播放")
                .setContentText("正在准备播放")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, initial)
            }
            foreground = true
        } catch (failure: Exception) {
            PlaybackStore.get(this).onServiceStartFailed(
                "无法进入前台播放：${failure.localizedMessage ?: "请检查服务声明和权限"}",
            )
            stopping = true
            stopSelf()
            return
        }

        controller = PlaybackStore.get(this)
        session = MediaSession(this, "XuebimcPlayback").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setSessionActivity(launchIntent())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { this@PlaybackService.controller.play() }
                override fun onPause() { this@PlaybackService.controller.pause() }
                override fun onSkipToNext() { this@PlaybackService.controller.next() }
                override fun onSkipToPrevious() { this@PlaybackService.controller.previous() }
                override fun onSeekTo(pos: Long) { this@PlaybackService.controller.seek(pos) }
                override fun onStop() { stopPlaybackService() }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    this@PlaybackService.controller.queue.firstOrNull { it.stableKey == mediaId }
                        ?.let { this@PlaybackService.controller.playTrack(it) }
                }
            }, handler)
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (!::controller.isInitialized || !::session.isInitialized) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // A fresh user request can arrive while the idle stop is being dispatched.
        // Re-enter foreground on this instance before reattaching or requesting audio focus.
        if (stopping) {
            stopping = false
            try {
                val notification = notificationBuilder().setContentTitle("音乐播放").setOngoing(true).build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                foreground = true
            } catch (failure: Exception) {
                controller.onServiceStartFailed("无法恢复后台播放：${failure.localizedMessage ?: "未知错误"}")
                stopping = true
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        // Apply cancellation before attaching: a delayed service start must not undo a user's pause.
        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_STOP -> {
                stopPlaybackService()
                return START_NOT_STICKY
            }
        }
        if (!attached) {
            attached = true
            controller.attachService(observer)
            handler.post(progress)
        }
        when (intent?.action) {
            ACTION_PLAY -> controller.play()
            ACTION_NEXT -> controller.next()
            ACTION_PREVIOUS -> controller.previous()
            Intent.ACTION_MEDIA_BUTTON -> {
                @Suppress("DEPRECATION")
                val event = intent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (event != null) session.controller.dispatchMediaButtonEvent(event)
            }
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun refresh() {
        if (stopping) return
        val track = controller.currentTrack
        if (track == null || controller.isReleased) {
            stopPlaybackService()
            return
        }
        if (publishedTrack !== track || publishedDuration != controller.durationMs) {
            // A fresh builder clears the previous song's artwork, even when this song has none.
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.stableKey)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_URI, track.uri.toString())
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, controller.durationMs)
            track.artworkUri?.let {
                metadata.putString(MediaMetadata.METADATA_KEY_ART_URI, it.toString())
                metadata.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, it.toString())
            }
            if (artworkKey == track.stableKey) artwork?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            session.setMetadata(metadata.build())
            if (artworkKey != track.stableKey) loadArtwork(track)
            publishedTrack = track
            publishedDuration = controller.durationMs
        }
        publishPlaybackState()
        notifications.notify(NOTIFICATION_ID, playbackNotification(track))
        if (controller.playing || controller.playbackRequested) {
            handler.removeCallbacks(idleStop)
            idleStopScheduled = false
        } else if (!idleStopScheduled) {
            idleStopScheduled = true
            handler.postDelayed(idleStop, PAUSED_STOP_DELAY_MS)
        }
    }

    private fun loadArtwork(track: Track) {
        artworkJob?.cancel()
        artworkKey = track.stableKey
        artwork = null
        artworkJob = artworkScope.launch {
            val bitmap = try {
                repository.loadArtwork(track, 512)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (stopping || artworkKey != track.stableKey) return@launch
            artwork = bitmap
            publishedTrack = null // republish metadata with the cover
            refresh()
        }
    }

    private fun publishPlaybackState() {
        if (stopping) return
        val state = when {
            controller.error != null && !controller.playbackRequested -> PlaybackState.STATE_ERROR
            controller.playing && !controller.isBuffering -> PlaybackState.STATE_PLAYING
            controller.playbackRequested -> PlaybackState.STATE_BUFFERING
            controller.ready -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_SEEK_TO
        val builder = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                state, controller.positionMs,
                if (state == PlaybackState.STATE_PLAYING) 1f else 0f,
                SystemClock.elapsedRealtime(),
            )
        controller.error?.let { builder.setErrorMessage(it) }
        session.setPlaybackState(builder.build())
    }

    private fun notificationBuilder(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(launchIntent())

    private fun playbackNotification(track: Track): Notification {
        val wantsPlayback = controller.playing || controller.playbackRequested
        return notificationBuilder()
            .setContentTitle(track.title.ifBlank { track.displayName.ifBlank { "未知歌曲" } })
            .setContentText(track.artist.ifBlank { track.album })
            .apply { if (artworkKey == track.stableKey) artwork?.let { setLargeIcon(it) } }
            .setSubText(controller.error ?: if (!controller.ready && controller.playbackRequested) "准备播放" else null)
            .setOngoing(wantsPlayback)
            .setDeleteIntent(actionIntent(ACTION_STOP, 4))
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "上一首", actionIntent(ACTION_PREVIOUS, 1),
            ).build())
            .addAction(Notification.Action.Builder(
                if (wantsPlayback) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (wantsPlayback) "暂停" else "播放",
                actionIntent(if (wantsPlayback) ACTION_PAUSE else ACTION_PLAY, 2),
            ).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_next, "下一首", actionIntent(ACTION_NEXT, 3),
            ).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "停止", actionIntent(ACTION_STOP, 4),
            ).build())
            .setStyle(Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, requestCode, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun launchIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    private fun stopPlaybackService() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        idleStopScheduled = false
        if (attached) {
            controller.detachService(observer)
            attached = false
        } else if (::controller.isInitialized) {
            controller.pause()
        }
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        if (latestStartId != 0) stopSelf(latestStartId) else stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        artworkScope.cancel()
        handler.removeCallbacksAndMessages(null)
        if (attached) controller.detachService(observer)
        attached = false
        if (::session.isInitialized) {
            session.isActive = false
            session.setCallback(null)
            session.release()
        }
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        // The store retains the paused decoder/queue for the Activity; never release it here.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "local_music_playback"
        private const val NOTIFICATION_ID = 2001
        private const val PAUSED_STOP_DELAY_MS = 60_000L
        private const val ACTION_ATTACH = "com.example.xuebimc.playback.ATTACH"
        private const val ACTION_PLAY = "com.example.xuebimc.playback.PLAY"
        private const val ACTION_PAUSE = "com.example.xuebimc.playback.PAUSE"
        private const val ACTION_NEXT = "com.example.xuebimc.playback.NEXT"
        private const val ACTION_PREVIOUS = "com.example.xuebimc.playback.PREVIOUS"
        private const val ACTION_STOP = "com.example.xuebimc.playback.STOP"

        internal fun start(context: Context) {
            checkNotNull(context.startForegroundService(
                Intent(context, PlaybackService::class.java).setAction(ACTION_ATTACH),
            )) { "PlaybackService 未在 Manifest 中声明" }
        }
    }
}
