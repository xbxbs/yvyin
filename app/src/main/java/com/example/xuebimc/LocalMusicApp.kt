package com.example.xuebimc

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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
    val view = LocalView.current
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
    var selectedTab by rememberSaveable { mutableStateOf(MusicTab.Library) }
    val onlineVisible = selectedTab == MusicTab.Online
    val settingsVisible = selectedTab == MusicTab.Settings
    val tabStates = rememberSaveableStateHolder()
    var compactBar by remember { mutableStateOf(false) }
    var libraryOverlayVisible by remember { mutableStateOf(false) }
    var onlineOverlayVisible by remember { mutableStateOf(false) }
    var playlistOverlayVisible by remember { mutableStateOf(false) }
    var settingsOverlayVisible by remember { mutableStateOf(false) }
    var downloadsVisible by rememberSaveable { mutableStateOf(false) }
    var downloadTarget by remember { mutableStateOf<Track?>(null) }
    var downloadMenuShowing by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Track?>(null) }
    var deletionShowing by remember { mutableStateOf(false) }
    val downloadPreparations = remember { mutableStateListOf<DownloadPreparation>() }
    var libraryOpenRequest by remember { mutableStateOf<LibraryOpenRequest?>(null) }
    var libraryOpenRevision by rememberSaveable { mutableLongStateOf(0L) }
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
    var relatedSearchRevision by rememberSaveable { mutableIntStateOf(0) }
    var onlineSearchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lyricsVisible by remember { mutableStateOf(false) }
    var queueVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var lastSheet by remember { mutableStateOf("more") }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionRevision by remember { mutableIntStateOf(0) }
    val currentTrack = playback.currentTrack
    val listeningStats by playback.listeningStats.snapshot.collectAsState()
    fun completeDeletion(track: Track, knownAliases: Set<String>) {
        val aliases = knownAliases + track.uri.toString()
        val deletedKeys = (library + playback.queue).filter { !it.isOnline && it.uri.toString() in aliases }
            .map { it.stableKey }.toSet() + track.stableKey
        synchronized(preferences) {
            val saved = preferences.getStringSet("imported_uris", emptySet()).orEmpty().toSet() - aliases
            preferences.edit().putStringSet("imported_uris", saved).apply {
                aliases.forEach { remove("imported_at:$it") }
            }.apply()
        }
        library = library.filterNot { it.stableKey in deletedKeys }
        playback.removeDeletedTracks(deletedKeys)
        repository.invalidateMetadata(track.uri)
        scanRevision++
        message = "已删除歌曲文件：${track.title}"
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }
    fun selectTab(tab: MusicTab) {
        focus.clearFocus()
        keyboard?.hide()
        if (selectedTab != tab) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        selectedTab = tab
        downloadsVisible = false
        downloadTarget = null
        if (tab == MusicTab.Library) scanRevision++
        compactBar = false
        if (tab != MusicTab.Library) playlistVisible = false
    }
    fun openDownloads() {
        focus.clearFocus()
        keyboard?.hide()
        downloadsVisible = true
        compactBar = false
    }
    fun beginDownload(request: DownloadPreparation, prepared: PreparedMusicDownload? = null) {
        downloadTarget = null
        openDownloads()
        val previous = downloadPreparations.indexOfFirst { it.key == request.key }
        if (previous >= 0 && downloadPreparations[previous].error == null) return
        val pending = request.copy(error = null)
        if (previous >= 0) downloadPreparations[previous] = pending else downloadPreparations.add(0, pending)
        scope.launch {
            try {
                val selected = prepared ?: onlineRepository.prepareDownload(pending.track, pending.quality)
                onlineRepository.download(selected, pending.embedCover, pending.embedLyrics)
                downloadPreparations.removeAll { it.key == pending.key }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val index = downloadPreparations.indexOfFirst { it.key == pending.key }
                if (index >= 0) downloadPreparations[index] = pending.copy(error = failure.localizedMessage ?: "无法开始下载，请稍后重试。")
            }
        }
    }
    fun performOnlineSearch() {
        onlineSearchJob?.cancel()
        val revision = ++onlineRevision
        val source = onlineSources.firstOrNull { it.id == onlineSourceId && it.enabled }
        val query = onlineQuery
        if (source == null) {
            onlineLoading = false
            onlineError = "该音源未启用"
            return
        }
        onlineLoading = true
        onlineError = null
        onlineSearchJob = scope.launch {
            try {
                val result = onlineRepository.search(source.id, query)
                if (revision == onlineRevision) { onlineResults = result; onlineError = null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (revision == onlineRevision) onlineError = failure.localizedMessage ?: "在线搜索失败" }
            finally { if (revision == onlineRevision) onlineLoading = false }
        }
    }
    fun openRelated(track: Track, artist: Boolean) {
        sheet = null
        if (track.isOnline) {
            val query = (if (artist) track.artist else track.album).trim()
            if (query.isBlank()) { message = "这首歌没有对应的${if (artist) "艺人" else "专辑"}信息。"; return }
            onlineSourceId = track.sourceId ?: onlineSourceId
            onlineQuery = query
            onlineResults = emptyList()
            relatedSearchRevision++
            selectTab(MusicTab.Online)
            performOnlineSearch()
        } else {
            libraryOpenRequest = LibraryOpenRequest(++libraryOpenRevision, track, artist)
            selectTab(MusicTab.Library)
        }
        playerVisible = false
    }
    LaunchedEffect(library) {
        playback.setAutoplayLibrary(library)
        playback.refreshLibraryMetadata(library)
    }

    LaunchedEffect(sheet) { sheet?.let { lastSheet = it } }

    LaunchedEffect(onlineVisible) {
        if (onlineVisible) {
            try {
                onlineSources = onlineRepository.loadSources()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
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
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = canReadAudio(context)
                val edited = MetadataEditor.consumeEditedUri()
                if (edited != null) scope.launch {
                    repository.invalidateMetadata(edited)
                    MetadataEditor.rescan(context, edited)
                    scanRevision++
                } else scanRevision++
                MusicDownloads.retryPending(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context, hasPermission) {
        var pendingRefresh: kotlinx.coroutines.Job? = null
        fun requestRefresh() {
            pendingRefresh?.cancel()
            pendingRefresh = scope.launch { delay(350L); scanRevision++ }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { requestRefresh() }
        }
        if (hasPermission) context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == MusicDownloads.ACTION_LIBRARY_CHANGED) requestRefresh()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(MusicDownloads.ACTION_LIBRARY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            pendingRefresh?.cancel()
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    LaunchedEffect(hasPermission, scanRevision) {
        loading = true
        try {
            val scanned = if (hasPermission) repository.scan() else emptyList()
            val imported = preferences.getStringSet("imported_uris", emptySet()).orEmpty().mapNotNull { value ->
                try { repository.importAudio(Uri.parse(value), preferences.getLong("imported_at:$value", 0L)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            val combined = repository.mergeLibrary(scanned, imported)
            library = withContext(Dispatchers.Default) {
                combined.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }
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
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        if (appPreferenceValues.autoOpenPlayer) playerVisible = true
        selectedTab = MusicTab.Library
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
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        if (appPreferenceValues.autoOpenPlayer) playerVisible = true
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
                    val addedAt = preferences.getLong("imported_at:$uri", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
                    val track = repository.importAudio(uri, addedAt)
                    synchronized(preferences) {
                        val saved = preferences.getStringSet("imported_uris", emptySet()).orEmpty().toSet() + uri.toString()
                        preferences.edit().putStringSet("imported_uris", saved)
                            .putLong("imported_at:$uri", addedAt).apply()
                    }
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
    LaunchedEffect(currentTrack?.stableKey, currentTrack?.metadataRevision) {
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
            val latest = playback.currentTrack?.takeIf {
                it.stableKey == target.stableKey && it.metadataRevision == target.metadataRevision
            } ?: return@LaunchedEffect
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
    BackHandler(playerVisible || onlineVisible || playlistVisible || settingsVisible || downloadsVisible || downloadTarget != null || sheet != null) {
        when {
            downloadTarget != null -> downloadTarget = null
            sheet != null -> sheet = null
            lyricsVisible && playerVisible -> lyricsVisible = false
            queueVisible && playerVisible -> queueVisible = false
            playerVisible -> playerVisible = false
            downloadsVisible -> { downloadsVisible = false; scanRevision++ }
            settingsVisible -> selectTab(MusicTab.Library)
            playlistVisible -> playlistVisible = false
            else -> selectTab(MusicTab.Library)
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
        settingsProgress.animateTo(if (settingsVisible) 1f else 0f, spring(dampingRatio = .85f, stiffness = 420f))
    }
    val playlistProgress = remember { Animatable(0f) }
    val playlistPresent by remember { derivedStateOf { playlistVisible || playlistProgress.value > .001f } }
    LaunchedEffect(playlistVisible) {
        playlistProgress.animateTo(if (playlistVisible) 1f else 0f, spring(dampingRatio = 1f, stiffness = 420f))
    }
    val playerPresent by remember { derivedStateOf { playerVisible || playerDragging || playerProgress.value > 0.001f } }
    val playerCovered by remember { derivedStateOf { playerExpansion() >= .999f } }
    val onlineCovered by remember { derivedStateOf { onlineProgress.value >= .999f } }
    val playlistCovered by remember { derivedStateOf { playlistProgress.value >= .999f } }
    val onlinePresent by remember { derivedStateOf { onlineVisible || onlineProgress.value > 0.001f } }
    LaunchedEffect(playerVisible, playerDragging) {
        if (playerDragging) return@LaunchedEffect
        // Let the newly composed endpoints register at p=0 before the shared container moves.
        if (playerVisible && playerProgress.value == 0f) withFrameNanos { }
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
        onlineProgress.animateTo(if (onlineVisible) 1f else 0f, spring(dampingRatio = .85f, stiffness = 380f))
    }
    val sheetCorner = RoundedCornerShape(28.dp)
    val bottomBackdrop = remember { HazeState() }
    val liquidBackdrop = rememberLayerBackdrop {
        // KernelSU also records an opaque page base before the body. Transparent capture
        // leaves old cover colours in the refraction when switching a dark destination.
        drawRect(Color.Black)
        drawContent()
    }
    var barHeight by remember { mutableStateOf(0.dp) }
    val recordLibraryBackdrop = !playerCovered

    CompositionLocalProvider(
        LocalAnimatedBackground provides appPreferenceValues.animatedBackground,
        LocalLiquidGlass provides appPreferenceValues.liquidGlass,
    ) {
    PlayerSurfaceTransition(playerExpansion, currentTrack?.stableKey) {
    SharedArtworkTransition(
        playerExpansion = playerExpansion,
        playerAtRootOrigin = true,
        enabled = !lyricsVisible && !queueVisible && sheet == null && !playlistAddVisible &&
            !libraryOverlayVisible && !onlineOverlayVisible && !playlistOverlayVisible && !settingsOverlayVisible && !downloadMenuShowing && !deletionShowing,
    ) {
    val playerTravelDistance = playerSurfaceTravelDistance { playerHeight }
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
        // Page bodies are sources; no ancestor ever captures a header's own blur/scrim.
        Box(Modifier.fillMaxSize().then(if (appPreferenceValues.liquidGlass) Modifier.layerBackdrop(liquidBackdrop) else Modifier)) {
        // Tabs cross-fade (they are siblings, not a push).
        Box(Modifier.fillMaxSize().graphicsLayer {
            val o = onlineProgress.value.coerceIn(0f, 1f)
            alpha = (1f - o) * (1f - settingsProgress.value.coerceIn(0f, 1f))
        }) {
            LibraryScreen(
                openRequest = libraryOpenRequest,
                listeningStats = listeningStats,
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
                onRefresh = { repository.invalidateMetadata(); scanRevision++ },
                onSelect = { selectTrack(it) },
                onOpenPlayer = { if (playback.currentTrack != null) playerVisible = true },
                onTogglePlayback = playback::togglePlayback,
                onImport = { audioPicker.launch(arrayOf("audio/*")) },
                onOnline = { selectTab(MusicTab.Online) },
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
                backdrop = bottomBackdrop,
                backdropVisible = recordLibraryBackdrop && !onlineCovered && !settingsCovered && !playlistCovered && !downloadsVisible,
                onScrollDirection = { if (selectedTab == MusicTab.Library) compactBar = it },
                isActive = !playerPresent && !onlineVisible && !playlistPresent && !settingsPresent && !downloadsVisible && sheet == null && !playlistAddVisible && deleteTarget == null,
                onPlaylists = { playlistVisible = true },
                onDownloads = ::openDownloads,
                onDeleteTrack = { deleteTarget = it },
                playlistCount = savedPlaylists.size,
                onAddToPlaylist = { playlistAddTracks = it; playlistAddVisible = true },
            )
        }
        if (onlinePresent) Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = onlineProgress.value.coerceIn(0f, 1f) * (1f - settingsProgress.value.coerceIn(0f, 1f))
        }.background(Color.Black).then(if (recordLibraryBackdrop && !settingsCovered) Modifier.hazeSource(bottomBackdrop, zIndex = 2f) else Modifier)
            .pointerInput(Unit) { detectTapGestures { } }) {
            tabStates.SaveableStateProvider("online") {
            OnlineMusicScreen(
                externalQueryRevision = relatedSearchRevision,
                isPlaying = playback.playing && onlineVisible && !playerCovered && !settingsCovered,
                onScrollDirection = { if (onlineVisible) compactBar = it },
                isActive = onlineVisible && !playerPresent && !settingsPresent && !playlistPresent && !downloadsVisible && downloadTarget == null && !playlistAddVisible && sheet == null,
                bottomInset = barHeight,
                currentTrack = currentTrack,
                sources = onlineSources,
                selectedSourceId = onlineSourceId,
                query = onlineQuery,
                results = onlineResults,
                saved = onlineSaved,
                loading = onlineLoading,
                error = onlineError,
                onBack = { selectTab(MusicTab.Library) },
                onQueryChange = { onlineQuery = it },
                onSourceChange = {
                    onlineSearchJob?.cancel()
                    onlineRevision++
                    onlineLoading = false
                    onlineSourceId = it
                    onlineResults = emptyList()
                    onlineError = null
                },
                onSearch = ::performOnlineSearch,
                onPlay = { playOnlineTrack(it, listOf(it), false) },
                onPlayFromList = ::playOnlineTrack,
                onSave = { onlineSaved = onlineRepository.saveTrack(it) },
                onRemove = { onlineSaved = onlineRepository.removeTrack(it) },
                onDownload = { track -> downloadTarget = track },
                onAddToPlaylist = { playlistAddTracks = it; playlistAddVisible = true },
                onOverlayVisibilityChange = { onlineOverlayVisible = it },
            )
            }
        }
        if (playlistPresent) Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * (1f - playlistProgress.value)
        }.background(Color.Black).then(if (recordLibraryBackdrop && !settingsCovered) Modifier.hazeSource(bottomBackdrop, zIndex = 2f) else Modifier)
            .pointerInput(Unit) { detectTapGestures {} }) {
            PlaylistScreen(
                repository = playlists, library = library, currentTrack = currentTrack,
                isPlaying = playback.playing, bottomInset = barHeight,
                isActive = playlistVisible && !playerPresent && !onlineVisible && !settingsPresent && !downloadsVisible && sheet == null && !playlistAddVisible,
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
        if (settingsPresent) Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = settingsProgress.value.coerceIn(0f, 1f)
        }.background(Color.Black)
            .pointerInput(Unit) { detectTapGestures {} }) {
            tabStates.SaveableStateProvider("settings") {
                SettingsScreen(
                    preferences = appPreferences, sources = onlineSources,
                    currentSourceId = onlineSourceId, bottomInset = barHeight,
                    isActive = settingsVisible && !playerPresent && !downloadsVisible,
                    onDownloads = ::openDownloads,
                    onOverlayVisibilityChange = { settingsOverlayVisible = it },
                    onBack = { selectTab(MusicTab.Library) },
                    onDeveloperOptions = { frameRateMonitor.captureBeforeInfo(lyricsVisible); sheet = "developer" },
                )
            }
        }
        if (downloadsVisible) Box(Modifier.fillMaxSize().background(Color.Black)
            .then(if (recordLibraryBackdrop) Modifier.hazeSource(bottomBackdrop, zIndex = 4f) else Modifier)
            .pointerInput(Unit) { detectTapGestures {} }) {
            DownloadsScreen(
                preparations = downloadPreparations.toList(), bottomInset = barHeight,
                isActive = !playerPresent && downloadTarget == null,
                onBack = { downloadsVisible = false; scanRevision++ },
                onBrowse = { selectTab(MusicTab.Online) },
                onOpen = { task -> task.documentUri?.let { value -> scope.launch {
                    try {
                        val track = repository.importAudio(Uri.parse(value))
                        downloadsVisible = false
                        selectTrack(track, listOf(track), "下载")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        android.widget.Toast.makeText(context, "文件已移动或无法读取，请检查下载目录。", android.widget.Toast.LENGTH_LONG).show()
                    }
                } } },
                onRetryPreparation = { beginDownload(it) },
                onDismissPreparation = { key -> downloadPreparations.removeAll { it.key == key && it.error != null } },
            )
        }
        }
        if (!libraryOverlayVisible && !onlineOverlayVisible && !playlistOverlayVisible && !settingsOverlayVisible && !downloadMenuShowing && !deletionShowing) GlassTabBar(
            backdrop = bottomBackdrop,
            liquidBackdrop = liquidBackdrop.takeIf { appPreferenceValues.liquidGlass },
            track = currentTrack,
            isPlaying = playback.playing,
            selectedTab = selectedTab,
            onSelectTab = ::selectTab,
            compact = compactBar && selectedTab != MusicTab.Settings,
            onExpand = { compactBar = false },
            onOpenPlayer = { if (playback.currentTrack != null) playerVisible = true },
            onTogglePlayback = playback::togglePlayback,
            onPrevious = playback::previous,
            onNext = playback::next,
            onHeightChange = { barHeight = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        }
        if (playerPresent && currentTrack != null) Box(Modifier.fillMaxSize().sharedPlayerSurface()) {
            PlayerScreen(
                title = currentTrack.title,
                artist = currentTrack.artist,
                positionMs = { playback.positionMs },
                durationMs = playback.durationMs,
                isPlaying = playback.playing,
                volume = playback.volume,
                isFavorite = playback.favorite,
                queueShuffled = playback.shuffled,
                queueRepeatMode = playback.repeatMode,
                queueAutoplayEnabled = playback.autoplayEnabled,
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
                    playerDragProgress = (playerDragProgress - delta / playerTravelDistance()).coerceIn(0f, 1f)
                },
                onDismissRelease = { velocity ->
                    if (playerDragging) {
                        val predicted = playerDragProgress - velocity / playerTravelDistance() * .14f
                        playerVisible = predicted >= .78f
                        releaseVelocity[0] = -velocity / playerTravelDistance()
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
                            SheetActionRow("下一首播放", PlayerIconType.Next) {
                                playback.playNext(track)
                                sheet = null
                            }
                            SheetActionDivider()
                            SheetActionRow("最后播放", PlayerIconType.Queue) {
                                playback.addToQueue(track)
                                sheet = null
                            }
                        }
                        SheetActionSectionDivider()
                        SheetActionGroup {
                            SheetActionRow("加入播放列表", PlayerIconType.AddToPlaylist) {
                                playlistAddTracks = listOf(track)
                                sheet = null
                                playlistAddVisible = true
                            }
                            SheetActionDivider()
                            SheetActionRow(if (playback.favorite) "取消收藏" else "收藏歌曲", PlayerIconType.Star,
                                filledStar = playback.favorite, onClick = playback::toggleFavorite)
                            if (!track.isOnline) {
                                SheetActionDivider()
                                SheetActionRow("编辑元数据", PlayerIconType.Info) {
                                    message = MetadataEditor.open(context, track)
                                    message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
                                    sheet = null
                                }
                            }
                        }
                        if (track.isOnline) {
                            SheetActionDivider()
                            val saved = onlineSaved.any { it.stableKey == track.stableKey }
                            SheetActionGroup {
                                SheetActionRow(if (saved) "从在线歌单移除" else "加入在线歌单", PlayerIconType.AddToPlaylist) {
                                    onlineSaved = if (saved) onlineRepository.removeTrack(track) else onlineRepository.saveTrack(track)
                                }
                            }
                        }
                        SheetActionSectionDivider()
                        SheetActionGroup {
                            SheetActionRow(if (track.isOnline) "搜索专辑" else "前往专辑", PlayerIconType.Album) { openRelated(track, false) }
                            SheetActionDivider()
                            SheetActionRow(if (track.isOnline) "搜索艺人" else "前往艺人", PlayerIconType.Artist) { openRelated(track, true) }
                            SheetActionDivider()
                            SheetActionRow("查看播放队列", PlayerIconType.Queue) {
                                sheet = null; revealControls(); lyricsVisible = false; queueVisible = true
                            }
                        }
                        SheetActionSectionDivider()
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
                    "output" -> {
                        MusicText("音频输出", 17f, true, modifier = Modifier.padding(16.dp))
                        SheetActionDivider()
                        PlayerInformationRow("当前设备", outputName())
                        SheetActionSectionDivider()
                        MusicDetailAction("蓝牙与音频设备", PlayerIconType.AirPlay) { sheet = null; onBluetoothSettings() }
                    }
                    "developer" -> {
                        MusicText("开发者选项", 17f, true, modifier = Modifier.padding(16.dp))
                        SheetActionDivider()
                        PerformanceReadout(frameRateMonitor)
                    }
                    else -> currentTrack?.let { track ->
                        val sourceName = onlineSources.firstOrNull { it.id == track.sourceId }?.name ?: track.sourceId.orEmpty()
                        PlayerTrackInformation(track, playback.durationMs, if (track.isOnline) sourceName else "本地歌曲") {
                            lyricsTarget = track
                            sheet = null
                            lyricsPicker.launch(arrayOf("*/*"))
                        }
                    }
                }
        }
        AppContextMenu(visible = sheet != null, anchor = null, onDismiss = { sheet = null },
            presentation = if (kind == "more") ContextMenuPresentation.Menu else ContextMenuPresentation.Information,
            title = when (kind) { "more" -> "歌曲菜单"; "output" -> "音频输出"; "developer" -> "开发者选项"; else -> "歌曲信息" }) {
            Column(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
                SheetContents(kind)
            }
        }
        PlaylistAddSheet(playlists, playlistAddTracks, playlistAddVisible) { playlistAddVisible = false }
        DownloadQualityMenu(downloadTarget, onlineSources, appPreferenceValues,
            onDismiss = { downloadTarget = null }, onShowingChanged = { downloadMenuShowing = it },
            onDownload = { prepared ->
                val track = prepared.track
                val quality = prepared.quality
                beginDownload(DownloadPreparation("${track.stableKey}:${quality.level}", track, quality,
                    appPreferenceValues.downloadEmbedCover, appPreferenceValues.downloadEmbedLyrics), prepared)
            })
        TrackDeletionDialog(deleteTarget, onDismiss = { deleteTarget = null },
            onDeleted = ::completeDeletion,
            resolveAliases = { track ->
                val imported = synchronized(preferences) {
                    preferences.getStringSet("imported_uris", emptySet()).orEmpty().toSet()
                }
                repository.aliasesFor(track, imported + (library + playback.queue).filterNot { it.isOnline }.map { it.uri.toString() })
            },
            onFeedback = { feedback ->
                message = feedback
                android.widget.Toast.makeText(context, feedback, android.widget.Toast.LENGTH_LONG).show()
            }, onShowingChanged = { deletionShowing = it })
    }
    }
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
    SheetTrackHeader(track)
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
private fun PerformanceReadout(monitor: FrameRateMonitor) {
    Column(Modifier.padding(16.dp)) {
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
