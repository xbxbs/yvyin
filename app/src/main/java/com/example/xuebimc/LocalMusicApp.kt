package com.example.xuebimc

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.util.Locale

private fun audioPermission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun canReadAudio(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

@Composable
internal fun LocalMusicApp(
    playback: PlaybackController,
    frameRateMonitor: FrameRateMonitor,
    onBluetoothSettings: () -> Unit,
    outputName: () -> String,
    onImmersiveChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val repository = remember { LocalMusicRepository(context.applicationContext) }
    val onlineRepository = remember { OnlineMusicRepository(context.applicationContext) }
    val playlists = remember { PlaylistRepository(context.applicationContext) }
    val savedPlaylists by playlists.playlists.collectAsState()
    val preferences = remember { context.getSharedPreferences("local_library", Context.MODE_PRIVATE) }
    val appPreferences = remember { AppPreferences(context.applicationContext) }
    val appPreferenceValues by appPreferences.values.collectAsState()
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf<List<Track>>(emptyList()) }
    var hasPermission by remember { mutableStateOf(canReadAudio(context)) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var scanRevision by remember { mutableIntStateOf(0) }
    var playerVisible by remember { mutableStateOf(false) }
    var onlineVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var libraryOverlayVisible by remember { mutableStateOf(false) }
    var onlineOverlayVisible by remember { mutableStateOf(false) }
    var playlistOverlayVisible by remember { mutableStateOf(false) }
    var playlistVisible by remember { mutableStateOf(false) }
    var playlistAddVisible by remember { mutableStateOf(false) }
    var playlistAddTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var onlineSources by remember { mutableStateOf(onlineRepository.builtInSources) }
    var onlineSourceId by remember { mutableStateOf(appPreferences.values.value.defaultOnlineSource) }
    var onlineQuery by remember { mutableStateOf("") }
    var onlineResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var onlineSaved by remember { mutableStateOf(onlineRepository.savedTracks()) }
    var onlineLoading by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    var onlineRevision by remember { mutableIntStateOf(0) }
    var onlineSearchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lyricsVisible by remember { mutableStateOf(false) }
    var queueVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var lastSheet by remember { mutableStateOf("more") }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionRevision by remember { mutableIntStateOf(0) }
    val currentTrack = playback.currentTrack
    LaunchedEffect(library) { playback.setAutoplayLibrary(library) }

    LaunchedEffect(sheet) { sheet?.let { lastSheet = it } }

    LaunchedEffect(onlineVisible, settingsVisible) {
        if (onlineVisible || settingsVisible) onlineSources = onlineRepository.loadSources()
    }
    LaunchedEffect(appPreferenceValues.defaultOnlineSource) {
        val preferred = appPreferenceValues.defaultOnlineSource
        if (onlineSourceId != preferred) {
            onlineSearchJob?.cancel()
            onlineRevision++
            onlineLoading = false
            onlineSourceId = preferred
            onlineResults = emptyList()
            onlineError = null
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        message = if (granted) null else "未获得音乐读取权限；仍可用“选择文件”播放你授权的歌曲。"
        if (granted) scanRevision++
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = canReadAudio(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermission, scanRevision) {
        loading = true
        try {
            val scanned = if (hasPermission) repository.scan() else emptyList()
            val imported = preferences.getStringSet("imported_uris", emptySet()).orEmpty().mapNotNull { value ->
                try { repository.importAudio(Uri.parse(value)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            library = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                (scanned + imported).distinctBy { it.stableKey }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }
            if (hasPermission) message = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            message = "读取音乐失败：${failure.localizedMessage ?: "请检查权限或重新扫描"}"
        } finally {
            loading = false
        }
    }

    fun selectTrack(track: Track, queue: List<Track> = library.ifEmpty { listOf(track) }, sourceName: String = "资料库") {
        focus.clearFocus()
        keyboard?.hide()
        playback.playTrack(track, queue, sourceName)
        playerVisible = true
        onlineVisible = false
        lyricsVisible = false
        queueVisible = false
        sheet = null
        if (Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("notification_permission_requested", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            preferences.edit().putBoolean("notification_permission_requested", true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun playOnlineTrack(track: Track, queue: List<Track>, fromSaved: Boolean) {
        val source = onlineSources.firstOrNull { it.id == track.sourceId && it.enabled }
        if (source?.supportsPlayback != true) {
            onlineError = "该来源已支持搜索和在线歌单，播放解析尚未接通。"
            return
        }
        focus.clearFocus()
        keyboard?.hide()
        playback.playTrack(track, queue.ifEmpty { listOf(track) },
            if (fromSaved) "在线歌单" else "${source.name}搜索")
        playerVisible = true
        lyricsVisible = false
        queueVisible = false
        if (Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("notification_permission_requested", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            preferences.edit().putBoolean("notification_permission_requested", true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                try {
                    val track = repository.importAudio(uri)
                    val saved = preferences.getStringSet("imported_uris", emptySet()).orEmpty().toSet() + uri.toString()
                    preferences.edit().putStringSet("imported_uris", saved).apply()
                    library = (library + track).distinctBy { it.stableKey }
                    message = null
                    selectTrack(track)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = "无法打开音频：${failure.localizedMessage ?: "格式不支持"}" }
            }
        }
    }
    var lyricsTarget by remember { mutableStateOf<Track?>(null) }
    val lyricsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = lyricsTarget
        if (uri != null && target != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                try {
                    val updated = repository.attachLyrics(target, uri)
                    playback.updateTrack(updated)
                    library = library.map { if (it.stableKey == updated.stableKey) updated else it }
                    message = if (updated.performanceLines.isEmpty()) "这个文件里没有可用的歌词时间轴。" else null
                    sheet = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = "歌词读取失败：${failure.localizedMessage ?: "请选择 LRC 或 TTML 文件"}" }
            }
        }
    }
    LaunchedEffect(currentTrack?.stableKey) {
        val target = currentTrack ?: return@LaunchedEffect
        try {
            val tags = if (target.isOnline) onlineRepository.resolveMetadata(target) { artwork ->
                // Metadata resolves on IO; publish the cover without waiting for lyrics.
                withContext(Dispatchers.Main.immediate) {
                    val latest = playback.currentTrack?.takeIf { it.stableKey == target.stableKey }
                    if (latest != null && latest.artworkUri != artwork) {
                        playback.updateTrack(latest.copy(artworkUri = artwork))
                    }
                }
            } else repository.loadDetails(target)
            val latest = playback.currentTrack?.takeIf { it.stableKey == target.stableKey } ?: return@LaunchedEffect
            val detailed = if (target.isOnline) latest.copy(
                artworkUri = tags.artworkUri ?: latest.artworkUri,
                lines = tags.lines.ifEmpty { latest.lines },
            ) else tags
            playback.updateTrack(detailed)
            library = library.map { if (it.stableKey == detailed.stableKey) detailed else it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A missing tag/lyric must not stop playable audio. */ }
    }

    val revealControls: () -> Unit = { controlsVisible = true; interactionRevision++ }
    LaunchedEffect(lyricsVisible, playback.playing, interactionRevision, sheet, playerVisible) {
        controlsVisible = true
        if (playerVisible && lyricsVisible && playback.playing && sheet == null) {
            delay(4_000L)
            controlsVisible = false
        }
    }
    LaunchedEffect(playerVisible, lyricsVisible, controlsVisible, sheet) {
        onImmersiveChange(playerVisible && lyricsVisible && !controlsVisible && sheet == null)
        frameRateMonitor.setScene(when {
            sheet != null -> FrameRateMonitor.Scene.PANEL
            playerVisible && lyricsVisible -> FrameRateMonitor.Scene.LYRICS
            else -> FrameRateMonitor.Scene.PLAYER
        })
    }
    LaunchedEffect(playback, lifecycle) {
        val frameOwner = Any()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    if (playback.playing) withFrameNanos { playback.tick(it, frameOwner) } else delay(90L)
                }
            } finally {
                playback.releaseFrameClock(frameOwner)
            }
        }
    }
    BackHandler(playerVisible || onlineVisible || playlistVisible || settingsVisible || sheet != null) {
        when {
            sheet != null -> sheet = null
            lyricsVisible && playerVisible -> lyricsVisible = false
            queueVisible && playerVisible -> queueVisible = false
            playerVisible -> playerVisible = false
            settingsVisible -> settingsVisible = false
            playlistVisible -> playlistVisible = false
            else -> onlineVisible = false
        }
    }

    // Screens are stacked, never swapped: each layer keeps its state and moves on a spring.
    val playerProgress = remember { Animatable(0f) }
    var playerHeight by remember { mutableFloatStateOf(1f) }
    var playerDragging by remember { mutableStateOf(false) }
    var playerDragProgress by remember { mutableFloatStateOf(1f) }
    val dragHandoff = remember { booleanArrayOf(false) }
    val releaseVelocity = remember { floatArrayOf(0f) }
    val playerExpansion = { if (playerDragging || dragHandoff[0]) playerDragProgress else playerProgress.value }
    val onlineProgress = remember { Animatable(0f) }
    val settingsProgress = remember { Animatable(0f) }
    val settingsPresent by remember { derivedStateOf { settingsVisible || settingsProgress.value > .001f } }
    val settingsCovered by remember { derivedStateOf { settingsProgress.value >= .999f } }
    LaunchedEffect(settingsVisible) {
        settingsProgress.animateTo(if (settingsVisible) 1f else 0f, spring(dampingRatio = 1f, stiffness = 420f))
    }
    val playlistProgress = remember { Animatable(0f) }
    val playlistPresent by remember { derivedStateOf { playlistVisible || playlistProgress.value > .001f } }
    LaunchedEffect(playlistVisible) {
        playlistProgress.animateTo(if (playlistVisible) 1f else 0f, spring(dampingRatio = 1f, stiffness = 420f))
    }
    val playerPresent by remember { derivedStateOf { playerVisible || playerDragging || playerProgress.value > 0.001f } }
    val onlinePresent by remember { derivedStateOf { onlineVisible || onlineProgress.value > 0.001f } }
    LaunchedEffect(playerVisible, playerDragging) {
        if (playerDragging) return@LaunchedEffect
        val initialVelocity = if (dragHandoff[0]) {
            playerProgress.snapTo(playerDragProgress)
            dragHandoff[0] = false
            releaseVelocity[0]
        } else playerProgress.velocity
        // Critically damped both ways: an overshoot would flash a gap under the sheet.
        playerProgress.animateTo(
            if (playerVisible) 1f else 0f,
            if (playerVisible) spring(dampingRatio = 1f, stiffness = 280f) else spring(dampingRatio = 1f, stiffness = 380f),
            initialVelocity = initialVelocity,
        )
    }
    LaunchedEffect(onlineVisible) {
        onlineProgress.animateTo(if (onlineVisible) 1f else 0f, spring(dampingRatio = 1f, stiffness = 380f))
    }
    val sheetCorner = RoundedCornerShape(28.dp)
    val bottomBackdrop = remember { HazeState() }
    var barHeight by remember { mutableStateOf(0.dp) }
    val recordLibraryBackdrop = (!playerVisible || playerDragging) && !settingsCovered

    CompositionLocalProvider(LocalAnimatedBackground provides appPreferenceValues.animatedBackground) {
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { playerHeight = it.height.toFloat().coerceAtLeast(1f) }) {
        // Base world (library + online), receding like an iOS card while the player rises.
        Box(Modifier.fillMaxSize().graphicsLayer {
            val p = playerExpansion().coerceIn(0f, 1f)
            val scale = 1f - 0.07f * p
            scaleX = scale; scaleY = scale
            // Fully covered: let RenderThread skip the whole library.
            alpha = if (p > 0.999f) 0f else 1f - 0.25f * p
            shape = sheetCorner
            clip = p > 0.001f
        }.background(Color(0xFF0E0D11))) {
        // The source contains pages only: never sample the glass itself or the player.
        Box(Modifier.fillMaxSize().then(if (recordLibraryBackdrop) Modifier.hazeSource(bottomBackdrop) else Modifier)) {
        // Tabs cross-fade (they are siblings, not a push).
        Box(Modifier.fillMaxSize().graphicsLayer {
            val o = onlineProgress.value.coerceIn(0f, 1f)
            alpha = if (o > 0.999f) 0f else 1f - o
        }) {
            LibraryScreen(
                tracks = library,
                currentTrack = currentTrack,
                isPlaying = playback.playing,
                loading = loading,
                hasPermission = hasPermission,
                error = message ?: playback.error,
                onRequestPermission = {
                    val requested = preferences.getBoolean("audio_permission_requested", false)
                    val activity = context as? Activity
                    if (requested && !hasPermission && activity != null && !activity.shouldShowRequestPermissionRationale(audioPermission())) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    } else {
                        preferences.edit().putBoolean("audio_permission_requested", true).apply()
                        audioPermissionLauncher.launch(audioPermission())
                    }
                },
                onRefresh = { scanRevision++ },
                onSelect = { selectTrack(it) },
                onOpenPlayer = { if (playback.currentTrack != null) playerVisible = true },
                onTogglePlayback = playback::togglePlayback,
                onImport = { audioPicker.launch(arrayOf("audio/*")) },
                onOnline = { onlineVisible = true },
                onPlayList = { list -> list.firstOrNull()?.let { selectTrack(it, list) } },
                onShuffleList = { tracks -> tracks.randomOrNull()?.let { first ->
                    selectTrack(first, tracks)
                    playback.toggleShuffle()
                } },
                onNext = playback::next,
                onPlayNext = playback::playNext,
                onAddToQueue = playback::addToQueue,
                onSelectFromList = { track, tracks -> selectTrack(track, tracks) },
                onOverlayVisibilityChange = { libraryOverlayVisible = it },
                bottomInset = barHeight,
                isActive = !playerPresent && !onlineVisible && !playlistPresent && !settingsPresent && sheet == null && !playlistAddVisible,
                onPlaylists = { playlistVisible = true },
                playlistCount = savedPlaylists.size,
                onAddToPlaylist = { playlistAddTracks = it; playlistAddVisible = true },
                onOpenSettings = { focus.clearFocus(); keyboard?.hide(); settingsVisible = true },
            )
        }
        if (onlinePresent) Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = onlineProgress.value.coerceIn(0f, 1f)
        }.background(Color(0xFF0E0D11)).pointerInput(Unit) { detectTapGestures { } }) {
            OnlineMusicScreen(
                isActive = onlineVisible && !playerPresent && !settingsPresent && !playlistPresent && !playlistAddVisible && sheet == null,
                bottomInset = barHeight,
                currentTrack = currentTrack,
                sources = onlineSources,
                selectedSourceId = onlineSourceId,
                query = onlineQuery,
                results = onlineResults,
                saved = onlineSaved,
                loading = onlineLoading,
                error = onlineError,
                onBack = { onlineVisible = false },
                onQueryChange = { onlineQuery = it },
                onSourceChange = {
                    onlineSearchJob?.cancel()
                    onlineRevision++
                    onlineLoading = false
                    onlineSourceId = it
                    onlineResults = emptyList()
                    onlineError = null
                },
                onSearch = {
                    onlineSearchJob?.cancel()
                    onlineRevision++
                    val revision = onlineRevision
                    val source = onlineSources.firstOrNull { it.id == onlineSourceId && it.enabled }
                    if (source == null) {
                        onlineError = "该音源未启用"
                    } else {
                        onlineLoading = true
                        onlineError = null
                        onlineSearchJob = scope.launch {
                            try {
                                val result = onlineRepository.search(source.id, onlineQuery)
                                if (revision == onlineRevision) {
                                    onlineResults = result
                                    onlineError = null
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                if (revision == onlineRevision) onlineError = failure.localizedMessage ?: "在线搜索失败"
                            } finally {
                                if (revision == onlineRevision) onlineLoading = false
                            }
                        }
                    }
                },
                onPlay = { playOnlineTrack(it, listOf(it), false) },
                onPlayFromList = ::playOnlineTrack,
                onSave = { onlineSaved = onlineRepository.saveTrack(it) },
                onRemove = { onlineSaved = onlineRepository.removeTrack(it) },
                onDownload = { track ->
                    scope.launch {
                        try {
                            onlineRepository.download(track)
                            onlineError = "已提交系统下载；完成后可回资料库扫描刷新。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { onlineError = failure.localizedMessage ?: "下载失败" }
                    }
                },
                onAddToPlaylist = { playlistAddTracks = it; playlistAddVisible = true },
                onOverlayVisibilityChange = { onlineOverlayVisible = it },
                onOpenSettings = { focus.clearFocus(); keyboard?.hide(); settingsVisible = true },
            )
        }
        if (playlistPresent) Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * (1f - playlistProgress.value)
        }.background(Color(0xFF0E0D11)).pointerInput(Unit) { detectTapGestures {} }) {
            PlaylistScreen(
                repository = playlists, library = library, currentTrack = currentTrack,
                isPlaying = playback.playing, bottomInset = barHeight,
                isActive = playlistVisible && !playerPresent && !onlineVisible && !settingsPresent && sheet == null && !playlistAddVisible,
                onBack = { playlistVisible = false },
                onPlay = { track, tracks, name -> selectTrack(track, tracks, "播放列表：$name") },
                onShuffle = { tracks, name -> tracks.randomOrNull()?.let {
                    selectTrack(it, tracks, "播放列表：$name")
                    playback.toggleShuffle()
                } },
                onPlayNext = playback::playNextMany, onPlayLater = playback::addToQueueMany,
                onOverlayVisibilityChange = { playlistOverlayVisible = it },
            )
        }
        }
        if (!libraryOverlayVisible && !onlineOverlayVisible && !playlistOverlayVisible) GlassTabBar(
            backdrop = bottomBackdrop,
            track = currentTrack,
            isPlaying = playback.playing,
            onlineSelected = onlineVisible,
            onSelectLibrary = { focus.clearFocus(); keyboard?.hide(); onlineVisible = false },
            onSelectOnline = { focus.clearFocus(); keyboard?.hide(); playlistVisible = false; onlineVisible = true },
            onOpenPlayer = { if (playback.currentTrack != null) playerVisible = true },
            onTogglePlayback = playback::togglePlayback,
            onNext = playback::next,
            onHeightChange = { barHeight = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        }
        if (settingsPresent) Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * (1f - settingsProgress.value)
        }.background(Color.Black).pointerInput(Unit) { detectTapGestures {} }) {
            SettingsScreen(
                preferences = appPreferences,
                sources = onlineSources,
                hasAudioPermission = hasPermission,
                scanning = loading,
                bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                onBack = { settingsVisible = false },
                onScan = { if (hasPermission) scanRevision++ else audioPermissionLauncher.launch(audioPermission()) },
                onOpenSystemSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                },
            )
        }
        if (playerPresent && currentTrack != null) Box(Modifier.fillMaxSize().graphicsLayer {
            val p = playerExpansion().coerceIn(0f, 1f)
            translationY = (1f - p) * size.height
            shape = sheetCorner
            clip = p < 0.999f
        }) {
            PlayerScreen(
                title = currentTrack.title,
                artist = currentTrack.artist,
                positionMs = { playback.positionMs },
                durationMs = playback.durationMs,
                isPlaying = playback.playing,
                volume = playback.volume,
                isFavorite = playback.favorite,
                onTogglePlay = playback::togglePlayback,
                onSeek = playback::seek,
                onVolumeChange = playback::changeVolume,
                onFavorite = playback::toggleFavorite,
                onLyrics = { revealControls(); queueVisible = false; lyricsVisible = !lyricsVisible },
                onQueue = { revealControls(); lyricsVisible = false; queueVisible = !queueVisible },
                onMore = { frameRateMonitor.captureBeforeInfo(lyricsVisible); sheet = "more" },
                onQuality = { frameRateMonitor.captureBeforeInfo(lyricsVisible); sheet = "quality" },
                playbackItemKey = playback.currentQueueEntry?.id ?: currentTrack.stableKey,
                onOutput = { sheet = "output" },
                lyricsVisible = lyricsVisible,
                controlsVisible = controlsVisible,
                onRevealControls = revealControls,
                track = currentTrack,
                onPrevious = playback::previous,
                onNext = playback::next,
                queueVisible = queueVisible,
                queueContent = { modifier, _ ->
                    Box(modifier) {
                        QueueSheetContent(playback) { queueVisible = false }
                    }
                },
                onDismiss = { playerVisible = false },
                onDismissDrag = { delta ->
                    if (!playerDragging) {
                        playerDragProgress = playerProgress.value
                        playerDragging = true
                    }
                    playerDragProgress = (playerDragProgress - delta / playerHeight).coerceIn(0f, 1f)
                },
                onDismissRelease = { velocity ->
                    if (playerDragging) {
                        val predicted = playerDragProgress - velocity / playerHeight * .14f
                        playerVisible = predicted >= .78f
                        releaseVelocity[0] = -velocity / playerHeight
                        dragHandoff[0] = true
                        playerDragging = false
                    }
                },
                onDismissCancel = {
                    if (playerDragging) {
                        playerVisible = true
                        releaseVelocity[0] = 0f
                        dragHandoff[0] = true
                        playerDragging = false
                    }
                },
                lyricContent = { modifier, entrance ->
                    if (currentTrack.performanceLines.isNotEmpty()) {
                        LyricsViewport(
                            lines = currentTrack.performanceLines,
                            positionMs = { playback.positionMs },
                            isPlaying = playback.playing,
                            seekRevision = playback.seekRevision,
                            modifier = modifier,
                            entranceProgress = entrance,
                            onSeek = playback::seek,
                            onInteraction = revealControls,
                        )
                    }
                    // No lyrics: the stage simply stays empty.
                },
            )
            (message ?: playback.error)?.let { error ->
                BasicText(error, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 50.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xE6352931)).clickable { message = null }.padding(12.dp),
                    style = TextStyle(color = Color.White, fontSize = 13.sp))
            }
        }

        val kind = sheet ?: lastSheet
        @Composable fun ColumnScope.SheetContents(kind: String) {
                when (kind) {
                    "more" -> currentTrack?.let { track ->
                        SheetTrackHeading(track)
                        SheetActionDivider()
                        SheetActionGroup {
                            SheetActionRow(if (playback.favorite) "取消收藏" else "收藏歌曲", PlayerIconType.Star,
                                selected = playback.favorite, onClick = playback::toggleFavorite)
                            SheetActionDivider()
                            SheetActionRow("下一首播放", PlayerIconType.Next) {
                                playback.playNext(track)
                                sheet = null
                            }
                            SheetActionDivider()
                            SheetActionRow("查看播放队列", PlayerIconType.Queue) {
                                sheet = null; revealControls(); lyricsVisible = false; queueVisible = true
                            }
                            SheetActionDivider()
                            SheetActionRow("加入播放列表", PlayerIconType.AddToPlaylist) {
                                playlistAddTracks = listOf(track)
                                sheet = null
                                playlistAddVisible = true
                            }
                        }
                        if (track.isOnline) {
                            Spacer(Modifier.height(8.dp))
                            val saved = onlineSaved.any { it.stableKey == track.stableKey }
                            SheetActionGroup {
                                SheetActionRow(if (saved) "从在线歌单移除" else "加入在线歌单", PlayerIconType.AddToPlaylist) {
                                    onlineSaved = if (saved) onlineRepository.removeTrack(track) else onlineRepository.saveTrack(track)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SheetActionGroup {
                            SheetActionRow("分享歌曲", PlayerIconType.Share) {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    if (track.isOnline) {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "${track.title} — ${track.artist}")
                                    } else {
                                        type = track.mimeType ?: "audio/*"
                                        putExtra(Intent.EXTRA_STREAM, track.uri)
                                        clipData = android.content.ClipData.newRawUri(track.title, track.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                }
                                runCatching { context.startActivity(Intent.createChooser(share, "分享歌曲")) }
                                    .onFailure { message = "无法分享这首歌曲，请确认文件仍可访问。" }
                                sheet = null
                            }
                            SheetActionDivider()
                            SheetActionRow("歌曲信息", PlayerIconType.Info) {
                                sheet = "info"
                            }
                        }
                    }
                    "quality" -> currentTrack?.let { track ->
                        val quality = track.audioQuality
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MusicText(quality.badgeLabel ?: "音频信息", 17f, true)
                            SheetActionDivider()
                            AudioParameterRow("格式", quality.formatLabel)
                            if (quality.bitsPerSample > 0) AudioParameterRow("位深", "${quality.bitsPerSample}-bit")
                            if (quality.sampleRate > 0) {
                                val large = quality.sampleRate >= 1_000_000
                                val rate = java.math.BigDecimal.valueOf(quality.sampleRate.toLong())
                                    .divide(java.math.BigDecimal.valueOf(if (large) 1_000_000L else 1_000L))
                                    .stripTrailingZeros().toPlainString()
                                AudioParameterRow("采样率", "$rate ${if (large) "MHz" else "kHz"}")
                            }
                            if (quality.bitrate > 0) AudioParameterRow("平均码率", "${quality.bitrate / 1000} kbps")
                            if (quality.level == AudioQualityLevel.HiResLossless) {
                                MusicText("这里显示文件规格；实际高采样率输出取决于设备和音频链路，可能需要外接 DAC。", 12f)
                            }
                        }
                    }
                    "output" -> {
                        SheetHeading("音频输出", outputName())
                        Spacer(Modifier.height(16.dp))
                        MusicText("使用当前系统音频设备，连接耳机后自动跟随系统输出。")
                        Spacer(Modifier.height(20.dp))
                        MusicAction("蓝牙与音频设备") { sheet = null; onBluetoothSettings() }
                    }
                    else -> currentTrack?.let { track ->
                        SheetTrackHeading(track)
                        Spacer(Modifier.height(20.dp))
                        MusicText(track.album.ifBlank { "未知专辑" }, 14f)
                        Spacer(Modifier.height(8.dp))
                        val technical = "${track.audioQuality.description} · ${formatPlaybackTime(playback.durationMs)}"
                        MusicText(technical, 13f)
                        Spacer(Modifier.height(8.dp))
                        val sourceName = onlineSources.firstOrNull { it.id == track.sourceId }?.name ?: track.sourceId.orEmpty()
                        val origin = if (track.isOnline) "搜索来源：$sourceName · 第三方解析" else "本地歌曲"
                        MusicText(if (track.performanceLines.isEmpty()) "$origin · 暂无同步歌词" else "$origin · ${track.performanceLines.size} 行歌词", 13f)
                        Spacer(Modifier.height(20.dp))
                        MusicAction("选择歌词文件") { lyricsTarget = track; lyricsPicker.launch(arrayOf("*/*")) }
                        Spacer(Modifier.height(8.dp))
                        PerformanceDisclosure(frameRateMonitor)
                    }
                }
        }
        val contextKind = if (kind == "quality") "quality" else "more"
        AppContextMenu(visible = sheet == "more" || sheet == "quality", anchor = null, onDismiss = { sheet = null }) {
            SheetContents(contextKind)
        }
        AppSheet(visible = sheet != null && sheet != "more" && sheet != "quality", onDismiss = { sheet = null },
            title = when (kind) { "output" -> "音频输出"; else -> "歌曲信息" }) {
            SheetContents(if (kind == "more") "info" else kind)
        }
        PlaylistAddSheet(playlists, playlistAddTracks, playlistAddVisible) { playlistAddVisible = false }
    }
    }
}

@Composable
internal fun MusicText(text: String, size: Float = 14f, primary: Boolean = false, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = TextStyle(color = Color.White.copy(alpha = if (primary) 0.95f else 0.58f),
        fontSize = size.sp, lineHeight = (size * 1.5f).sp, fontFamily = PlayerTypography.familyFor(text),
        fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun AudioParameterRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MusicText(label, 13f, modifier = Modifier.weight(1f))
        MusicText(value, 14f, true, maxLines = 2)
    }
}

@Composable
private fun SheetHeading(title: String, subtitle: String) {
    MusicText(title, 23f, true)
    Spacer(Modifier.height(4.dp))
    MusicText(subtitle)
}

@Composable
internal fun MusicAction(label: String, onClick: () -> Unit) {
    SheetActionGroup { SheetActionRow(label, onClick = onClick) }
}

@Composable
private fun SheetTrackHeading(track: Track) {
    Row(Modifier.fillMaxWidth().then(if (LocalInsideContextMenu.current) Modifier.padding(14.dp) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        TrackArtwork(track, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), 144)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            MusicText(track.title, 16f, true, maxLines = 2)
            MusicText(track.artist, 13f, maxLines = 1)
        }
    }
}

@Composable
private fun QueueModeControl(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .background(if (active) LibraryAccent.copy(alpha = .18f) else Color.White.copy(alpha = .065f))
        .clickable(role = Role.Button, onClick = onClick).semantics { stateDescription = if (active) "已开启" else "已关闭" },
        contentAlignment = Alignment.Center) {
        BasicText(label, style = TextStyle(fontSize = 14.sp, color = if (active) LibraryAccent else Color.White,
            fontFamily = PlayerTypography.medium))
    }
}

@Composable
private fun PerformanceDisclosure(monitor: FrameRateMonitor) {
    var expanded by remember { mutableStateOf(false) }
    MusicAction(if (expanded) "收起性能读数" else "性能读数") { expanded = !expanded }
    if (expanded) {
        val sample = monitor.snapshot
        Spacer(Modifier.height(12.dp))
        MusicText("系统刷新率：${sample.refreshRateHz?.let { String.format(Locale.ROOT, "%.1f Hz", it) } ?: "待采样"}", 13f)
        monitor.beforeInfoSnapshot?.let { before ->
            val fps = before.framesPerSecond?.takeIf { before.status == FrameRateMonitor.Status.MEASURED }
            MusicText("打开前绘制帧率：${fps?.let { String.format(Locale.ROOT, "%.1f FPS", it) } ?: "没有有效采样"}", 13f)
        }
        MusicText("Hz 与 FPS 不是同一项指标。读数是短时间窗采样，不代表持续稳定帧率。", 12f)
    }
}
