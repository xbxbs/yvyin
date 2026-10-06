package com.example.xuebimc

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.PI
import kotlin.math.sin

private val LibrarySecondary = Color.White.copy(alpha = .68f)
private val LibraryHairline = Color.White.copy(alpha = .10f)
private val LibraryGround = Color.Black
private val LibraryRaised = Color(0xFF1C1B20)
/** Same single accent as the player and shared action sheets. */
internal val LibraryAccent = Color(0xFFF2506B)

private data class LibrarySearchResult(
    val index: LibraryIndex? = null,
    val route: String = "",
    val query: String = "",
    val matches: List<Track> = emptyList(),
    val groups: List<LibraryBrowseGroup> = emptyList(),
    val durationMs: Long = 0L,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySurface(
    tracks: List<Track>,
    currentTrack: Track?,
    isPlaying: Boolean,
    loading: Boolean,
    hasPermission: Boolean,
    error: String?,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (Track) -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
    onImport: () -> Unit,
    onOnline: () -> Unit = {},
    onPlayList: (List<Track>) -> Unit = { it.firstOrNull()?.let(onSelect) },
    onNext: () -> Unit = {},
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    bottomInset: Dp = 0.dp,
    isActive: Boolean = true,
    onSelectFromList: (Track, List<Track>) -> Unit = { track, _ -> onSelect(track) },
    onOverlayVisibilityChange: (Boolean) -> Unit = {},
    onShuffleList: (List<Track>) -> Unit = { onPlayList(it.shuffled()) },
    showSongsOnly: Boolean = false,
    onSongs: () -> Unit = {},
    onRootBack: () -> Unit = {},
    onPlaylists: () -> Unit = {},
    playlistCount: Int = 0,
    onAddToPlaylist: (List<Track>) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Title) }
    var browseKind by rememberSaveable { mutableStateOf<LibraryBrowseKind?>(null) }
    var groupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetShowing by remember { mutableStateOf(false) }
    // Retain the content during the shared sheet's exit animation.
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var feedback by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var feedbackRevision by remember { mutableStateOf(0L) }
    val searchTerm = query.trim()
    val homeListState = rememberLazyListState()
    val browseListState = rememberLazyListState()
    val collectionListState = rememberLazyListState()
    val listState = when {
        groupKey != null -> collectionListState
        browseKind != null -> browseListState
        else -> homeListState
    }
    val route = "${browseKind?.name.orEmpty()}:${groupKey?.let { "group:$it" } ?: "groups"}"
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val accessibility = LocalAccessibilityManager.current
    val motionAllowed = rememberLibraryMotionAllowed()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val sheetVisible = isActive && menuOpen
    val animatePlayback = isActive && isPlaying && motionAllowed && !sheetShowing &&
        lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val reportSheetVisibility by rememberUpdatedState(onOverlayVisibilityChange)

    fun dismissKeyboard() {
        keyboard?.hide()
        focus.clearFocus()
        searchFocused = false
    }
    fun cancelSearch() {
        query = ""
        dismissKeyboard()
    }
    fun browse(kind: LibraryBrowseKind, key: String? = null) {
        cancelSearch()
        menuOpen = false
        browseKind = kind
        groupKey = key
        scope.launch {
            if (key == null) browseListState.scrollToItem(0) else collectionListState.scrollToItem(0)
        }
    }
    fun goBack() {
        cancelSearch()
        if (groupKey != null) groupKey = null else browseKind = null
    }
    fun openTrackMenu(track: Track) {
        dismissKeyboard()
        menuTrack = track
        menuOpen = true
    }
    fun showFeedback(message: String) { feedback = ++feedbackRevision to message }
    fun selectFromList(track: Track, queue: List<Track>) {
        if (queue.none { it.stableKey == track.stableKey }) return
        dismissKeyboard()
        // Keep every earlier track and its position. Never rotate/slice the queue to fake an index.
        onSelectFromList(track, queue)
    }

    DisposableEffect(Unit) { onDispose { reportSheetVisibility(false) } }
    LaunchedEffect(isActive) {
        if (!isActive) {
            menuOpen = false
            dismissKeyboard()
        }
    }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = 2200L, containsText = true, containsControls = false,
            ) ?: 2200L
            delay(timeout)
            feedback = null
        }
    }
    BackHandler(isActive && !menuOpen && !sheetShowing && (searchFocused || query.isNotEmpty() || browseKind != null)) {
        if (searchFocused || query.isNotEmpty()) cancelSearch() else goBack()
    }

    val index by produceState<LibraryIndex?>(null, tracks, sort) {
        value = withContext(Dispatchers.Default) { buildLibraryIndex(tracks, sort) }
    }
    val readyIndex = index?.takeIf { it.source === tracks && it.sort == sort }
    val group = browseKind?.let { readyIndex?.groups?.get(it) }?.firstOrNull { it.key == groupKey }
    val pageTitle = if (groupKey != null) group?.title ?: "歌曲" else browseKind?.label ?: if (showSongsOnly) "歌曲" else "资料库"
    val browsingGroups = browseKind != null && groupKey == null
    val searchResult by produceState(
        LibrarySearchResult(), readyIndex, route, searchTerm,
    ) {
        if (searchTerm.isNotEmpty()) delay(120)
        value = withContext(Dispatchers.Default) {
            val terms = searchTerm.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val source = if (groupKey != null) group?.tracks.orEmpty() else readyIndex?.ordered.orEmpty()
            val matches = if (terms.isEmpty()) source else source.filter { track ->
                ensureActive()
                libraryMatches(track, terms)
            }
            val groups = browseKind?.let { readyIndex?.groups?.get(it) }.orEmpty().filter { entry ->
                ensureActive()
                terms.isEmpty() || terms.all { entry.title.contains(it, ignoreCase = true) } ||
                    entry.tracks.any { libraryMatches(it, terms) }
            }
            LibrarySearchResult(readyIndex, route, searchTerm, matches, groups,
                matches.sumOf { ensureActive(); it.durationMs.coerceAtLeast(0L) })
        }
    }
    val searching = readyIndex == null || searchResult.index !== readyIndex ||
        searchResult.route != route || searchResult.query != searchTerm
    // Do not let a tap during debounce play yesterday's filter or a removed collection.
    val visibleTracks = if (searching) emptyList() else searchResult.matches
    val visibleGroups = if (searching) emptyList() else searchResult.groups
    val albumColumns = if (LocalConfiguration.current.screenWidthDp >= 600) 3 else 2
    val currentKey = currentTrack?.stableKey
    val density = LocalDensity.current
    var topBarHeight by remember { mutableStateOf(56.dp) }
    var searchHeight by remember { mutableStateOf(60.dp) }
    val headerFeatherHeight = 24.dp
    val headerBackdrop = remember { HazeState() }
    val searchTopPx by remember(listState, density) {
        derivedStateOf {
            val info = listState.layoutInfo
            val search = info.visibleItemsInfo.firstOrNull { it.key == "library:search" }
            val heading = info.visibleItemsInfo.firstOrNull { it.key == "library:heading" }
            val top = with(density) { topBarHeight.roundToPx() }
            val searchOffset = search?.offset ?: heading?.let { it.offset + it.size }
            (searchOffset?.plus(info.beforeContentPadding) ?: top).coerceAtLeast(top)
        }
    }
    val collapsed by remember(listState, density) {
        derivedStateOf {
            val info = listState.layoutInfo
            val search = info.visibleItemsInfo.firstOrNull { it.key == "library:search" }
            // Include the feather inset when deciding whether search has reached the toolbar.
            search?.let {
                it.offset + info.beforeContentPadding <= with(density) { topBarHeight.roundToPx() }
            } ?: (listState.firstVisibleItemIndex > 0)
        }
    }
    val barAlpha by animateFloatAsState(
        if (collapsed) 1f else 0f, tween(if (motionAllowed) 160 else 0), label = "library-bar",
    )
    // A covered library draws normally without capturing another viewport for the player.
    val sampleBackdrop = isActive && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    Box(Modifier.fillMaxSize().background(LibraryGround)) {
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding().then(if (sheetShowing) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().semantics { paneTitle = pageTitle }
                    // Foreground navigation and search never feed back into the source.
                    .then(if (sampleBackdrop) Modifier.hazeSource(headerBackdrop) else Modifier),
                // The parent already includes its floating bar + system navigation in bottomInset.
                // Keep the initial heading below the feather; padding scrolls away, not the viewport.
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp,
                    top = topBarHeight + headerFeatherHeight, bottom = bottomInset + 24.dp),
            ) {
                item(key = "library:heading", contentType = "heading") {
                    LibraryText(pageTitle, Modifier.padding(top = 2.dp, bottom = 12.dp), size = 34.sp, bold = true, maxLines = 2)
                }
                item(key = "library:search", contentType = "search-space") {
                    // The clear search field lives outside the Haze source, at this item's position.
                    Spacer(Modifier.fillMaxWidth().height(searchHeight))
                }
                if (!showSongsOnly && browseKind == null && searchTerm.isEmpty()) {
                    item(key = "library:browse", contentType = "browse") {
                        Column {
                            LibraryDestinationRow("播放列表", playlistCount, PlayerIconType.Queue, onPlaylists)
                            LibraryBrowseKind.entries.forEach { kind ->
                                if (kind == LibraryBrowseKind.Folders) LibraryDestinationRow("歌曲", tracks.size, PlayerIconType.Play, onSongs)
                                LibraryBrowseRow(kind, readyIndex?.groups?.get(kind)?.size, onClick = { browse(kind) })
                            }
                        }
                    }
                }
                val recent = readyIndex?.recent.orEmpty()
                if (!showSongsOnly && browseKind == null && searchTerm.isEmpty() && recent.isNotEmpty()) {
                    item(key = "library:recent", contentType = "recent") {
                        val visible by remember(listState) {
                            derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "library:recent" } }
                        }
                        LibraryRecentShelf(recent, currentKey, isPlaying, animatePlayback && visible,
                            onSelect = { selectFromList(it, recent) }, onMore = ::openTrackMenu)
                    }
                }
                if (!hasPermission && browseKind == null) {
                    item(key = "library:permission", contentType = "notice") {
                        LibraryPermissionPrompt(onRequestPermission)
                    }
                }
                if (!error.isNullOrBlank()) {
                    item(key = "library:error", contentType = "notice") {
                        LibraryNotice("读取音乐遇到问题", error, "重试",
                            if (hasPermission) onRefresh else onRequestPermission, enabled = !loading)
                    }
                }
                if (showSongsOnly || browseKind != null || searchTerm.isNotEmpty() || tracks.isEmpty()) {
                item(key = "library:section", contentType = "section") {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp)) {
                        LibraryText(
                            if (searchTerm.isNotEmpty()) "搜索结果" else if (browsingGroups) browseKind!!.label else "歌曲",
                            size = 20.sp, semibold = true,
                        )
                        LibraryText(
                            when {
                                searching -> if (searchTerm.isNotEmpty()) "正在搜索…" else "正在整理…"
                                loading -> "正在扫描…"
                                browsingGroups -> "${visibleGroups.size} 个${browseKind!!.label}"
                                else -> "${visibleTracks.size} 首 · ${libraryTotalDuration(searchResult.durationMs)}"
                            },
                            modifier = Modifier.padding(top = 4.dp).semantics {
                                liveRegion = LiveRegionMode.Polite
                                if (loading || searching) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                            },
                            size = 13.sp, color = LibrarySecondary,
                        )
                        if (sort == LibrarySort.Recent && !browsingGroups) {
                            LibraryText("按媒体库编号估算；导入文件保留原序", Modifier.padding(top = 4.dp), size = 12.sp,
                                color = LibrarySecondary, maxLines = 2)
                        }
                        if (!browsingGroups && visibleTracks.isNotEmpty() && !searching) {
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LibraryAction("播放", { dismissKeyboard(); onPlayList(visibleTracks) }, Modifier.weight(1f), LibrarySymbol.Play, emphasized = true)
                                LibraryAction("随机播放", { dismissKeyboard(); onShuffleList(visibleTracks) }, Modifier.weight(1f), LibrarySymbol.Shuffle, emphasized = true)
                            }
                        }
                    }
                }
                when {
                    tracks.isEmpty() && loading -> item(key = "library:scanning", contentType = "empty") {
                        LibraryEmptyState("正在寻找你的音乐", "正在扫描本地音频，歌曲会显示在这里。")
                    }
                    searching -> item(key = "library:searching", contentType = "empty") {
                        LibraryEmptyState(if (searchTerm.isEmpty()) "正在整理资料库" else "正在搜索", "正在匹配歌曲、艺人和专辑…")
                    }
                    tracks.isEmpty() -> item(key = "library:empty", contentType = "empty") {
                        LibraryEmptyState(
                            "还没有本地音乐",
                            if (hasPermission) "扫描设备中的音乐，或从系统文件选择器导入喜欢的歌曲。"
                            else "你可以先导入一首歌曲；文件导入不需要授予整个曲库的访问权限。",
                            "从文件导入", onImport,
                        )
                    }
                    browsingGroups && visibleGroups.isNotEmpty() -> {
                        if (browseKind == LibraryBrowseKind.Albums) {
                            items(visibleGroups.chunked(albumColumns), key = { "album-row:${it.first().key}" }, contentType = { "albums" }) { row ->
                                Row(Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    row.forEach { entry ->
                                        LibraryAlbumTile(entry, Modifier.weight(1f)) { browse(LibraryBrowseKind.Albums, entry.key) }
                                    }
                                    repeat(albumColumns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        } else {
                            items(visibleGroups, key = { "group:${it.key}" }, contentType = { "group" }) { entry ->
                                LibraryGroupRow(entry, browseKind!!) { browse(browseKind!!, entry.key) }
                            }
                        }
                    }
                    (browsingGroups && visibleGroups.isEmpty()) || visibleTracks.isEmpty() -> item(key = "library:no-matches", contentType = "empty") {
                        LibraryEmptyState(
                            if (searchTerm.isEmpty()) "这里还没有歌曲" else "没有找到相关音乐",
                            if (searchTerm.isEmpty()) "歌曲可能已移出资料库，可返回浏览或重新扫描。" else "试试其他歌名、艺人或专辑，也可以搜索文件名。",
                            if (query.isNotEmpty()) "清除搜索" else "重新扫描",
                            if (query.isNotEmpty()) ::cancelSearch else if (hasPermission) onRefresh else onRequestPermission,
                        )
                    }
                    else -> items(visibleTracks, key = { it.stableKey }, contentType = { "track" }) { track ->
                        val visible by remember(listState, track.stableKey) {
                            derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == track.stableKey } }
                        }
                        LibraryTrackRow(
                            track, isCurrent = track.stableKey == currentKey,
                            isPlaying = isPlaying && track.stableKey == currentKey,
                            animateBars = animatePlayback && visible,
                            onSelect = { selectFromList(it, visibleTracks) }, onMore = { openTrackMenu(track) },
                        )
                    }
                }
                }
            }
            MusicHeaderGlass(
                backdrop = headerBackdrop,
                headerHeight = topBarHeight,
                featherHeight = headerFeatherHeight,
                enabled = sampleBackdrop,
            )
            MusicHeaderGlass(
                backdrop = headerBackdrop,
                headerHeight = searchHeight,
                featherHeight = headerFeatherHeight,
                modifier = Modifier.offset { IntOffset(0, searchTopPx) }
                    .graphicsLayer {
                        // Fade in only as search approaches its pinned position, without recomposition.
                        alpha = ((topBarHeight.toPx() + headerFeatherHeight.toPx() - searchTopPx) /
                            headerFeatherHeight.toPx()).coerceIn(0f, 1f)
                    },
                enabled = sampleBackdrop,
            )
            Box(
                Modifier.fillMaxWidth()
                    // Placement and drawing, not composition, observe the per-frame search position.
                    .offset { IntOffset(0, searchTopPx) }
                    .onSizeChanged { searchHeight = with(density) { it.height.toDp() } }
                    // Preserve dragging the old sticky search area to scroll the list.
                    .scrollable(listState, Orientation.Vertical, enabled = isActive, reverseDirection = true)
                    .padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
            ) {
                LibrarySearchField(query, searchFocused,
                    onQueryChange = { query = it }, onFocusChange = { searchFocused = it }, onCancel = ::cancelSearch)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .onSizeChanged { topBarHeight = with(density) { it.height.toDp() } }
                    .pointerInput(Unit) { detectTapGestures {} }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (browseKind != null || showSongsOnly) {
                    LibraryIconButton(LibrarySymbol.Back, "返回${if (groupKey != null) browseKind?.label else "资料库"}", {
                        if (browseKind != null) goBack() else onRootBack()
                    })
                } else Spacer(Modifier.width(48.dp))
                LibraryText(
                    pageTitle, Modifier.weight(1f).graphicsLayer { alpha = barAlpha }
                        .then(if (!collapsed) Modifier.clearAndSetSemantics {} else Modifier),
                    size = 17.sp, semibold = true, align = TextAlign.Center,
                )
                LibraryIconButton(LibrarySymbol.More, "资料库选项", {
                    dismissKeyboard()
                    menuTrack = null
                    menuOpen = true
                }, color = LibraryAccent)
            }
        }
        if (feedback != null && isActive && !sheetShowing) {
            Box(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = bottomInset + 12.dp)
                .clip(RoundedCornerShape(24.dp)).background(LibraryRaised).padding(horizontal = 18.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }) {
                LibraryText(feedback!!.second, size = 14.sp, medium = true, maxLines = 2, align = TextAlign.Center)
            }
        }
        AppContextMenu(
            visible = sheetVisible, anchor = null, onDismiss = { menuOpen = false },
            onShowingChanged = { showing -> sheetShowing = showing; reportSheetVisibility(showing) },
        ) {
            val selectedTrack = menuTrack?.let { target -> tracks.firstOrNull { it.stableKey == target.stableKey } }
            if (menuTrack == null) {
                LibraryText("资料库", Modifier.padding(16.dp), size = 17.sp, semibold = true)
                SheetActionDivider()
                SheetActionGroup {
                    SheetActionRow("设置", PlayerIconType.Settings) { menuOpen = false; onOpenSettings() }
                    SheetActionDivider()
                    SheetActionRow("导入音乐") { menuOpen = false; onImport() }
                    SheetActionDivider()
                    if (loading) {
                        LibraryText("正在扫描…", Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(16.dp)
                            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }, color = LibrarySecondary)
                    } else SheetActionRow("重新扫描") {
                        menuOpen = false
                        if (hasPermission) onRefresh() else onRequestPermission()
                    }
                }
                LibraryText("歌曲排序", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), size = 13.sp, color = LibrarySecondary, medium = true)
                SheetActionGroup {
                    LibrarySort.entries.forEachIndexed { i, option ->
                        if (i > 0) SheetActionDivider()
                        Box(Modifier.semantics { selected = sort == option }) {
                            SheetActionRow(option.label, selected = sort == option) {
                                sort = option
                                menuOpen = false
                                scope.launch { listState.scrollToItem(0) }
                            }
                        }
                    }
                }
                LibraryText("最近添加按媒体库编号估算；导入文件暂无添加时间。", Modifier.padding(16.dp),
                    size = 12.sp, color = LibrarySecondary, maxLines = 3)
            } else if (selectedTrack != null) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    TrackArtwork(selectedTrack, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).clearAndSetSemantics {}, requestSize = 160)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        LibraryText(libraryTitle(selectedTrack), size = 17.sp, semibold = true, maxLines = 2)
                        LibraryText(librarySubtitle(selectedTrack), Modifier.padding(top = 2.dp), size = 13.sp, color = LibrarySecondary, maxLines = 2)
                    }
                }
                SheetActionDivider()
                SheetActionGroup {
                    SheetActionRow("下一首播放", PlayerIconType.Next) {
                        onPlayNext(selectedTrack)
                        menuOpen = false
                        showFeedback(if (currentTrack == null) "已开始播放" else "已设为下一首")
                    }
                    SheetActionDivider()
                    SheetActionRow("加入队列", PlayerIconType.Queue) {
                        onAddToQueue(selectedTrack)
                        menuOpen = false
                        showFeedback(if (currentTrack == null) "已开始播放" else "歌曲已在待播队列中")
                    }
                    SheetActionDivider()
                    SheetActionRow("加入播放列表", PlayerIconType.Queue) {
                        menuOpen = false
                        onAddToPlaylist(listOf(selectedTrack))
                    }
                }
                Spacer(Modifier.height(8.dp))
                SheetActionGroup {
                    SheetActionRow("前往专辑", PlayerIconType.Album) { browse(LibraryBrowseKind.Albums, libraryAlbumKey(selectedTrack)) }
                    SheetActionDivider()
                    SheetActionRow("前往艺人", PlayerIconType.Artist) { browse(LibraryBrowseKind.Artists, libraryArtistKey(selectedTrack)) }
                }
            } else {
                LibraryText("这首歌曲已移出资料库", maxLines = 2)
                Spacer(Modifier.height(12.dp))
                SheetActionGroup { SheetActionRow("关闭") { menuOpen = false } }
            }
        }
    }
}

@Composable
private fun LibraryDestinationRow(label: String, count: Int, icon: PlayerIconType, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerIcon(icon, Modifier.size(24.dp), LibraryAccent)
            Spacer(Modifier.width(16.dp))
            LibraryText(label, Modifier.weight(1f), size = 20.sp, semibold = true)
            LibraryText(count.toString(), size = 14.sp, color = LibrarySecondary)
            Spacer(Modifier.width(12.dp))
            LibraryGlyph(LibrarySymbol.Chevron, Modifier.size(16.dp), LibrarySecondary)
        }
        Box(Modifier.padding(start = 40.dp).fillMaxWidth().height(.5.dp).background(LibraryHairline))
    }
}

@Composable
private fun LibraryBrowseRow(kind: LibraryBrowseKind, count: Int?, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryGlyph(when (kind) {
                LibraryBrowseKind.Artists -> LibrarySymbol.Artist
                LibraryBrowseKind.Albums -> LibrarySymbol.Album
                LibraryBrowseKind.Folders -> LibrarySymbol.Folder
            }, Modifier.size(24.dp), LibraryAccent)
            Spacer(Modifier.width(16.dp))
            LibraryText(kind.label, Modifier.weight(1f), size = 20.sp, semibold = true)
            if (count != null) LibraryText(count.toString(), size = 14.sp, color = LibrarySecondary)
            Spacer(Modifier.width(12.dp))
            LibraryGlyph(LibrarySymbol.Chevron, Modifier.size(16.dp), LibrarySecondary)
        }
        Box(Modifier.padding(start = 40.dp).fillMaxWidth().height(.5.dp).background(LibraryHairline))
    }
}

@Composable
private fun LibraryGroupRow(group: LibraryBrowseGroup, kind: LibraryBrowseKind, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (kind == LibraryBrowseKind.Folders) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    LibraryGlyph(LibrarySymbol.Folder, Modifier.size(26.dp), LibraryAccent)
                }
            } else TrackArtwork(group.tracks.firstOrNull(), Modifier.size(48.dp).clip(CircleShape).clearAndSetSemantics {}, requestSize = 160)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                LibraryText(group.title, size = 17.sp, medium = true, maxLines = 2)
                LibraryText(group.subtitle, Modifier.padding(top = 4.dp), size = 13.sp, color = LibrarySecondary, maxLines = 2)
            }
            Spacer(Modifier.width(12.dp))
            LibraryGlyph(LibrarySymbol.Chevron, Modifier.size(16.dp), LibrarySecondary)
        }
        Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(.5.dp).background(LibraryHairline))
    }
}

@Composable
private fun LibraryAlbumTile(group: LibraryBrowseGroup, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)) {
        TrackArtwork(group.tracks.firstOrNull(), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
            .clearAndSetSemantics {}, requestSize = 400)
        LibraryText(group.title, Modifier.padding(top = 8.dp), size = 16.sp, medium = true, maxLines = 2)
        LibraryText(group.subtitle, Modifier.padding(top = 4.dp), size = 13.sp, color = LibrarySecondary, maxLines = 2)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRecentShelf(
    tracks: List<Track>, currentKey: String?, isPlaying: Boolean, animateBars: Boolean,
    onSelect: (Track) -> Unit, onMore: (Track) -> Unit,
) {
    val shelfState = rememberLazyListState()
    Column(Modifier.padding(top = 26.dp)) {
        LibraryText("最近添加", size = 20.sp, semibold = true)
        LibraryText("按媒体库编号估算", Modifier.padding(top = 4.dp), size = 12.sp, color = LibrarySecondary)
        Spacer(Modifier.height(12.dp))
        LazyRow(
            state = shelfState,
            // Bleed to the screen edge while the first cover stays on the content margin.
            modifier = Modifier.layout { measurable, constraints ->
                val bleed = 18.dp.roundToPx()
                val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            },
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(tracks, key = { it.stableKey }) { track ->
                val visible by remember(shelfState, track.stableKey) {
                    derivedStateOf { shelfState.layoutInfo.visibleItemsInfo.any { it.key == track.stableKey } }
                }
                Column(
                    Modifier.width(148.dp).clip(RoundedCornerShape(10.dp))
                        .combinedClickable(role = Role.Button, onClickLabel = "播放歌曲", onLongClickLabel = "歌曲操作",
                            onLongClick = { onMore(track) }, onClick = { onSelect(track) })
                        .semantics {
                            selected = track.stableKey == currentKey
                            if (track.stableKey == currentKey) stateDescription = if (isPlaying) "正在播放" else "已暂停"
                        },
                ) {
                    Box {
                        TrackArtwork(
                            track, Modifier.size(148.dp).clip(RoundedCornerShape(10.dp)).clearAndSetSemantics {},
                            requestSize = 360,
                        )
                        if (track.stableKey == currentKey) {
                            Box(
                                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp).clip(CircleShape)
                                    .background(Color(0xFF332C39).copy(alpha = .9f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isPlaying) LibraryEqualizer(animateBars && visible, Modifier.size(14.dp))
                                else LibraryGlyph(LibrarySymbol.Pause, Modifier.size(14.dp), LibraryAccent)
                            }
                        }
                    }
                    LibraryText(libraryTitle(track), Modifier.padding(top = 8.dp), size = 14.sp, medium = true,
                        color = if (track.stableKey == currentKey) LibraryAccent else Color.White)
                    LibraryText(librarySubtitle(track), Modifier.padding(top = 3.dp), size = 13.sp, color = LibrarySecondary)
                }
            }
        }
    }
}

@Composable
private fun LibrarySearchField(
    query: String, focused: Boolean, onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit, onCancel: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Row(
        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(LibraryRaised.copy(alpha = .58f)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically,
      ) {
        LibraryGlyph(LibrarySymbol.Search, Modifier.size(20.dp), LibrarySecondary)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).onFocusChanged { onFocusChange(it.isFocused) }
                .semantics { contentDescription = "搜索本地音乐" },
            textStyle = TextStyle(
                color = Color.White, fontSize = 16.sp, fontFamily = PlayerTypography.familyFor(query),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            singleLine = true,
            cursorBrush = SolidColor(LibraryAccent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focus.clearFocus()
            }),
            decorationBox = { field ->
                Box(Modifier.heightIn(min = 48.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        LibraryText("搜索歌曲、艺人或专辑", size = 15.sp, color = LibrarySecondary)
                    }
                    field()
                }
            },
        )
        if (query.isNotEmpty()) {
            LibraryIconButton(LibrarySymbol.Clear, "清除搜索", { onQueryChange("") })
        } else {
            Spacer(Modifier.width(12.dp))
        }
      }
      if (focused || query.isNotEmpty()) {
          Spacer(Modifier.width(8.dp))
          Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onCancel)
              .padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
              LibraryText("取消", size = 16.sp, color = LibraryAccent, medium = true)
          }
      }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTrackRow(
    track: Track, isCurrent: Boolean, isPlaying: Boolean, animateBars: Boolean,
    onSelect: (Track) -> Unit, onMore: () -> Unit,
) {
    val longPressAnchor = rememberMenuAnchor()
    Column {
        Row(
            Modifier.fillMaxWidth().then(longPressAnchor.first).clip(RoundedCornerShape(10.dp))
                .combinedClickable(role = Role.Button, onClickLabel = "播放歌曲", onLongClickLabel = "歌曲操作",
                    onLongClick = { longPressAnchor.second(); onMore() }, onClick = { onSelect(track) })
                .semantics {
                    selected = isCurrent
                    if (isCurrent) stateDescription = if (isPlaying) "正在播放" else "已暂停"
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TrackArtwork(
                    track, Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)).clearAndSetSemantics {},
                    requestSize = 160,
                )
                if (isCurrent) {
                    Box(
                        Modifier.matchParentSize().clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = .45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isPlaying) LibraryEqualizer(animateBars, Modifier.size(18.dp))
                        else LibraryGlyph(LibrarySymbol.Pause, Modifier.size(18.dp), LibraryAccent)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                LibraryText(libraryTitle(track), size = 16.sp, medium = true, color = if (isCurrent) LibraryAccent else Color.White)
                LibraryText(
                    librarySubtitle(track), size = 13.sp, color = LibrarySecondary,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            LibraryIconButton(LibrarySymbol.More, "${libraryTitle(track)}的更多操作", onMore, color = LibrarySecondary)
        }
        Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(.5.dp).background(LibraryHairline))
    }
}

@Composable
private fun LibraryEqualizer(animate: Boolean, modifier: Modifier) {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        phase.snapTo(0f)
        if (animate) phase.animateTo(1f, infiniteRepeatable(tween(1100, easing = LinearEasing)))
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        // Read only in draw, not composition. No loop is left running in a paused/hidden row.
        val time = phase.value * (2f * PI.toFloat())
        repeat(3) { bar ->
            val wave = if (animate) (sin(time + bar * 2.1f) + 1f) * .5f else when (bar) { 0 -> .35f; 1 -> .9f; else -> .6f }
            val x = size.width * (.2f + bar * .3f)
            drawLine(LibraryAccent, Offset(x, size.height * .88f),
                Offset(x, size.height * (.66f - .52f * wave)), size.width * .14f, StrokeCap.Round)
        }
    }
}

@Composable
private fun LibraryPermissionPrompt(onRequestPermission: () -> Unit) {
    Row(
        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = .07f))
            .clickable(role = Role.Button, onClickLabel = "授予音乐扫描权限", onClick = onRequestPermission)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LibraryGlyph(LibrarySymbol.Refresh, Modifier.size(20.dp), LibrarySecondary)
        Column(Modifier.weight(1f)) {
            LibraryText("扫描更多本地音乐", size = 14.sp, medium = true)
            LibraryText(
                "已导入歌曲可直接播放，点此允许扫描设备。",
                Modifier.padding(top = 4.dp), size = 12.sp, color = LibrarySecondary, maxLines = 2,
            )
        }
        LibraryGlyph(LibrarySymbol.Chevron, Modifier.size(16.dp), LibrarySecondary)
    }
}

@Composable
private fun LibraryNotice(
    title: String, message: String, action: String, onAction: () -> Unit, enabled: Boolean = true,
) {
    Column(
        Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = .07f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LibraryText(title, size = 16.sp, medium = true, maxLines = 2)
        LibraryText(message, size = 13.sp, color = LibrarySecondary, maxLines = 4)
        LibraryAction(action, onAction, Modifier.fillMaxWidth(), enabled = enabled)
    }
}

@Composable
private fun LibraryEmptyState(
    title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TrackArtwork(null, Modifier.size(72.dp).clip(RoundedCornerShape(17.dp)).clearAndSetSemantics {})
        LibraryText(title, size = 20.sp, medium = true, maxLines = 2, align = TextAlign.Center)
        LibraryText(message, size = 14.sp, color = LibrarySecondary, maxLines = 4, align = TextAlign.Center)
        if (action != null && onAction != null) {
            LibraryAction(action, onAction, emphasized = true)
        }
    }
}

@Composable
private fun LibraryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: LibrarySymbol? = null,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    val foreground = (if (emphasized) LibraryAccent else Color.White).copy(alpha = if (enabled) 1f else .42f)
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (emphasized) .09f else .06f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (symbol != null) {
            LibraryGlyph(symbol, Modifier.size(18.dp), foreground)
            Spacer(Modifier.width(8.dp))
        }
        LibraryText(label, Modifier.weight(1f, fill = false), size = 14.sp, color = foreground, medium = true)
    }
}

@Composable
private fun LibraryIconButton(symbol: LibrarySymbol, label: String, onClick: () -> Unit, color: Color = Color.White) {
    val anchor = rememberMenuAnchor()
    Box(
        Modifier.size(48.dp).then(anchor.first).clip(CircleShape).clickable(role = Role.Button, onClick = {
            if (symbol == LibrarySymbol.More) anchor.second()
            onClick()
        })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        LibraryGlyph(symbol, Modifier.size(24.dp), color)
    }
}

@Composable
private fun LibraryText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    color: Color = Color.White,
    medium: Boolean = false,
    bold: Boolean = false,
    semibold: Boolean = false,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color, fontSize = size, lineHeight = size * 1.35f,
            fontFamily = FontFamily.SansSerif,
            fontWeight = when { bold -> FontWeight.Bold; semibold -> FontWeight.SemiBold; medium -> FontWeight.Medium; else -> FontWeight.Normal },
            fontSynthesis = FontSynthesis.None,
            fontFeatureSettings = "tnum", textAlign = align,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

private enum class LibrarySymbol { Play, Pause, Search, Clear, Refresh, Chevron, Shuffle, More, Back, Artist, Album, Folder }

@Composable
private fun LibraryGlyph(symbol: LibrarySymbol, modifier: Modifier, color: Color = Color.White) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val unit = size.minDimension / 24f
        withTransform({
            translate((size.width - unit * 24f) / 2f, (size.height - unit * 24f) / 2f)
            scale(unit, unit, pivot = Offset.Zero)
        }) {
            when (symbol) {
                LibrarySymbol.More -> {
                    listOf(5f, 12f, 19f).forEach { x -> drawCircle(color, 1.7f, Offset(x, 12f)) }
                }
                LibrarySymbol.Back -> drawPath(
                    Path().apply { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) },
                    color, style = Stroke(1.8f, cap = StrokeCap.Round),
                )
                LibrarySymbol.Artist -> {
                    drawCircle(color, 4f, Offset(12f, 7f), style = Stroke(1.8f))
                    drawPath(Path().apply { moveTo(4f, 21f); cubicTo(4f, 11f, 20f, 11f, 20f, 21f) },
                        color, style = Stroke(1.8f, cap = StrokeCap.Round))
                }
                LibrarySymbol.Album -> {
                    drawRoundRect(color, Offset(3f, 3f), Size(18f, 18f), CornerRadius(2f), style = Stroke(1.8f))
                    drawCircle(color, 5f, Offset(12f, 12f), style = Stroke(1.8f))
                    drawCircle(color, 1.2f, Offset(12f, 12f))
                }
                LibrarySymbol.Folder -> drawPath(
                    Path().apply { moveTo(3f, 6f); lineTo(10f, 6f); lineTo(12f, 9f); lineTo(21f, 9f)
                        lineTo(21f, 20f); lineTo(3f, 20f); close() }, color, style = Stroke(1.8f),
                )
                LibrarySymbol.Play -> drawPath(
                    Path().apply { moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close() }, color,
                )
                LibrarySymbol.Pause -> {
                    drawRoundRect(color, Offset(6f, 4f), Size(4f, 16f), CornerRadius(1f))
                    drawRoundRect(color, Offset(14f, 4f), Size(4f, 16f), CornerRadius(1f))
                }
                LibrarySymbol.Search -> {
                    drawCircle(color, 6.5f, Offset(10f, 10f), style = Stroke(1.8f))
                    drawLine(color, Offset(15f, 15f), Offset(21f, 21f), 1.8f, StrokeCap.Round)
                }
                LibrarySymbol.Clear -> {
                    drawLine(color, Offset(7f, 7f), Offset(17f, 17f), 1.8f, StrokeCap.Round)
                    drawLine(color, Offset(17f, 7f), Offset(7f, 17f), 1.8f, StrokeCap.Round)
                }
                LibrarySymbol.Chevron -> drawPath(
                    Path().apply { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) },
                    color, style = Stroke(1.8f, cap = StrokeCap.Round),
                )
                LibrarySymbol.Refresh -> {
                    drawArc(color, -65f, 295f, false, Offset(4f, 4f), Size(16f, 16f), style = Stroke(1.8f, cap = StrokeCap.Round))
                    drawPath(
                        Path().apply { moveTo(12f, 2f); lineTo(17f, 4.5f); lineTo(13f, 8f) },
                        color, style = Stroke(1.8f, cap = StrokeCap.Round),
                    )
                }
                LibrarySymbol.Shuffle -> {
                    val stroke = Stroke(1.8f, cap = StrokeCap.Round)
                    drawPath(Path().apply { moveTo(3f, 7f); lineTo(7f, 7f); cubicTo(12f, 7f, 12f, 17f, 17f, 17f); lineTo(20f, 17f) }, color, style = stroke)
                    drawPath(Path().apply { moveTo(3f, 17f); lineTo(7f, 17f); cubicTo(12f, 17f, 12f, 7f, 17f, 7f); lineTo(20f, 7f) }, color, style = stroke)
                    drawPath(Path().apply { moveTo(17.5f, 4.5f); lineTo(20f, 7f); lineTo(17.5f, 9.5f) }, color, style = stroke)
                    drawPath(Path().apply { moveTo(17.5f, 14.5f); lineTo(20f, 17f); lineTo(17.5f, 19.5f) }, color, style = stroke)
                }
            }
        }
    }
}

private fun libraryTotalDuration(durationMs: Long): String {
    val minutes = durationMs.coerceAtLeast(0L) / 60_000
    return when {
        durationMs <= 0L -> "时长未知"
        minutes == 0L -> "不足 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0L -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}
