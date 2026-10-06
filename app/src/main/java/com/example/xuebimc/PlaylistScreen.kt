@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.animation.ExperimentalAnimationApi::class)

package com.example.xuebimc

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val PlaylistMuted = Color(0xFFA4A0A7)
private val PlaylistSurface = Color(0xFF1C1A1E)

private sealed interface PlaylistPopup {
    data class Info(val playlist: MusicPlaylist) : PlaylistPopup
    data class Song(val playlist: MusicPlaylist, val entry: PlaylistEntry) : PlaylistPopup
    data class Sort(val playlist: MusicPlaylist) : PlaylistPopup
    data class Batch(val playlist: MusicPlaylist, val ids: Set<String>) : PlaylistPopup
}

private sealed interface PlaylistPanel {
    data object Create : PlaylistPanel
    data class Edit(val playlist: MusicPlaylist) : PlaylistPanel
    data class Songs(val id: String) : PlaylistPanel
    data class Transfer(val id: String, val ids: Set<String>) : PlaylistPanel
    data class Remove(val id: String, val ids: Set<String>) : PlaylistPanel
    data class Delete(val playlist: MusicPlaylist) : PlaylistPanel
}

private data class PlaylistRouteSnapshot(
    val playlist: MusicPlaylist?, val editing: Boolean, val searching: Boolean,
    val query: String, val selection: Set<String>,
)

@Stable
private class PlaylistTask(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var retry by mutableStateOf<(() -> Unit)?>(null)
        private set

    fun run(label: String, success: String = "已$label", action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = "正在$label…"
        retry = null
        scope.launch {
            try {
                action()
                if (success.isNotEmpty()) message = success
                else if (message == "正在$label…") message = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = "${label}失败：${failure.message ?: "请重试"}"
                retry = { run(label, success, action) }
            } finally {
                busy = false
            }
        }
    }
}

@Composable
private fun rememberPlaylistTask(): PlaylistTask {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlaylistTask(scope) }
}

/** Unit-returning repository methods report disk failures through error, not exceptions. */
private suspend fun PlaylistRepository.checked(action: suspend PlaylistRepository.() -> Unit) {
    check(ready.value) { error.value ?: "歌单尚未读取完成" }
    action()
    error.value?.let { throw IllegalStateException(it) }
}

private fun PlaylistRepository.requirePlaylist(id: String): MusicPlaylist =
    playlists.value.firstOrNull { it.id == id } ?: error("歌单已不存在，请返回列表")

@Composable
fun PlaylistScreen(
    repository: PlaylistRepository,
    library: List<Track>,
    currentTrack: Track?,
    isPlaying: Boolean,
    bottomInset: Dp,
    isActive: Boolean,
    onBack: () -> Unit,
    onPlay: (Track, List<Track>, String) -> Unit,
    onShuffle: (List<Track>, String) -> Unit,
    onPlayNext: (List<Track>) -> Unit,
    onPlayLater: (List<Track>) -> Unit,
    onOverlayVisibilityChange: (Boolean) -> Unit = {},
) {
    val playlists by repository.playlists.collectAsState()
    val ready by repository.ready.collectAsState()
    val storeError by repository.error.collectAsState()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val task = rememberPlaylistTask()
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    val playlist = playlists.firstOrNull { it.id == openedId }
    var editing by rememberSaveable(openedId) { mutableStateOf(false) }
    var searching by rememberSaveable(openedId) { mutableStateOf(false) }
    var query by rememberSaveable(openedId) { mutableStateOf("") }
    var selection by remember(openedId) { mutableStateOf(emptySet<String>()) }
    var popup by remember { mutableStateOf<PlaylistPopup?>(null) }
    var popupVisible by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    var panel by remember { mutableStateOf<PlaylistPanel?>(null) }
    var panelVisible by remember { mutableStateOf(false) }
    var popupShowing by remember { mutableStateOf(false) }
    var panelShowing by remember { mutableStateOf(false) }
    var coverTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var importTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val availability = remember { mutableStateMapOf<String, Boolean>() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var availabilityRevision by remember { mutableIntStateOf(0) }
    val motion = rememberLibraryMotionAllowed() && isActive

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { availability.clear(); availabilityRevision++ }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notifyOverlay by rememberUpdatedState(onOverlayVisibilityChange)
    SideEffect { notifyOverlay(isActive && (popupShowing || panelShowing)) }
    DisposableEffect(Unit) { onDispose { notifyOverlay(false) } }
    LaunchedEffect(isActive) {
        if (!isActive) { popupVisible = false; panelVisible = false; focus.clearFocus() }
    }
    LaunchedEffect(playlist?.entries) {
        val existing = playlist?.entries?.mapTo(HashSet()) { it.id }.orEmpty()
        selection = selection.intersect(existing)
    }

    fun showPopup(value: PlaylistPopup, bounds: Rect?) {
        focus.clearFocus(); popup = value; anchor = bounds; popupVisible = true
    }
    fun showPanel(value: PlaylistPanel) {
        focus.clearFocus(); popupVisible = false; panel = value; panelVisible = true
    }
    fun back() {
        when {
            popupVisible -> popupVisible = false
            panelVisible -> panelVisible = false
            editing -> { editing = false; if (task.retry == null) task.message = "已退出编辑，选择已保留" }
            searching -> { searching = false; query = ""; focus.clearFocus() }
            openedId != null -> openedId = null
            else -> onBack()
        }
    }
    BackHandler(isActive) { back() }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = coverTarget
        if (uri != null && id != null) task.run("更新封面") {
            withContext(Dispatchers.IO) {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                check(options.outWidth > 0 && options.outHeight > 0) { "无法读取这张图片，请重新选择" }
            }
            repository.checked { requirePlaylist(id); setCustomCover(id, uri.toString()) }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = importTarget
        if (uris.isNotEmpty() && id != null) {
            val batch = PlaylistImportBatch(uris.distinct())
            task.run("导入歌曲") { batch.importInto(context, repository, id) }
        }
    }
    fun importAudio(id: String) {
        if (task.busy) return
        importTarget = id; panelVisible = false; popupVisible = false
        try { audioPicker.launch(arrayOf("audio/*")) }
        catch (failure: Exception) { task.message = "无法打开文件选择器：${failure.message}" }
    }
    fun changeCover(id: String) {
        if (task.busy) return
        coverTarget = id; popupVisible = false
        try { coverPicker.launch(arrayOf("image/*")) }
        catch (failure: Exception) { task.message = "无法打开图片选择器：${failure.message}" }
    }
    fun queueTracks(tracks: List<Track>, next: Boolean) {
        popupVisible = false
        task.run(if (next) "加入下一首" else "加入队列末尾") {
            val playable = playlistPlayable(context, tracks, availability)
            check(playable.size == tracks.size) { "有 ${tracks.size - playable.size} 首文件不可读；未加入队列，请取消选择缺失条目后重试。歌单条目仍保留" }
            check(playable.isNotEmpty()) { "请先选择歌曲" }
            if (next) onPlayNext(playable) else onPlayLater(playable)
        }
    }
    fun play(tracks: List<Track>, first: Track?, title: String, shuffle: Boolean = false) {
        task.run("准备播放", success = "") {
            if (shuffle) {
                // No descriptor scan: the engine handles unknown/unavailable items as it plays.
                val queue = tracks.filter { availability[it.stableKey] != false }
                check(queue.isNotEmpty()) { "没有可播放的条目；缺失文件仍保留在歌单中" }
                onShuffle(queue, title)
                if (queue.size != tracks.size) task.message = "跳过 ${tracks.size - queue.size} 个已知不可读条目"
            } else {
                var chosen = first
                if (chosen != null) {
                    val readable = playlistReadable(context, chosen)
                    availability[chosen.stableKey] = readable
                    check(readable) { "文件缺失或读取权限失效，条目仍保留" }
                } else {
                    // Stop at the first readable item. Never preflight the rest of the playlist.
                    for (candidate in tracks) {
                        if (availability[candidate.stableKey] == false) continue
                        val readable = playlistReadable(context, candidate)
                        availability[candidate.stableKey] = readable
                        if (readable) { chosen = candidate; break }
                    }
                }
                val start = checkNotNull(chosen) { "没有可读取的歌曲；缺失条目仍保留，未删除音频" }
                val queue = tracks.filter { availability[it.stableKey] != false }
                check(queue.any { it === start }) { "所选条目已不在当前播放列表中" }
                onPlay(start, queue, title)
                if (queue.size != tracks.size) task.message = "跳过 ${tracks.size - queue.size} 个已知不可读条目"
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AnimatedContent(
            targetState = openedId,
            modifier = Modifier.fillMaxSize().statusBarsPadding().clipToBounds(),
            transitionSpec = {
                (EnterTransition.None togetherWith ExitTransition.None).apply {
                    targetContentZIndex = if (targetState == null) 0f else 1f
                }.using(null)
            }, label = "playlist route push",
        ) { routeId ->
            val isTarget = routeId == openedId
            var retained by remember(routeId) {
                mutableStateOf(PlaylistRouteSnapshot(playlists.firstOrNull { it.id == routeId }, editing, searching, query, selection))
            }
            val page = if (isTarget) PlaylistRouteSnapshot(
                playlists.firstOrNull { it.id == routeId }, editing, searching, query, selection,
            ) else retained
            SideEffect {
                if (isTarget && (routeId == null || page.playlist != null)) retained = page
            }
            // This child animation belongs to AnimatedContent's single route transition:
            // outgoing content stays alive, and a rapid Back reverses its current velocity.
            val push = transition.animateFloat(
                transitionSpec = { if (motion) spring(dampingRatio = 1f, stiffness = 450f) else snap() },
                label = "playlist route translation",
            ) { state ->
                if (state == EnterExitState.Visible) 0f else if (routeId == null) -.23f else 1f
            }
            val inactive = if (isTarget) Modifier else Modifier.clearAndSetSemantics { }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            val playlist = page.playlist
            Column(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * push.value }
                .background(Color.Black).then(inactive)) {
            if (routeId == null) {
                PlaylistIndex(
                    playlists, ready, storeError, bottomInset,
                    onBack = ::back, onCreate = { if (ready && !task.busy) showPanel(PlaylistPanel.Create) },
                    onOpen = { openedId = it },
                    onMenu = { item, bounds -> showPopup(PlaylistPopup.Info(item), bounds) },
                )
            } else if (playlist == null) {
                PlaylistNavigation("歌单", false, ::back)
                PlaylistText(storeError ?: if (ready) "歌单已不存在" else "正在读取歌单…", Modifier.padding(24.dp), color = if (storeError != null) LibraryAccent else PlaylistMuted)
            } else {
                key(playlist.id) { PlaylistDetail(
                    playlist, currentTrack, isPlaying, bottomInset, page.editing, page.searching, page.query,
                    page.selection, task.busy || !ready || !isTarget, motion && isTarget, isActive && isTarget, availability, availabilityRevision,
                    onBack = ::back,
                    onSearch = { searching = true }, onQuery = { query = it },
                    onCancelSearch = { searching = false; query = ""; focus.clearFocus() },
                    onMenu = { bounds -> showPopup(PlaylistPopup.Info(playlist), bounds) },
                    onSort = { bounds -> showPopup(PlaylistPopup.Sort(playlist), bounds) },
                    onFavorite = { task.run("更新收藏") { repository.checked { toggleFavorite(playlist.id) } } },
                    onEdit = { editing = !editing },
                    onSelect = { id -> selection = if (id in selection) selection - id else selection + id },
                    onSelectAll = { ids -> selection = if (ids.all { it in selection }) selection - ids.toSet() else selection + ids },
                    onBatch = { bounds -> showPopup(PlaylistPopup.Batch(playlist, selection), bounds) },
                    onAdd = { showPanel(PlaylistPanel.Songs(playlist.id)) },
                    onImport = { importAudio(playlist.id) },
                    onPlay = { track, tracks -> play(tracks, track, playlist.title) },
                    onShuffle = { tracks -> play(tracks, null, playlist.title, true) },
                    onTrackMenu = { entry, bounds -> showPopup(PlaylistPopup.Song(playlist, entry), bounds) },
                    onReorder = { ids ->
                        task.run("保存顺序") { repository.checked { requirePlaylist(playlist.id); reorder(playlist.id, ids) } }
                    },
                    onEnableReorder = {
                        query = ""; searching = false; focus.clearFocus()
                        task.run("切换为自定义顺序") { repository.checked { setSort(playlist.id, PlaylistSort.Custom, true) } }
                    },
                ) }
            }
            }
        }
        PlaylistFeedback(task, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 8.dp))
        // Payloads deliberately outlive visible: AppSheet/AppContextMenu retain exit content.
        PlaylistPopupContent(
            popup, popupVisible && isActive, anchor, task.busy || !ready,
            onDismiss = { popupVisible = false }, onShowing = { popupShowing = it },
            onEdit = { showPanel(PlaylistPanel.Edit(it)) },
            onAdd = { showPanel(PlaylistPanel.Songs(it.id)) },
            onCover = { changeCover(it.id) },
            onResetCover = { item -> popupVisible = false; task.run("恢复自动封面") { repository.checked { setCustomCover(item.id, null) } } },
            onDelete = { showPanel(PlaylistPanel.Delete(it)) },
            onFavorite = { item -> popupVisible = false; task.run("更新收藏") { repository.checked { toggleFavorite(item.id) } } },
            onQueue = ::queueTracks,
            onRemove = { id, ids -> showPanel(PlaylistPanel.Remove(id, ids)) },
            onTransfer = { id, ids -> showPanel(PlaylistPanel.Transfer(id, ids)) },
            onShare = { track -> popupVisible = false; task.run("分享", success = "") { sharePlaylistTrack(context, track) } },
            onSort = { item, sort, ascending -> popupVisible = false; task.run("排序") { repository.checked { setSort(item.id, sort, ascending) } } },
        )
        PlaylistPanelContent(
            panel, panelVisible && isActive, repository, playlists, library, task,
            onDismiss = { panelVisible = false }, onShowing = { panelShowing = it },
            onCreated = { id -> openedId = id; panelVisible = false },
            onImport = ::importAudio,
            onDeleted = { id -> if (openedId == id) openedId = null; panelVisible = false },
        )
    }
}

private class PlaylistAnchor { var bounds: Rect? = null }
private enum class PlaylistGlyph { Back, Plus, Search, Close, Shuffle, Sort, Check, Handle, Chevron, Trash, Transfer, Up, Down }

@Composable
private fun PlaylistText(
    text: String, modifier: Modifier = Modifier, size: Int = 16,
    color: Color = Color.White, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    BasicText(text, modifier, style = TextStyle(
        color = color, fontSize = size.sp, lineHeight = (size * 1.3f).sp,
        fontFamily = if (bold) PlayerTypography.medium else PlayerTypography.latin,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, textAlign = align ?: TextAlign.Start,
    ), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun PlaylistSymbol(symbol: PlaylistGlyph, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(22.dp)) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x * sx, y * sy), Offset(x2 * sx, y2 * sy), 1.8f * sx, StrokeCap.Round)
        when (symbol) {
            PlaylistGlyph.Back -> { line(15f, 4f, 7f, 12f); line(7f, 12f, 15f, 20f) }
            PlaylistGlyph.Chevron -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
            PlaylistGlyph.Plus -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            PlaylistGlyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            PlaylistGlyph.Check -> { line(4f, 12f, 9f, 17f); line(9f, 17f, 20f, 6f) }
            PlaylistGlyph.Search -> {
                drawCircle(color, 6.6f * sx, Offset(10f * sx, 10f * sy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * sx))
                line(15f, 15f, 21f, 21f)
            }
            PlaylistGlyph.Handle -> { line(5f, 7f, 19f, 7f); line(5f, 12f, 19f, 12f); line(5f, 17f, 19f, 17f) }
            PlaylistGlyph.Sort -> { line(5f, 5f, 19f, 5f); line(5f, 12f, 15f, 12f); line(5f, 19f, 10f, 19f) }
            PlaylistGlyph.Trash -> {
                line(4f, 6f, 20f, 6f); line(9f, 3f, 15f, 3f)
                line(6f, 6f, 7f, 21f); line(7f, 21f, 17f, 21f); line(17f, 21f, 18f, 6f)
                line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
            }
            PlaylistGlyph.Transfer -> {
                line(3f, 7f, 21f, 7f); line(17f, 3f, 21f, 7f); line(21f, 7f, 17f, 11f)
                line(21f, 17f, 3f, 17f); line(7f, 13f, 3f, 17f); line(3f, 17f, 7f, 21f)
            }
            PlaylistGlyph.Up, PlaylistGlyph.Down -> {
                val tip = if (symbol == PlaylistGlyph.Up) 4f else 20f
                val wing = if (symbol == PlaylistGlyph.Up) 10f else 14f
                line(12f, 4f, 12f, 20f); line(6f, wing, 12f, tip); line(12f, tip, 18f, wing)
            }
            PlaylistGlyph.Shuffle -> {
                line(3f, 6f, 6f, 6f); line(6f, 6f, 17f, 18f); line(17f, 18f, 21f, 18f)
                line(3f, 18f, 6f, 18f); line(6f, 18f, 17f, 6f); line(17f, 6f, 21f, 6f)
                line(18f, 3f, 21f, 6f); line(21f, 6f, 18f, 9f)
                line(18f, 15f, 21f, 18f); line(21f, 18f, 18f, 21f)
            }
        }
    }
}

@Composable
private fun PlaylistButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, glyph: PlaylistGlyph? = null, icon: PlayerIconType? = null,
    filled: Boolean = false,
) {
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val tint = if (enabled) LibraryAccent else PlaylistMuted.copy(alpha = .5f)
        when {
            glyph != null -> PlaylistSymbol(glyph, color = tint)
            icon != null -> PlayerIcon(icon, Modifier.size(22.dp), tint = tint, filled = filled)
            else -> PlaylistText(label, Modifier.padding(horizontal = 10.dp), color = tint, size = 15, maxLines = 1)
        }
    }
}

@Composable
private fun PlaylistNavigation(title: String, collapsed: Boolean, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color.Black)) {
        if (collapsed) PlaylistText(title, Modifier.align(Alignment.Center).padding(horizontal = 110.dp), size = 17, bold = true, maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistButton("返回", onBack, glyph = PlaylistGlyph.Back)
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

@Composable
private fun PlaylistIndex(
    playlists: List<MusicPlaylist>, ready: Boolean, storeError: String?, bottomInset: Dp,
    onBack: () -> Unit, onCreate: () -> Unit, onOpen: (String) -> Unit,
    onMenu: (MusicPlaylist, Rect?) -> Unit,
) {
    val state = rememberLazyListState()
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 } }
    PlaylistNavigation("歌单", collapsed, onBack) { PlaylistButton("新建歌单", onCreate, enabled = ready, glyph = PlaylistGlyph.Plus) }
    LazyColumn(state = state, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomInset + 88.dp)) {
        item("index:title") { PlaylistText("歌单", Modifier.padding(top = 12.dp, bottom = 20.dp), size = 34, bold = true) }
        item("index:create") {
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(16.dp))
                .background(PlaylistSurface).clickable(enabled = ready, onClick = onCreate).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaylistSymbol(PlaylistGlyph.Plus, color = LibraryAccent)
                Spacer(Modifier.width(16.dp)); PlaylistText("新建歌单", color = LibraryAccent, bold = true)
            }
            Spacer(Modifier.height(16.dp))
        }
        if (storeError != null) item("index:error") { PlaylistText(storeError, Modifier.padding(vertical = 16.dp), color = LibraryAccent) }
        if (!ready) item("index:loading") { PlaylistText(if (storeError == null) "正在读取歌单…" else "数据受保护，暂不可编辑。请处理存储问题后重新打开。", color = PlaylistMuted) }
        else if (playlists.isEmpty()) item("index:empty") {
            PlaylistText("为喜欢的音乐留一个位置", Modifier.padding(top = 30.dp), size = 22, bold = true)
            PlaylistText("新建后可从资料库添加，或导入设备中的音频。", Modifier.padding(top = 10.dp), color = PlaylistMuted)
        }
        items(playlists, key = { it.id }, contentType = { "playlist" }) { item ->
            val rowAnchor = remember { PlaylistAnchor() }
            val moreAnchor = remember { PlaylistAnchor() }
            Row(Modifier.fillMaxWidth().onGloballyPositioned { rowAnchor.bounds = it.boundsInRoot() }
                .combinedClickable(onClick = { onOpen(item.id) }, onLongClick = { onMenu(item, rowAnchor.bounds) })
                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaylistArtwork(item, Modifier.size(76.dp), requestSize = 128)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    PlaylistText(item.title, size = 19, bold = true, maxLines = 2)
                    PlaylistText("${item.entries.size} 首 · ${item.creator}", Modifier.padding(top = 4.dp), size = 13, color = PlaylistMuted, maxLines = 1)
                    if (item.subtitle.isNotBlank()) PlaylistText(item.subtitle, size = 13, color = PlaylistMuted, maxLines = 1)
                }
                if (item.favorite) PlayerIcon(PlayerIconType.Star, Modifier.size(16.dp), tint = LibraryAccent, filled = true)
                PlaylistButton("${item.title}的更多操作", { onMenu(item, moreAnchor.bounds) }, Modifier.onGloballyPositioned { moreAnchor.bounds = it.boundsInRoot() }, icon = PlayerIconType.More)
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: MusicPlaylist, currentTrack: Track?, isPlaying: Boolean, bottomInset: Dp,
    editing: Boolean, searching: Boolean, query: String, selection: Set<String>, busy: Boolean,
    motion: Boolean, isActive: Boolean, availability: MutableMap<String, Boolean>, availabilityRevision: Int,
    onBack: () -> Unit, onSearch: () -> Unit, onQuery: (String) -> Unit, onCancelSearch: () -> Unit,
    onMenu: (Rect?) -> Unit, onSort: (Rect?) -> Unit, onFavorite: () -> Unit, onEdit: () -> Unit,
    onSelect: (String) -> Unit, onSelectAll: (List<String>) -> Unit, onBatch: (Rect?) -> Unit,
    onAdd: () -> Unit, onImport: () -> Unit,
    onPlay: (Track?, List<Track>) -> Unit, onShuffle: (List<Track>) -> Unit,
    onTrackMenu: (PlaylistEntry, Rect?) -> Unit, onReorder: (List<String>) -> Unit, onEnableReorder: () -> Unit,
) {
    val list = rememberLazyListState()
    val ids = remember(playlist.entries) { playlist.entries.map { it.id } }
    val byId = remember(playlist.entries) { playlist.entries.associateBy { it.id } }
    val reorder = rememberPlaylistReorderState(list, ids)
    val filtered = remember(playlist.entries, playlist.sort, playlist.ascending, reorder.order, query) {
        val ordered = if (playlist.sort == PlaylistSort.Custom) reorder.order.mapNotNull(byId::get)
        else sortedPlaylistEntries(playlist)
        val term = query.trim()
        if (term.isEmpty()) ordered else ordered.filter {
            it.track.title.contains(term, true) || it.track.artist.contains(term, true) || it.track.album.contains(term, true)
        }
    }
    // Playback resolves reference identity before stableKey. A distinct Track per occurrence
    // lets the unchanged public callback select the second/third copy of the same recording.
    val tracks = remember(filtered) { filtered.map { it.track.copy() } }
    val visibleIndex = remember(filtered) { filtered.mapIndexed { index, entry -> entry.id to index }.toMap() }
    val dragEnabled = editing && !searching && query.isBlank() && playlist.sort == PlaylistSort.Custom && !busy && isActive
    LaunchedEffect(dragEnabled) { if (!dragEnabled) reorder.cancel() }
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset >= titleBottom } }
    val menuAnchor = remember { PlaylistAnchor() }
    val sortAnchor = remember { PlaylistAnchor() }
    val batchAnchor = remember { PlaylistAnchor() }
    PlaylistNavigation(playlist.title, collapsed, onBack) {
        PlaylistButton("搜索歌单", onSearch, glyph = PlaylistGlyph.Search)
        PlaylistButton(if (editing) "完成" else "编辑", onEdit, enabled = !busy)
    }
    if (searching) PlaylistSearch(query, onQuery, onCancelSearch, "搜索标题、艺人或专辑")
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 100.dp)) {
        item("playlist:header", contentType = "header") {
            Box(Modifier.fillMaxWidth()) {
                PlaylistHeaderBackdrop(playlist, motion && isPlaying, Modifier.fillMaxWidth().height(470.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PlaylistArtwork(playlist, Modifier.widthIn(max = 260.dp).fillMaxWidth(.7f), requestSize = 512)
                    PlaylistText(playlist.title, Modifier.padding(top = 22.dp).onGloballyPositioned {
                        titleBottom = (it.positionInParent().y + it.size.height).toInt()
                    }, size = 30, bold = true, maxLines = 3, align = TextAlign.Center)
                    if (playlist.subtitle.isNotBlank()) PlaylistText(playlist.subtitle, Modifier.padding(top = 7.dp), color = PlaylistMuted, align = TextAlign.Center)
                    PlaylistText(playlist.creator.ifBlank { "我" }, Modifier.padding(top = 10.dp), color = LibraryAccent, size = 15)
                    val dates = remember(playlist.createdAt, playlist.updatedAt) {
                        val format = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                        "创建 ${format.format(Date(playlist.createdAt))} · 更新 ${format.format(Date(playlist.updatedAt))}"
                    }
                    PlaylistText(dates, Modifier.padding(top = 6.dp), color = PlaylistMuted, size = 12, align = TextAlign.Center)
                    val duration = remember(playlist.entries) { playlist.entries.sumOf { it.track.durationMs.coerceAtLeast(0L) } }
                    PlaylistText("${playlist.entries.size} 首 · ${playlistDuration(duration)}", Modifier.padding(top = 5.dp), color = PlaylistMuted, size = 13)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PlaylistButton(if (playlist.favorite) "取消收藏歌单" else "收藏歌单", onFavorite, enabled = !busy, icon = PlayerIconType.Star, filled = playlist.favorite)
                        PlaylistButton("歌单更多操作", { onMenu(menuAnchor.bounds) }, Modifier.onGloballyPositioned { menuAnchor.bounds = it.boundsInRoot() }, icon = PlayerIconType.More)
                    }
                }
            }
        }
        stickyHeader(key = "playlist:controls") {
            Column(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 20.dp, vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlaylistPill("播放", Modifier.weight(1f), !busy && tracks.isNotEmpty(), PlayerIconType.Play) { onPlay(null, tracks) }
                    PlaylistPill("随机播放", Modifier.weight(1f), !busy && tracks.isNotEmpty(), shuffle = true) { onShuffle(tracks) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (editing) {
                        PlaylistButton(if (filtered.isNotEmpty() && filtered.all { it.id in selection }) "取消全选" else "全选", { onSelectAll(filtered.map { it.id }) }, enabled = !busy)
                        PlaylistText("已选 ${selection.size}", Modifier.weight(1f), size = 13, color = PlaylistMuted)
                        PlaylistButton("批量操作", { onBatch(batchAnchor.bounds) }, Modifier.onGloballyPositioned { batchAnchor.bounds = it.boundsInRoot() }, enabled = selection.isNotEmpty() && !busy, icon = PlayerIconType.More)
                    } else {
                        PlaylistText(if (query.isBlank()) "歌曲" else "${filtered.size} 个结果", Modifier.weight(1f), size = 18, bold = true)
                        PlaylistButton("添加歌曲", onAdd, enabled = !busy, glyph = PlaylistGlyph.Plus)
                        PlaylistButton("排序：${playlistSortName(playlist.sort)}", { onSort(sortAnchor.bounds) }, Modifier.onGloballyPositioned { sortAnchor.bounds = it.boundsInRoot() }, enabled = !busy, glyph = PlaylistGlyph.Sort)
                    }
                }
            }
        }
        if (editing && !dragEnabled && !busy) item("playlist:reorderHint") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                PlaylistText("拖拽需要自定义顺序，并退出歌单搜索。", size = 13, color = PlaylistMuted)
                PlaylistButton("清除搜索并切换自定义", onEnableReorder)
            }
        }
        if (editing && dragEnabled) item("playlist:dragHint") {
            PlaylistText("长按右侧手柄拖动；松手保存。辅助功能可上移／下移。", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), size = 12, color = PlaylistMuted)
        }
        if (filtered.isEmpty()) item("playlist:empty") {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                PlaylistText(if (query.isBlank()) "还没有歌曲" else "没有匹配的歌曲", size = 21, bold = true)
                if (query.isBlank()) {
                    PlaylistButton("从资料库添加", onAdd, enabled = !busy)
                    PlaylistButton("导入设备音频", onImport, enabled = !busy)
                }
            }
        }
        items(filtered, key = { playlistEntryKey(it.id) }, contentType = { "entry" }) { entry ->
            val context = LocalContext.current
            val readable = availability[entry.track.stableKey]
            LaunchedEffect(entry.track.stableKey, availabilityRevision, isActive) {
                if (isActive && availability[entry.track.stableKey] == null) {
                    availability[entry.track.stableKey] = playlistReadable(context, entry.track)
                }
            }
            val dragging = reorder.draggingId == entry.id
            val move: (Int) -> Unit = { delta ->
                val from = reorder.order.indexOf(entry.id)
                val to = from + delta
                if (dragEnabled && from >= 0 && to in reorder.order.indices) {
                    val next = reorder.order.toMutableList().apply { add(to, removeAt(from)) }
                    reorder.sync(next); onReorder(next)
                }
            }
            PlaylistTrackRow(
                entry, editing, entry.id in selection, currentTrack?.stableKey == entry.track.stableKey,
                isPlaying, readable == false, busy, motion,
                Modifier.zIndex(if (dragging) 2f else 0f).graphicsLayer { translationY = reorder.translation(entry.id) }
                    .background(if (dragging) PlaylistSurface else Color.Black),
                onClick = {
                    if (editing) onSelect(entry.id)
                    else visibleIndex[entry.id]?.let { index -> onPlay(tracks[index], tracks) }
                },
                onMenu = { bounds -> onTrackMenu(entry, bounds) },
                handle = if (editing) ({
                    Box(Modifier.size(48.dp).playlistDragHandle(reorder, entry.id, dragEnabled, onReorder)
                        .semantics {
                            contentDescription = if (dragEnabled) "${entry.track.title}，长按拖动排序" else "排序手柄不可用"
                            if (dragEnabled) customActions = buildList {
                                if (reorder.order.firstOrNull() != entry.id) add(CustomAccessibilityAction("上移") { move(-1); true })
                                if (reorder.order.lastOrNull() != entry.id) add(CustomAccessibilityAction("下移") { move(1); true })
                            }
                        }, contentAlignment = Alignment.Center) {
                        PlaylistSymbol(PlaylistGlyph.Handle, color = if (dragEnabled) PlaylistMuted else PlaylistMuted.copy(alpha = .3f))
                    }
                }) else null,
            )
        }
        item("playlist:footer") {
            PlaylistText("缺失文件会保留在歌单中。移出条目不会删除设备上的音频。", Modifier.padding(24.dp), color = PlaylistMuted, size = 12)
        }
    }
}

@Composable
private fun PlaylistPill(
    label: String, modifier: Modifier, enabled: Boolean, icon: PlayerIconType? = null,
    shuffle: Boolean = false, onClick: () -> Unit,
) {
    Row(modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp)).background(PlaylistSurface)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val tint = if (enabled) LibraryAccent else PlaylistMuted
        if (shuffle) PlaylistSymbol(PlaylistGlyph.Shuffle, color = tint)
        else if (icon != null) PlayerIcon(icon, Modifier.size(20.dp), tint = tint, filled = true)
        Spacer(Modifier.width(8.dp)); PlaylistText(label, color = tint, bold = true, maxLines = 1)
    }
}

@Composable
private fun PlaylistSearch(query: String, onQuery: (String) -> Unit, onCancel: () -> Unit, hint: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { PlaylistField(query, onQuery, hint) }
        PlaylistButton("取消", onCancel)
    }
}

@Composable
private fun PlaylistField(value: String, onValue: (String) -> Unit, hint: String, singleLine: Boolean = true) {
    BasicTextField(value, onValue, Modifier.fillMaxWidth().heightIn(min = 50.dp)
        .clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .06f))
        .padding(horizontal = 14.dp, vertical = 14.dp).semantics { contentDescription = hint },
        singleLine = singleLine,
        textStyle = TextStyle(color = Color.White, fontFamily = PlayerTypography.latin, fontSize = 16.sp),
        cursorBrush = SolidColor(LibraryAccent),
        decorationBox = { inner -> Box { if (value.isEmpty()) PlaylistText(hint, color = PlaylistMuted); inner() } },
    )
}

@Composable
private fun PlaylistTrackRow(
    entry: PlaylistEntry, editing: Boolean, selected: Boolean, current: Boolean, playing: Boolean,
    missing: Boolean, busy: Boolean, motion: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit, onMenu: (Rect?) -> Unit, handle: (@Composable () -> Unit)? = null,
) {
    val bounds = remember { PlaylistAnchor() }
    val moreBounds = remember { PlaylistAnchor() }
    val quality = remember(entry.track) { entry.track.audioQuality }
    Row(modifier.fillMaxWidth().onGloballyPositioned { bounds.bounds = it.boundsInRoot() }
        .playlistSwipeActions(!editing && !busy, motion) { onMenu(bounds.bounds) }
        .padding(start = 16.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        // The drag handle is a sibling of the click region, never a child of a long-click row.
        Row(Modifier.weight(1f).heightIn(min = 52.dp)
            .combinedClickable(enabled = !busy, role = if (editing) Role.Checkbox else Role.Button,
                onClick = onClick, onLongClickLabel = "歌曲操作", onLongClick = { onMenu(bounds.bounds) })
            .semantics { if (editing) this.selected = selected }, verticalAlignment = Alignment.CenterVertically) {
            if (editing) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (selected) PlaylistSymbol(PlaylistGlyph.Check, color = LibraryAccent)
                else Canvas(Modifier.size(22.dp)) {
                    drawCircle(PlaylistMuted, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
            }
            Box(Modifier.size(52.dp)) {
                TrackArtwork(entry.track, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), requestSize = 128)
                if (current) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
                    PlayerIcon(if (playing) PlayerIconType.Pause else PlayerIconType.Play, Modifier.size(20.dp), tint = LibraryAccent, filled = true)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
                PlaylistText(entry.track.title, size = 16, color = if (current) LibraryAccent else Color.White, maxLines = 1)
                PlaylistText("${entry.track.artist} · ${entry.track.album}", Modifier.padding(top = 3.dp), color = PlaylistMuted, size = 12, maxLines = 1)
                PlaylistText(if (missing) "文件缺失或权限失效 · 条目已保留" else quality.description,
                    Modifier.padding(top = 2.dp), color = if (missing) LibraryAccent else PlaylistMuted, size = 10, maxLines = 1)
            }
            PlaylistText(if (entry.track.durationMs > 0) formatPlaybackTime(entry.track.durationMs) else "—", size = 11, color = PlaylistMuted)
        }
        if (handle != null) handle()
        else PlaylistButton("${entry.track.title}的更多操作", { onMenu(moreBounds.bounds) },
            Modifier.onGloballyPositioned { moreBounds.bounds = it.boundsInRoot() }, enabled = !busy, icon = PlayerIconType.More)
    }
}

@Composable
private fun PlaylistFeedback(task: PlaylistTask, modifier: Modifier = Modifier) {
    val message = task.message ?: return
    Column(modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth()
        .clip(RoundedCornerShape(16.dp)).background(Color(0xFF30292F))
        .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        PlaylistText(message, size = 13)
        if (!task.busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            task.retry?.let { retry -> PlaylistButton("重试", retry) }
            PlaylistButton("关闭提示", { task.message = null })
        }
    }
}

private fun sortedPlaylistEntries(playlist: MusicPlaylist): List<PlaylistEntry> {
    val comparator = when (playlist.sort) {
        PlaylistSort.Custom -> return playlist.entries
        PlaylistSort.Title -> compareBy<PlaylistEntry> { it.track.title.lowercase(Locale.ROOT) }
        PlaylistSort.Artist -> compareBy { it.track.artist.lowercase(Locale.ROOT) }
        PlaylistSort.Album -> compareBy { it.track.album.lowercase(Locale.ROOT) }
        PlaylistSort.Added -> compareBy { it.addedAt }
    }
    // sortedWith is stable: equal metadata retains occurrence order, even when descending.
    return playlist.entries.sortedWith(if (playlist.ascending) comparator else comparator.reversed())
}

private fun playlistSortName(sort: PlaylistSort) = when (sort) {
    PlaylistSort.Custom -> "自定义"
    PlaylistSort.Title -> "标题"
    PlaylistSort.Artist -> "艺人"
    PlaylistSort.Album -> "专辑"
    PlaylistSort.Added -> "添加时间"
}

private fun playlistDuration(ms: Long): String {
    if (ms <= 0) return "时长未知"
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分钟" else if (minutes > 0) "$minutes 分钟" else "${ms / 1000} 秒"
}

private suspend fun playlistReadable(context: Context, track: Track): Boolean = withContext(Dispatchers.IO) {
    if (track.isOnline) return@withContext true // Source resolution belongs to the real playback pipeline.
    try { context.contentResolver.openAssetFileDescriptor(track.uri, "r")?.use { true } ?: false }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

private suspend fun playlistPlayable(context: Context, tracks: List<Track>, cache: MutableMap<String, Boolean>): List<Track> {
    val result = ArrayList<Track>(tracks.size)
    for (track in tracks) {
        val readable = cache[track.stableKey]?.takeIf { it } ?: playlistReadable(context, track).also { cache[track.stableKey] = it }
        if (readable) result += track
    }
    return result
}

/** Successful files are removed from pending work, so a partial retry cannot add duplicates. */
private class PlaylistImportBatch(uris: List<Uri>) {
    private val remaining = uris.toMutableList()
    private val pending = mutableListOf<Track>()
    private var imported = 0

    suspend fun importInto(context: Context, repository: PlaylistRepository, id: String) {
        repository.requirePlaylist(id)
        val audio = LocalMusicRepository(context.applicationContext)
        val failures = mutableListOf<String>()
        for (uri in remaining.toList()) {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    check(context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true) { "文件不可读" }
                }
                pending += audio.importAudio(uri)
                remaining.remove(uri)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failures += "${uri.lastPathSegment.orEmpty().takeLast(48)}：${failure.message ?: "读取失败"}" }
        }
        if (pending.isNotEmpty()) {
            repository.checked { requirePlaylist(id); addTracks(id, pending.toList()) }
            imported += pending.size
            pending.clear()
        }
        check(remaining.isEmpty()) {
            "已成功导入 $imported 首，${remaining.size} 个文件失败。重试只处理失败文件；不会重复添加成功项。${failures.take(2).joinToString("；")}"
        }
    }
}

private fun sharePlaylistTrack(context: Context, track: Track) {
    val send = Intent(Intent.ACTION_SEND).apply {
        if (track.isOnline) {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${track.title} — ${track.artist}\n${track.uri}")
        } else {
            check(track.uri.scheme == "content") { "此音频无法安全授予分享权限，请通过系统文件应用分享" }
            type = track.mimeType ?: "audio/*"
            putExtra(Intent.EXTRA_STREAM, track.uri)
            putExtra(Intent.EXTRA_TEXT, "${track.title} — ${track.artist}")
            clipData = android.content.ClipData.newUri(context.contentResolver, track.title, track.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(Intent.createChooser(send, "分享歌曲").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun PlaylistPopupContent(
    popup: PlaylistPopup?, visible: Boolean, anchor: Rect?, busy: Boolean,
    onDismiss: () -> Unit, onShowing: (Boolean) -> Unit,
    onEdit: (MusicPlaylist) -> Unit, onAdd: (MusicPlaylist) -> Unit, onCover: (MusicPlaylist) -> Unit,
    onResetCover: (MusicPlaylist) -> Unit, onDelete: (MusicPlaylist) -> Unit, onFavorite: (MusicPlaylist) -> Unit,
    onQueue: (List<Track>, Boolean) -> Unit, onRemove: (String, Set<String>) -> Unit,
    onTransfer: (String, Set<String>) -> Unit, onShare: (Track) -> Unit,
    onSort: (MusicPlaylist, PlaylistSort, Boolean) -> Unit,
) {
    AppContextMenu(visible, anchor, onDismiss, onShowingChanged = onShowing, header = {
        when (val menu = popup) {
            is PlaylistPopup.Info -> PlaylistText(menu.playlist.title, Modifier.padding(16.dp), size = 15, bold = true, maxLines = 2)
            is PlaylistPopup.Song -> Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(menu.entry.track, Modifier.size(40.dp).clip(RoundedCornerShape(7.dp)), requestSize = 64)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    PlaylistText(menu.entry.track.title, size = 14, bold = true, maxLines = 1)
                    PlaylistText(menu.entry.track.artist, size = 12, color = PlaylistMuted, maxLines = 1)
                }
            }
            is PlaylistPopup.Sort -> PlaylistText("排列歌曲", Modifier.padding(16.dp), size = 15, bold = true)
            is PlaylistPopup.Batch -> PlaylistText("已选择 ${menu.ids.size} 个条目", Modifier.padding(16.dp), size = 15, bold = true)
            null -> Unit
        }
        SheetActionDivider()
    }) {
        // The host supplies one glass surface. Do not nest SheetActionGroup cards here.
        when (val menu = popup) {
            is PlaylistPopup.Info -> {
                SheetActionRow("添加 / 导入歌曲", PlayerIconType.Queue) { if (!busy) onAdd(menu.playlist) }
                SheetActionRow("编辑歌单信息", PlayerIconType.Info) { if (!busy) onEdit(menu.playlist) }
                SheetActionRow("选择自定义封面", PlayerIconType.Album) { if (!busy) onCover(menu.playlist) }
                if (menu.playlist.customCoverUri != null) SheetActionRow("恢复自动封面", PlayerIconType.Album) { if (!busy) onResetCover(menu.playlist) }
                SheetActionDivider()
                SheetActionRow(if (menu.playlist.favorite) "取消收藏" else "收藏歌单", PlayerIconType.Star, menu.playlist.favorite) { if (!busy) onFavorite(menu.playlist) }
                SheetActionDivider()
                PlaylistContextRow("删除歌单（不删除音频）", PlaylistGlyph.Trash) { if (!busy) onDelete(menu.playlist) }
            }
            is PlaylistPopup.Song -> {
                SheetActionRow("下一首播放", PlayerIconType.Next) { if (!busy) onQueue(listOf(menu.entry.track), true) }
                SheetActionRow("最后播放", PlayerIconType.Queue) { if (!busy) onQueue(listOf(menu.entry.track), false) }
                SheetActionDivider()
                PlaylistContextRow("转移 / 复制到其他歌单", PlaylistGlyph.Transfer) { if (!busy) onTransfer(menu.playlist.id, setOf(menu.entry.id)) }
                SheetActionRow("分享歌曲", PlayerIconType.Share) { if (!busy) onShare(menu.entry.track) }
                SheetActionDivider()
                PlaylistContextRow("从歌单移出", PlaylistGlyph.Trash) { if (!busy) onRemove(menu.playlist.id, setOf(menu.entry.id)) }
            }
            is PlaylistPopup.Batch -> {
                val selected = sortedPlaylistEntries(menu.playlist).filter { it.id in menu.ids }.map { it.track }
                SheetActionRow("下一首播放", PlayerIconType.Next) { if (!busy) onQueue(selected, true) }
                SheetActionRow("最后播放", PlayerIconType.Queue) { if (!busy) onQueue(selected, false) }
                SheetActionDivider()
                PlaylistContextRow("转移 / 复制到其他歌单", PlaylistGlyph.Transfer) { if (!busy) onTransfer(menu.playlist.id, menu.ids) }
                PlaylistContextRow("移出所选条目（保留音频）", PlaylistGlyph.Trash) { if (!busy) onRemove(menu.playlist.id, menu.ids) }
            }
            is PlaylistPopup.Sort -> {
                PlaylistSort.entries.forEach { sort ->
                    val icon = when (sort) {
                        PlaylistSort.Custom -> PlayerIconType.Queue
                        PlaylistSort.Title, PlaylistSort.Added -> PlayerIconType.Info
                        PlaylistSort.Artist -> PlayerIconType.Artist
                        PlaylistSort.Album -> PlayerIconType.Album
                    }
                    SheetActionRow(playlistSortName(sort), icon,
                        selected = sort == menu.playlist.sort) { if (!busy) onSort(menu.playlist, sort, menu.playlist.ascending) }
                }
                if (menu.playlist.sort != PlaylistSort.Custom) {
                    SheetActionDivider()
                    PlaylistContextRow("升序", PlaylistGlyph.Up, menu.playlist.ascending) { if (!busy) onSort(menu.playlist, menu.playlist.sort, true) }
                    PlaylistContextRow("降序", PlaylistGlyph.Down, !menu.playlist.ascending) { if (!busy) onSort(menu.playlist, menu.playlist.sort, false) }
                }
            }
            null -> Unit
        }
    }
}

/** Extra playlist-only symbols; all other rows use the shared PlayerIcon/SheetActionRow. */
@Composable
private fun PlaylistContextRow(label: String, glyph: PlaylistGlyph, selected: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PlaylistText(label, Modifier.weight(1f), size = 17, color = if (selected) LibraryAccent else Color.White.copy(alpha = .94f), maxLines = 2)
        Spacer(Modifier.width(16.dp))
        PlaylistSymbol(glyph, color = if (selected) LibraryAccent else Color.White.copy(alpha = .72f))
    }
}

@Composable
private fun PlaylistPanelContent(
    panel: PlaylistPanel?, visible: Boolean, repository: PlaylistRepository, playlists: List<MusicPlaylist>,
    library: List<Track>, task: PlaylistTask, onDismiss: () -> Unit, onShowing: (Boolean) -> Unit,
    onCreated: (String) -> Unit, onImport: (String) -> Unit, onDeleted: (String) -> Unit,
) {
    val ready by repository.ready.collectAsState()
    val disabled = task.busy || !ready
    AppSheet(visible, onDismiss, scrollable = panel !is PlaylistPanel.Songs && panel !is PlaylistPanel.Transfer,
        title = when (panel) {
            PlaylistPanel.Create -> "新建歌单"
            is PlaylistPanel.Edit -> "编辑歌单"
            is PlaylistPanel.Songs -> "添加歌曲"
            is PlaylistPanel.Transfer -> "转移歌曲"
            is PlaylistPanel.Remove -> "移出歌曲"
            is PlaylistPanel.Delete -> "删除歌单"
            null -> "歌单"
        }, onShowingChanged = onShowing) {
        key(panel) {
            when (val page = panel) {
                PlaylistPanel.Create -> {
                    // Also retain it if the user edits the form and presses Save after a partial failure.
                    var created by remember { mutableStateOf<String?>(null) }
                    PlaylistInfoEditor(null, disabled) { title, subtitle, creator ->
                        task.run("新建歌单") {
                            if (created == null) repository.checked {
                                created = create(title).takeIf { it.isNotBlank() } ?: error("未能创建歌单")
                            }
                            val id = checkNotNull(created)
                            try { repository.checked { updateInfo(id, title, subtitle, creator) } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                throw IllegalStateException("歌单已创建，但信息未保存；重试会更新同一个歌单。${failure.message}", failure)
                            }
                            onCreated(id)
                        }
                    }
                }
                is PlaylistPanel.Edit -> PlaylistInfoEditor(page.playlist, disabled) { title, subtitle, creator ->
                    task.run("保存歌单信息") {
                        repository.checked { requirePlaylist(page.playlist.id); updateInfo(page.playlist.id, title, subtitle, creator) }
                        onDismiss()
                    }
                }
                is PlaylistPanel.Songs -> PlaylistLibraryPicker(library, disabled, onImport = { onImport(page.id) }) { tracks ->
                    task.run("添加 ${tracks.size} 首歌曲") {
                        repository.checked { requirePlaylist(page.id); addTracks(page.id, tracks) }
                        onDismiss()
                    }
                }
                is PlaylistPanel.Transfer -> {
                    var move by remember { mutableStateOf(true) }
                    PlaylistText("${if (move) "转移" else "复制"} ${page.ids.size} 个条目", size = 22, bold = true)
                    PlaylistText(if (move) "成功后从原歌单移出，不删除音频。" else "保留原歌单中的条目。", Modifier.padding(vertical = 8.dp), size = 13, color = PlaylistMuted)
                    Row {
                        PlaylistButton(if (move) "已选：转移" else "转移", { move = true }, enabled = !disabled)
                        PlaylistButton(if (!move) "已选：复制" else "复制", { move = false }, enabled = !disabled)
                    }
                    PlaylistDestinations(playlists.filterNot { it.id == page.id }, disabled, emptyText = "先创建另一个歌单，再转移或复制。") { target ->
                        val shouldMove = move
                        task.run(if (shouldMove) "转移条目" else "复制条目") {
                            repository.checked {
                                val source = requirePlaylist(page.id)
                                requirePlaylist(target.id)
                                check(source.entries.any { it.id in page.ids }) { "所选条目已不在原歌单中" }
                                transfer(page.id, target.id, page.ids, move = shouldMove)
                            }
                            onDismiss()
                        }
                    }
                }
                is PlaylistPanel.Remove -> {
                    PlaylistText("移出 ${page.ids.size} 个条目？", size = 23, bold = true)
                    PlaylistText("只更改这个歌单；不会删除设备上的音频。", Modifier.padding(vertical = 16.dp), color = PlaylistMuted)
                    PlaylistButton("确认移出", {
                        task.run("移出条目") {
                            repository.checked { requirePlaylist(page.id); removeEntries(page.id, page.ids) }
                            onDismiss()
                        }
                    }, enabled = !disabled)
                    PlaylistButton("取消", onDismiss)
                }
                is PlaylistPanel.Delete -> {
                    PlaylistText("删除「${page.playlist.title}」？", size = 23, bold = true)
                    PlaylistText("将删除歌单信息和条目，不会删除音频文件。此操作无法撤销。", Modifier.padding(vertical = 16.dp), color = PlaylistMuted)
                    PlaylistButton("确认删除歌单", {
                        task.run("删除歌单") {
                            repository.checked { requirePlaylist(page.playlist.id); delete(page.playlist.id) }
                            onDeleted(page.playlist.id)
                        }
                    }, enabled = !disabled)
                    PlaylistButton("取消", onDismiss)
                }
                null -> Unit
            }
        }
        PlaylistFeedback(task)
    }
}

@Composable
private fun PlaylistInfoEditor(initial: MusicPlaylist?, busy: Boolean, onSave: (String, String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var subtitle by rememberSaveable { mutableStateOf(initial?.subtitle.orEmpty()) }
    var creator by rememberSaveable { mutableStateOf(initial?.creator ?: "我") }
    PlaylistText(if (initial == null) "新建歌单" else "编辑歌单", size = 24, bold = true)
    Spacer(Modifier.height(18.dp))
    PlaylistField(title, { title = it }, "歌单标题")
    Spacer(Modifier.height(12.dp))
    PlaylistField(subtitle, { subtitle = it }, "副标题（可选）", singleLine = false)
    Spacer(Modifier.height(12.dp))
    PlaylistField(creator, { creator = it }, "创建者")
    Spacer(Modifier.height(14.dp))
    PlaylistPill(if (busy) "正在保存…" else "保存", Modifier.fillMaxWidth(), !busy && title.isNotBlank()) {
        onSave(title.trim(), subtitle.trim(), creator.trim().ifBlank { "我" })
    }
}

@Composable
private fun ColumnScope.PlaylistLibraryPicker(
    library: List<Track>, busy: Boolean, onImport: () -> Unit, onAdd: (List<Track>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val entries = remember(library) {
        library.mapIndexed { index, track -> PlaylistEntry("library:$index:${track.stableKey}", track, 0) }
    }
    val filtered = remember(entries, query) {
        val term = query.trim()
        if (term.isEmpty()) entries else entries.filter {
            it.track.title.contains(term, true) || it.track.artist.contains(term, true) || it.track.album.contains(term, true)
        }
    }
    val chosen = remember(entries, selected) { entries.filter { it.id in selected }.map { it.track } }
    PlaylistText("添加歌曲", size = 24, bold = true)
    PlaylistText("资料库 ${library.size} 首 · 已选 ${chosen.size}", Modifier.padding(vertical = 8.dp), size = 13, color = PlaylistMuted)
    PlaylistField(query, { query = it }, "搜索资料库")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        PlaylistButton("导入设备音频", onImport, enabled = !busy)
        PlaylistButton(if (filtered.isNotEmpty() && filtered.all { it.id in selected }) "取消全选" else "全选结果", {
            val ids = filtered.map { it.id }.toSet()
            selected = if (ids.all { it in selected }) selected - ids else selected + ids
        }, enabled = !busy && filtered.isNotEmpty())
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 330.dp)) {
        if (filtered.isEmpty()) item("picker:empty") {
            PlaylistText(if (library.isEmpty()) "暂无资料库歌曲，可直接导入音频文件。" else "没有匹配的歌曲", Modifier.padding(vertical = 24.dp), color = PlaylistMuted)
        }
        items(filtered, key = { it.id }, contentType = { "pick-track" }) { entry ->
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .clickable(enabled = !busy, role = Role.Checkbox) {
                    selected = if (entry.id in selected) selected - entry.id else selected + entry.id
                }.semantics { this.selected = entry.id in selected }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(entry.track, Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)), requestSize = 64)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    PlaylistText(entry.track.title, maxLines = 1)
                    PlaylistText("${entry.track.artist} · ${entry.track.album}", size = 12, color = PlaylistMuted, maxLines = 1)
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (entry.id in selected) PlaylistSymbol(PlaylistGlyph.Check, color = LibraryAccent)
                    else PlaylistSymbol(PlaylistGlyph.Plus, color = PlaylistMuted)
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    PlaylistPill("添加 ${chosen.size} 首", Modifier.fillMaxWidth(), !busy && chosen.isNotEmpty()) { onAdd(chosen) }
}

@Composable
private fun ColumnScope.PlaylistDestinations(
    playlists: List<MusicPlaylist>, busy: Boolean, emptyText: String = "还没有歌单，可先新建。",
    onChoose: (MusicPlaylist) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 330.dp)) {
        if (playlists.isEmpty()) item("destination:empty") { PlaylistText(emptyText, Modifier.padding(vertical = 24.dp), color = PlaylistMuted) }
        items(playlists, key = { it.id }, contentType = { "destination" }) { playlist ->
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(enabled = !busy, role = Role.Button) { onChoose(playlist) }
                .padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaylistArtwork(playlist, Modifier.size(50.dp), requestSize = 64)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    PlaylistText(playlist.title, bold = true, maxLines = 1)
                    PlaylistText("${playlist.entries.size} 首 · ${playlist.creator}", size = 12, color = PlaylistMuted, maxLines = 1)
                }
                PlaylistSymbol(PlaylistGlyph.Chevron, color = PlaylistMuted)
            }
        }
    }
}

/** Shared entry point for local/online/player callers. Payload is retained during sheet exit. */
@Composable
fun PlaylistAddSheet(repository: PlaylistRepository, tracks: List<Track>, visible: Boolean, onDismiss: () -> Unit) {
    val playlists by repository.playlists.collectAsState()
    val ready by repository.ready.collectAsState()
    val storeError by repository.error.collectAsState()
    val task = rememberPlaylistTask()
    var retainedTracks by remember { mutableStateOf(tracks.toList()) }
    var creating by remember { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    val payload = if (visible && tracks.isNotEmpty()) tracks else retainedTracks
    SideEffect { if (visible && tracks.isNotEmpty()) retainedTracks = tracks.toList() }
    AppSheet(visible, onDismiss, scrollable = false, title = "加入歌单") {
        PlaylistText("加入歌单", size = 24, bold = true)
        PlaylistText("${payload.size} 首歌曲 · 保留重复歌曲的独立条目", Modifier.padding(vertical = 10.dp), color = PlaylistMuted, size = 13)
        if (storeError != null && task.message == null) PlaylistText(storeError.orEmpty(), color = LibraryAccent, size = 13)
        if (creating) {
            PlaylistField(title, { title = it }, "新歌单标题")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PlaylistButton("取消新建", { creating = false }, enabled = !task.busy)
                PlaylistButton("创建并加入", {
                    val snapshot = payload.toList()
                    val name = title.trim()
                    task.run("创建并加入歌单") {
                        repository.checked { check(create(name, snapshot).isNotBlank()) { "未能创建歌单" } }
                        creating = false; title = ""; onDismiss()
                    }
                }, enabled = ready && !task.busy && title.isNotBlank() && payload.isNotEmpty())
            }
        } else {
            SheetActionGroup {
                SheetActionRow("新建歌单") { if (ready && !task.busy) creating = true }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!ready) PlaylistText(if (storeError == null) "正在读取歌单…" else "存储尚未就绪，已禁止写入以保护数据。", color = PlaylistMuted, size = 13)
        PlaylistDestinations(playlists, !ready || task.busy || payload.isEmpty()) { destination ->
            val snapshot = payload.toList()
            task.run("加入「${destination.title}」") {
                repository.checked { requirePlaylist(destination.id); addTracks(destination.id, snapshot) }
                onDismiss()
            }
        }
        PlaylistFeedback(task)
    }
}
