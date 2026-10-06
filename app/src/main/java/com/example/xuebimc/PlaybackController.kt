package com.example.xuebimc

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main-thread engine. Activities and the service must obtain it from PlaybackStore. */
class PlaybackController(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolverJob: Job? = null

    /** Assign an application-scoped resolver; only online tracks pass through it. */
    var trackResolver: (suspend (Track) -> Track)? = null
    private val audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val preferences = this.context.getSharedPreferences("player", Context.MODE_PRIVATE)
    val listeningStats = ListeningStats(this.context)
    private val favorites = preferences.getStringSet("favorite_tracks", emptySet()).orEmpty().toMutableSet()
    private val timeline = PlaybackTimeline()
    private val frameClock = PlaybackFrameClock()
    private val defaultFrameOwner = Any()
    private val scheduler = PlaybackQueue<Track>(
        keyOf = { it.stableKey },
        tagsOf = { PlaybackQueue.Tags(it.genre, it.artist, it.album) },
        isLocal = { !it.isOnline },
    )
    private var player: MediaPlayer? = null
    private var generation = 0L
    private var seeking = false
    private var seekTarget = 0L
    private var queuedSeek: Long? = null
    private var lastSampleRealtime = 0L
    private var lastStatsRealtime = 0L
    private var playWhenReady = false
    private var resumeAfterFocusGain = false
    private var focusHeld = false
    private var ducked = false
    internal var isBuffering = false
        private set
    private var serviceObserver: (() -> Unit)? = null
    private var serviceStartPending = false

    internal var isReleased = false
        private set
    internal val playbackRequested: Boolean get() = playWhenReady || resumeAfterFocusGain

    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var volume by mutableFloatStateOf(preferences.getFloat("volume", 0.65f).let {
        if (it.isFinite()) it.coerceIn(0f, 1f) else 0.65f
    })
        private set
    var favorite by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var seekRevision by mutableIntStateOf(0)
        private set
    var currentTrack by mutableStateOf<Track?>(null)
        private set
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var queueHistory by mutableStateOf<List<QueueEntry>>(emptyList())
        private set
    var currentQueueEntry by mutableStateOf<QueueEntry?>(null)
        private set
    var manualQueue by mutableStateOf<List<QueueEntry>>(emptyList())
        private set
    var contextQueue by mutableStateOf<List<QueueEntry>>(emptyList())
        private set
    var contextSourceName by mutableStateOf("资料库")
        private set
    var autoplayEnabled by mutableStateOf(false)
        private set
    var repeatMode by mutableStateOf(RepeatMode.Off)
        private set
    var shuffled by mutableStateOf(false)
        private set
    /** Legacy service projection only; the scheduler owns all order and occurrence identity. */
    val queuePosition: Int get() = if (currentQueueEntry == null) -1 else queueHistory.size

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener({ change ->
            if (!isReleased && focusHeld) {
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        ducked = false
                        applyVolume()
                        if (resumeAfterFocusGain && serviceObserver != null) {
                            resumeAfterFocusGain = false
                            playWhenReady = true
                            startIfReady()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        resumeAfterFocusGain = resumeAfterFocusGain || playing || playWhenReady
                        pause(abandonFocus = false)
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        ducked = true
                        applyVolume()
                    }
                }
            }
        }, handler)
        .build()

    private val serviceStartTimeout = Runnable {
        if (serviceStartPending) {
            onServiceStartFailed("后台播放服务启动超时，请重新播放")
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    init {
        ContextCompat.registerReceiver(
            this.context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true) {
        replaceQueue(tracks, startIndex, autoPlay, "资料库")
    }

    private fun replaceQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean, sourceName: String) {
        if (isReleased) return
        if (tracks.isEmpty()) {
            pause()
            disposePlayer()
            scheduler.setContext(emptyList(), sourceName = sourceName)
            publishQueue()
            durationMs = 0L
            positionMs = 0L
            favorite = false
            error = null
            seekRevision++
            changed()
            return
        }
        val index = startIndex.coerceIn(tracks.indices)
        val track = tracks[index]
        val reusePlayer = currentTrack?.stableKey == track.stableKey && player != null
        scheduler.setContext(tracks, index, sourceName)
        if (reusePlayer) {
            updateTrack(track)
            if (autoPlay) play() else pause()
        } else {
            loadCurrent(autoPlay, userRequest = true)
        }
    }

    fun playTrack(track: Track, tracks: List<Track> = queue, sourceName: String = "资料库") {
        val index = tracks.indexOfFirst { it === track }.takeIf { it >= 0 }
            ?: tracks.indexOfFirst { it.stableKey == track.stableKey }
        val updated = if (index < 0) tracks + track else tracks.mapIndexed { i, existing ->
            if (i == index) track else existing
        }
        replaceQueue(updated, if (index < 0) updated.lastIndex else index, autoPlay = true, sourceName = sourceName)
    }

    /** Metadata/lyrics replacement only: never recreate the decoder or reset its clock. */
    fun updateTrack(track: Track) {
        if (isReleased) return
        scheduler.updateTrack(track)
        publishQueue()
        if (currentTrack?.stableKey == track.stableKey) {
            currentTrack = track
            if (!ready || durationMs <= 0L) durationMs = track.durationMs.coerceAtLeast(0L)
        }
        changed()
    }

    private fun publishQueue() {
        fun project(entry: PlaybackQueue.Entry<Track>) = QueueEntry(
            id = entry.id,
            track = entry.track,
            origin = when (entry.origin) {
                PlaybackQueue.Origin.Context -> QueueOrigin.Context
                PlaybackQueue.Origin.Manual -> QueueOrigin.Manual
                PlaybackQueue.Origin.Autoplay -> QueueOrigin.Autoplay
            },
            sourceName = entry.sourceName,
        )
        queueHistory = scheduler.history.map(::project)
        currentQueueEntry = scheduler.current?.let(::project)
        manualQueue = scheduler.manual.map(::project)
        contextQueue = scheduler.context.map(::project)
        currentTrack = currentQueueEntry?.track
        listeningStats.onTrackStarted(currentTrack)
        contextSourceName = scheduler.contextSourceName
        autoplayEnabled = scheduler.autoplayEnabled
        shuffled = scheduler.shuffled
        repeatMode = when (scheduler.repeat) {
            PlaybackQueue.Repeat.Off -> RepeatMode.Off
            PlaybackQueue.Repeat.All -> RepeatMode.All
            PlaybackQueue.Repeat.One -> RepeatMode.One
        }
        queue = projectedEntries().map { it.track }
    }

    private fun projectedEntries(): List<QueueEntry> =
        queueHistory + listOfNotNull(currentQueueEntry) + manualQueue + contextQueue

    fun next() {
        if (isReleased || currentTrack == null) return
        val autoPlay = playing || playbackRequested || error != null
        scheduler.resetFailures()
        if (scheduler.advance() != null) loadCurrent(autoPlay, userRequest = true)
        else {
            publishQueue()
            pause()
        }
    }

    fun cycleRepeat() {
        if (isReleased || autoplayEnabled) return
        scheduler.cycleRepeat()
        publishQueue()
        changed()
    }

    /** Shuffle keeps the current song playing and reorders only what comes after it. */
    fun toggleShuffle() {
        if (isReleased || autoplayEnabled || currentTrack == null) return
        scheduler.toggleShuffle()
        publishQueue()
        changed()
    }

    fun toggleAutoplay() {
        if (isReleased) return
        scheduler.toggleAutoplay()
        publishQueue()
        changed()
    }

    fun setAutoplayLibrary(tracks: List<Track>) {
        if (isReleased) return
        scheduler.setAutoplayLibrary(tracks)
    }

    fun playNext(track: Track) = playNextMany(listOf(track))

    fun addToQueue(track: Track) = addToQueueMany(listOf(track))

    fun playNextMany(tracks: List<Track>) = enqueueManual(tracks, next = true)

    fun addToQueueMany(tracks: List<Track>) = enqueueManual(tracks, next = false)

    private fun enqueueManual(tracks: List<Track>, next: Boolean) {
        if (isReleased || tracks.isEmpty()) return
        scheduler.enqueue(tracks, next)
        if (scheduler.current == null && scheduler.advance() != null) {
            loadCurrent(autoPlay = true, userRequest = true)
        } else {
            publishQueue()
            changed()
        }
    }

    fun playQueueEntry(id: String) {
        if (isReleased || scheduler.playEntry(id) == null) return
        scheduler.resetFailures()
        loadCurrent(autoPlay = true, userRequest = true)
    }

    fun removeQueueEntry(id: String) {
        if (isReleased) return
        scheduler.remove(id)
        publishQueue()
        changed()
    }

    fun moveQueueEntry(id: String, beforeId: String?) {
        if (isReleased) return
        scheduler.move(id, beforeId)
        publishQueue()
        changed()
    }

    fun clearUpcoming() {
        if (isReleased) return
        scheduler.clearUpcoming()
        publishQueue()
        changed()
    }

    /** Compatibility adapters resolve indices to occurrence IDs before touching the scheduler. */
    fun playQueueAt(index: Int) {
        projectedEntries().getOrNull(index)?.let { playQueueEntry(it.id) }
    }

    fun removeFromQueue(index: Int) {
        projectedEntries().getOrNull(index)?.let { removeQueueEntry(it.id) }
    }

    fun previous() {
        if (isReleased || currentTrack == null) return
        if (positionMs > 3000L) { seek(0L); return }
        val autoPlay = playing || playbackRequested || error != null
        scheduler.resetFailures()
        if (scheduler.previous() != null) loadCurrent(autoPlay, userRequest = true) else seek(0L)
    }

    private fun loadCurrent(autoPlay: Boolean, userRequest: Boolean) {
        val entry = scheduler.current ?: return
        disposePlayer()
        publishQueue()
        durationMs = entry.track.durationMs.coerceAtLeast(0L)
        positionMs = 0L
        timeline.reset(0L, SystemClock.elapsedRealtime())
        seekRevision++
        playing = false
        playWhenReady = autoPlay
        if (resumeAfterFocusGain) abandonAudioFocus()
        resumeAfterFocusGain = false
        error = null
        favorite = entry.track.stableKey in favorites
        if (!autoPlay) abandonAudioFocus()
        changed()
        when {
            !autoPlay || serviceObserver != null -> prepareCurrentTrack()
            userRequest -> ensureServiceStarted()
            !serviceStartPending -> {
                playWhenReady = false
                abandonAudioFocus()
                changed()
            }
        }
    }

    private fun prepareCurrentTrack() {
        val track = currentTrack ?: return
        if (isReleased || player != null || resolverJob?.isActive == true) return
        val token = generation
        val resolver = trackResolver
        if (!track.isOnline || resolver == null) {
            prepareResolvedTrack(track, token)
            return
        }
        // No URI checks precede resolution: online favorites can initially have Uri.EMPTY.
        val job = resolverScope.launch(start = CoroutineStart.LAZY) {
            try {
                val resolved = withContext(Dispatchers.IO) { resolver(track) }
                currentCoroutineContext().ensureActive()
                if (isReleased || generation != token) return@launch
                require(resolved.stableKey == track.stableKey) { "在线解析结果的歌曲标识发生变化" }
                val latest = currentTrack ?: return@launch
                // Keep metadata/lyrics delivered while the network request was in flight.
                val playable = if (latest === track) resolved else latest.copy(
                    uri = resolved.uri,
                    requestHeaders = resolved.requestHeaders,
                    mimeType = resolved.mimeType ?: latest.mimeType,
                    codecMimeType = resolved.codecMimeType ?: latest.codecMimeType,
                    artworkUri = latest.artworkUri ?: resolved.artworkUri,
                    lines = latest.lines.ifEmpty { resolved.lines },
                )
                updateTrack(playable)
                if (!isReleased && generation == token) prepareResolvedTrack(playable, token)
            } catch (failure: Exception) {
                // A cancelled old request is silent; repository/network errors are recoverable.
                currentCoroutineContext().ensureActive()
                if (!isReleased && generation == token) {
                    failMedia("无法解析「${track.title}」：${failure.localizedMessage ?: "未知错误"}")
                }
            } finally {
                if (generation == token) resolverJob = null
            }
        }
        resolverJob = job
        job.start()
    }

    private fun prepareResolvedTrack(track: Track, token: Long) {
        if (isReleased || generation != token || player != null) return
        try {
            val candidate = MediaPlayer()
            player = candidate
            candidate.setAudioAttributes(audioAttributes)
            applyVolume()
            // Optional for hosts which have not yet added WAKE_LOCK to the manifest.
            if (context.checkSelfPermission(Manifest.permission.WAKE_LOCK) == PackageManager.PERMISSION_GRANTED) {
                candidate.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            }
            candidate.setOnPreparedListener { prepared ->
                if (isCurrent(prepared, token)) {
                    durationMs = prepared.duration.toLong().coerceAtLeast(0L)
                    ready = true
                    error = null
                    val pending = queuedSeek
                    queuedSeek = null
                    if (pending != null) seek(pending)
                    startIfReady()
                    changed()
                }
            }
            candidate.setOnSeekCompleteListener { prepared ->
                if (isCurrent(prepared, token)) finishSeek(prepared)
            }
            candidate.setOnCompletionListener { completed ->
                // Completion from an older in-flight seek cannot advance the queue.
                if (isCurrent(completed, token) && !seeking) completeTrack()
            }
            candidate.setOnInfoListener { active, what, _ ->
                if (isCurrent(active, token)) {
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            if (playing && !seeking && !isBuffering) {
                                positionMs = timeline.positionAt(SystemClock.elapsedRealtime()).coerceIn(0L, durationMs)
                            }
                            isBuffering = true
                            timeline.reset(positionMs, SystemClock.elapsedRealtime())
                            changed()
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            isBuffering = false
                            timeline.reset(positionMs, SystemClock.elapsedRealtime())
                            lastSampleRealtime = 0L
                            changed()
                        }
                    }
                }
                false
            }
            candidate.setOnErrorListener { failed, what, extra ->
                if (isCurrent(failed, token)) failMedia("无法播放「${track.title}」（$what / $extra）")
                true
            }
            // Track already contains a resolved local/HTTPS URI. No provider parsing or downloads here.
            candidate.setDataSource(context, track.uri, track.requestHeaders)
            candidate.prepareAsync()
        } catch (failure: Exception) {
            if (generation == token) failMedia("无法打开「${track.title}」：${failure.localizedMessage ?: "未知错误"}")
        }
    }

    private fun completeTrack() {
        val advance = playing || playWhenReady
        playing = false
        playWhenReady = false
        resumeAfterFocusGain = false
        positionMs = durationMs
        lastStatsRealtime = 0L
        listeningStats.flush()
        timeline.reset(positionMs, SystemClock.elapsedRealtime())
        if (advance && serviceObserver != null) {
            if (scheduler.advance(PlaybackQueue.Advance.Complete) != null) {
                loadCurrent(autoPlay = true, userRequest = false)
                return
            }
            publishQueue()
        }
        abandonAudioFocus()
        changed()
    }

    fun togglePlayback() {
        if (playing || playbackRequested) pause() else play()
    }

    /** Only explicit playback requests may start the foreground service. */
    fun play() {
        if (isReleased || currentTrack == null) return
        scheduler.resetFailures()
        if (resumeAfterFocusGain) abandonAudioFocus()
        resumeAfterFocusGain = false
        playWhenReady = true
        error = null
        if (player == null && resolverJob?.isActive != true) {
            // An explicit retry supersedes any posted automatic error-recovery action.
            generation++
            if (positionMs > 0L) queuedSeek = positionMs
        }
        if (ready && durationMs > 0L && positionMs >= durationMs - 100L) seek(0L)
        if (serviceObserver == null) {
            ensureServiceStarted()
        } else {
            if (player == null) prepareCurrentTrack()
            startIfReady()
        }
        changed()
    }

    private fun startIfReady() {
        val active = player ?: return
        if (isReleased || !ready || !playWhenReady || playing || serviceObserver == null) return
        if (!focusHeld) {
            focusHeld = runCatching {
                audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }.getOrDefault(false)
            if (!focusHeld) {
                playWhenReady = false
                error = "无法获得音频焦点，请稍后重试"
                changed()
                return
            }
        }
        try {
            active.start()
            val now = SystemClock.elapsedRealtime()
            // Pausing/resuming must not move the lyric clock backwards to a coarse sample.
            val anchor = if (seeking) positionMs else maxOf(positionMs, active.currentPosition.toLong())
            timeline.reset(anchor, now)
            lastSampleRealtime = now
            playing = true
            changed()
        } catch (failure: Exception) {
            failMedia("播放失败：${failure.localizedMessage ?: "未知错误"}")
        }
    }

    fun pause(abandonFocus: Boolean = true) {
        if (isReleased) return
        playWhenReady = false
        if (abandonFocus) resumeAfterFocusGain = false
        val wasPlaying = playing
        if (wasPlaying && ready && !seeking && !isBuffering) {
            positionMs = timeline.positionAt(SystemClock.elapsedRealtime()).coerceIn(0L, durationMs)
        }
        playing = false
        if (wasPlaying && ready) {
            runCatching { player?.pause() }.onFailure {
                failMedia("暂停失败：${it.localizedMessage ?: "未知错误"}")
            }
        }
        if (abandonFocus) abandonAudioFocus()
        timeline.reset(positionMs, SystemClock.elapsedRealtime())
        lastStatsRealtime = 0L
        listeningStats.flush()
        changed()
    }

    fun seek(milliseconds: Long) {
        if (isReleased || currentTrack == null) return
        val target = milliseconds.coerceIn(0L, if (durationMs > 0L) durationMs else Long.MAX_VALUE)
        seekRevision++
        positionMs = target
        timeline.reset(target, SystemClock.elapsedRealtime())
        if (!ready || seeking) {
            queuedSeek = target
        } else {
            seekTarget = target
            seeking = true
            runCatching { player?.seekTo(target, MediaPlayer.SEEK_CLOSEST) }.onFailure {
                failMedia("定位失败：${it.localizedMessage ?: "未知错误"}")
            }
        }
        changed()
    }

    private fun finishSeek(active: MediaPlayer) {
        if (!seeking) return
        val pending = queuedSeek
        try {
            if (pending != null) {
                queuedSeek = null
                seekTarget = pending
                active.seekTo(pending, MediaPlayer.SEEK_CLOSEST)
            } else {
                seeking = false
                val now = SystemClock.elapsedRealtime()
                val sampled = active.currentPosition.toLong()
                positionMs = (if (kotlin.math.abs(sampled - seekTarget) <= 80L) seekTarget else sampled)
                    .coerceIn(0L, durationMs)
                timeline.reset(positionMs, now)
                lastSampleRealtime = now
                if (playing && playWhenReady) {
                    if (durationMs > 0L && seekTarget >= durationMs) completeTrack() else active.start()
                }
                changed()
            }
        } catch (failure: Exception) {
            failMedia("定位失败：${failure.localizedMessage ?: "未知错误"}")
        }
    }

    /**
     * [frameNanos] is the Choreographer vsync time. Deriving the position from it (instead of
     * reading the wall clock whenever the callback happens to run) gives every frame an evenly
     * spaced time step, which is what keeps per-word motion from stuttering at 90/120 Hz.
     */
    /** Background notification ticks must not advance a position the next UI frame will render. */
    fun tick() = publishTick(frameNanos = null, frameOwner = null)

    fun tick(frameNanos: Long) = tick(frameNanos, defaultFrameOwner)

    fun tick(frameNanos: Long, frameOwner: Any) = publishTick(frameNanos, frameOwner)

    fun releaseFrameClock(frameOwner: Any) { frameClock.release(frameOwner) }

    private fun publishTick(frameNanos: Long?, frameOwner: Any?) {
        val active = player ?: return
        if (!playing || !ready || seeking || isBuffering || isReleased) return
        val receivedAt = SystemClock.elapsedRealtime()
        if (frameOwner != null) frameClock.onFrame(frameOwner, receivedAt)
        else if (!frameClock.serviceMayPublish(receivedAt)) return
        // Map the monotonic frame clock onto the elapsedRealtime base the timeline uses.
        val now = if (frameNanos == null) receivedAt else
            (frameNanos + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())) / 1_000_000L
        try {
            if (now - lastSampleRealtime >= 200L) {
                timeline.synchronize(active.currentPosition.toLong(), now)
                lastSampleRealtime = now
            }
            // Keep a handoff guard for stalled/resumed windows. During normal foreground frames,
            // the service no longer publishes ahead and creates duplicate/uneven rendered steps.
            positionMs = maxOf(positionMs, timeline.positionAt(now)).coerceIn(0L, durationMs)
            val previousStats = lastStatsRealtime
            lastStatsRealtime = now
            if (previousStats > 0L) listeningStats.recordProgress(currentTrack, now - previousStats, playing)
        } catch (failure: Exception) {
            failMedia("读取播放进度失败：${failure.localizedMessage ?: "未知错误"}")
        }
    }

    fun changeVolume(value: Float) {
        if (isReleased || !value.isFinite()) return
        volume = value.coerceIn(0f, 1f)
        applyVolume()
        preferences.edit().putFloat("volume", volume).apply()
    }

    fun toggleFavorite() {
        val key = currentTrack?.stableKey ?: return
        if (isReleased) return
        if (!favorites.add(key)) favorites.remove(key)
        favorite = key in favorites
        preferences.edit().putStringSet("favorite_tracks", favorites.toSet()).apply()
        changed()
    }

    private fun applyVolume() {
        val effective = volume * if (ducked) 0.2f else 1f
        runCatching { player?.setVolume(effective, effective) }
    }

    private fun abandonAudioFocus() {
        if (focusHeld) runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        focusHeld = false
        ducked = false
        applyVolume()
    }

    private fun failMedia(message: String) {
        val retry = playing || playWhenReady
        val skipUnreadableLocalFile = currentTrack?.isOnline != true
        disposePlayer()
        playing = false
        playWhenReady = false
        resumeAfterFocusGain = false
        error = message
        abandonAudioFocus()
        if (retry && skipUnreadableLocalFile && (serviceObserver != null || serviceStartPending)) {
            playWhenReady = true
            val token = generation
            // Resolve the next occurrence only when this runs: pause/reorder/clear may intervene.
            // Posting breaks bad-URI recursion; the scheduler excludes all already failed keys.
            handler.post {
                if (!isReleased && generation == token && playWhenReady) {
                    if (scheduler.advance(PlaybackQueue.Advance.Failure) != null) {
                        loadCurrent(autoPlay = true, userRequest = false)
                    } else {
                        playWhenReady = false
                        publishQueue()
                        changed()
                    }
                }
            }
        }
        changed()
    }

    private fun isCurrent(candidate: MediaPlayer, token: Long): Boolean =
        !isReleased && generation == token && player === candidate

    private fun disposePlayer() {
        generation++
        resolverJob?.cancel()
        resolverJob = null
        val old = player
        player = null
        ready = false
        seeking = false
        isBuffering = false
        queuedSeek = null
        if (old != null) {
            runCatching {
                old.setOnPreparedListener(null)
                old.setOnSeekCompleteListener(null)
                old.setOnCompletionListener(null)
                old.setOnInfoListener(null)
                old.setOnErrorListener(null)
            }
            runCatching { old.release() }
        }
    }

    private fun changed() { serviceObserver?.invoke() }

    private fun ensureServiceStarted() {
        if (serviceObserver != null || serviceStartPending || !playWhenReady || isReleased) return
        serviceStartPending = true
        handler.postDelayed(serviceStartTimeout, 8_000L)
        try {
            PlaybackService.start(context)
        } catch (failure: Exception) {
            onServiceStartFailed("无法启动后台播放：${failure.localizedMessage ?: "请检查服务声明和权限"}")
        }
    }

    internal fun attachService(observer: () -> Unit) {
        if (isReleased) return
        serviceObserver = observer
        serviceStartPending = false
        handler.removeCallbacks(serviceStartTimeout)
        if (playWhenReady) {
            if (player == null) prepareCurrentTrack()
            startIfReady()
        }
        changed()
    }

    internal fun detachService(observer: () -> Unit) {
        if (serviceObserver !== observer) return
        serviceObserver = null
        serviceStartPending = false
        handler.removeCallbacks(serviceStartTimeout)
        // Unexpected service loss must never leave unannounced background audio.
        pause()
    }

    internal fun onServiceStartFailed(message: String) {
        serviceStartPending = false
        handler.removeCallbacks(serviceStartTimeout)
        pause()
        error = message
        changed()
    }

    /** Explicit app-wide shutdown only; never call from Activity.onStop/onDestroy. */
    fun release() {
        if (isReleased) return
        pause()
        isReleased = true
        handler.removeCallbacksAndMessages(null)
        disposePlayer()
        resolverScope.cancel()
        scheduler.setContext(emptyList())
        scheduler.setAutoplayLibrary(emptyList())
        publishQueue()
        serviceStartPending = false
        changed()
        serviceObserver = null
        runCatching { context.unregisterReceiver(noisyReceiver) }
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}

enum class RepeatMode { Off, All, One }
