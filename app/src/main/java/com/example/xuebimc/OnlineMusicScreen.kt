package com.example.xuebimc

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OnlineSecondary = Color.White.copy(alpha = .58f)
private val OnlineAccent = Color(0xFFFA586A)

@Composable
fun OnlineMusicScreen(
    currentTrack: Track?,
    sources: List<OnlineSource>,
    selectedSourceId: String,
    query: String,
    results: List<Track>,
    saved: List<Track>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSourceChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPlay: (Track) -> Unit,
    onSave: (Track) -> Unit,
    onRemove: (Track) -> Unit,
    onDownload: (Track) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    onAddToPlaylist: (List<Track>) -> Unit = {},
    onOverlayVisibilityChange: (Boolean) -> Unit = {},
    onPlayFromList: (Track, List<Track>, Boolean) -> Unit = { track, _, _ -> onPlay(track) },
    onOpenSettings: () -> Unit = {},
    isActive: Boolean = true,
) {
    var showSaved by rememberSaveable { mutableStateOf(false) }
    val resultsState = rememberLazyListState()
    val savedState = rememberLazyListState()
    val enabledSources = remember(sources) { sources.filter { it.enabled } }
    val sourceById = remember(sources) { sources.associateBy { it.id } }
    val savedKeys = remember(saved) { saved.mapTo(HashSet()) { it.stableKey } }
    val selectedSource = sourceById[selectedSourceId]?.takeIf { it.enabled }
    val currentKey = currentTrack?.stableKey
    var submittedQuery by rememberSaveable { mutableStateOf(query.trim()) }
    var submittedSource by rememberSaveable { mutableStateOf(selectedSourceId) }
    var searchPending by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    val resultMatchesInput = submittedQuery == query.trim() && submittedSource == selectedSourceId
    val receipt by OnlineMusicRepository.searchReceipt.collectAsState()
    val sourceResults = remember(results, selectedSourceId) { results.filter { it.isOnline && it.sourceId == selectedSourceId } }
    val hasCurrentResults = receipt?.let {
        it.sourceId == submittedSource && it.query == submittedQuery.take(160) && it.tracks == sourceResults
    } == true
    val visibleTracks = if (showSaved) saved
        else if (resultMatchesInput && hasCurrentResults && !loading && !searchPending && !searchFailed) sourceResults else emptyList()
    val context = LocalContext.current
    val artworkRepository = remember(context.applicationContext) { OnlineMusicRepository(context.applicationContext) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var sourceMenu by remember { mutableStateOf(false) }
    var menuVisible by remember { mutableStateOf(false) }
    var showSourceDetails by remember { mutableStateOf(false) }
    var afterDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }
    var retryAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(isActive) {
        if (!isActive) {
            menuVisible = false
            afterDismiss = null
            focus.clearFocus()
        }
    }
    val sourceAnchor = rememberMenuAnchor()
    val sourceInfoAnchor = rememberMenuAnchor()
    val canSearch = selectedSource != null && query.isNotBlank() && (!loading || !resultMatchesInput)
    val hideKeyboard: () -> Unit = { keyboard?.hide(); focus.clearFocus() }
    val menuAction: (() -> Unit) -> Unit = { action -> afterDismiss = action; menuVisible = false }
    val submitSearch: () -> Unit = {
        if (canSearch) {
            hideKeyboard()
            submittedQuery = query.trim()
            submittedSource = selectedSourceId
            showSaved = false
            searchPending = true
            searchFailed = false
            // Search retries must capture the current query/source and refresh the submission state.
            retryAction = null
            onSearch()
        }
    }
    LaunchedEffect(loading, searchPending, error) {
        if (searchPending && !loading) {
            // The host leaves the old list in place on failure. Never relabel it as the new query's results.
            searchFailed = !error.isNullOrBlank()
            searchPending = false
        }
    }
    val playTrack: (Track) -> Unit = { track ->
        hideKeyboard()
        // Collection membership is not navigation context: a saved song can also be a search hit.
        val queue = visibleTracks.ifEmpty { listOf(track) }
        val fromSaved = showSaved
        val play = { onPlayFromList(track, queue, fromSaved) }
        retryAction = play
        play()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Never observe scroll offsets in the screen; LazyColumn owns scrolling.
        LazyColumn(
            state = if (showSaved) savedState else resultsState,
            modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = bottomInset + 24.dp),
        ) {
            item(key = "online:heading", contentType = "heading") {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OnlineText("在线", Modifier.weight(1f), size = 34.sp, bold = true)
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) {
                            hideKeyboard()
                            onOpenSettings()
                        }.semantics { contentDescription = "打开设置" },
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayerIcon(PlayerIconType.Settings, Modifier.size(23.dp), tint = OnlineAccent)
                    }
                }
            }
            item(key = "online:search", contentType = "search") {
                OnlineSearchField(query, { value ->
                    if (value != query) retryAction = null
                    onQueryChange(value)
                }, canSearch, loading, submitSearch)
            }
            item(key = "online:source", contentType = "source") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).then(sourceAnchor.first).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = "选择搜索音源") {
                                hideKeyboard(); sourceAnchor.second(); sourceMenu = true
                                showSourceDetails = false; menuTrack = null; menuVisible = true
                            }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OnlineText("搜索音源", size = 12.sp, color = OnlineSecondary)
                        Spacer(Modifier.width(8.dp))
                        OnlineText(selectedSource?.name ?: "不可用", Modifier.weight(1f, fill = false),
                            size = 13.sp, color = OnlineAccent, medium = true)
                        Spacer(Modifier.width(6.dp))
                        OnlineGlyph(OnlineSymbol.Chevron, Modifier.size(12.dp), OnlineSecondary)
                    }
                    Box(
                        Modifier.then(sourceInfoAnchor.first).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                            hideKeyboard(); sourceInfoAnchor.second(); sourceMenu = true
                            showSourceDetails = true; menuTrack = null; menuVisible = true
                        }.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { OnlineText("音源信息", size = 12.sp, color = OnlineSecondary) }
                }
            }
            item(key = "online:tabs", contentType = "tabs") {
                Row(
                    Modifier.padding(top = 4.dp, bottom = 8.dp).fillMaxWidth().selectableGroup()
                        .clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .055f)),
                ) {
                    OnlineTab("搜索结果", !showSaved, { hideKeyboard(); showSaved = false }, Modifier.weight(1f))
                    OnlineTab("在线歌单", showSaved, { hideKeyboard(); showSaved = true }, Modifier.weight(1f))
                }
            }
            if (!error.isNullOrBlank()) {
                item(key = "online:error", contentType = "notice") {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OnlineText(error, Modifier.weight(1f), size = 13.sp, color = OnlineSecondary, maxLines = 4)
                        if (!loading && !error.startsWith("已提交") && (retryAction != null || canSearch)) {
                            Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) {
                                if (!resultMatchesInput || retryAction == null) submitSearch() else { hideKeyboard(); retryAction?.invoke() }
                            }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                OnlineText("重试", size = 14.sp, color = OnlineAccent)
                            }
                        }
                    }
                }
            }
            if (showSaved || loading || searchPending || (resultMatchesInput && hasCurrentResults && !searchFailed)) {
              item(key = "online:count", contentType = "section") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OnlineText(
                        if (showSaved) "已保存 ${saved.size} 首"
                        else if (resultMatchesInput && hasCurrentResults && !loading && !searchPending && !searchFailed)
                            "${selectedSource?.name.orEmpty()} · ${sourceResults.size} 首"
                        else "",
                        Modifier.weight(1f), size = 13.sp, color = OnlineSecondary,
                    )
                    if (loading || searchPending) {
                        OnlineText(
                            "正在加载…", size = 12.sp, color = OnlineSecondary,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                            },
                        )
                    }
                }
              }
            }
            if (visibleTracks.isEmpty()) {
                item(key = "online:empty", contentType = "empty") {
                    val title = when {
                        showSaved -> "在线歌单还是空的"
                        loading || searchPending -> "正在加载"
                        enabledSources.isEmpty() -> "暂无可用音源"
                        selectedSource == null -> "当前音源不可用"
                        query.isBlank() -> "搜索喜欢的音乐"
                        !resultMatchesInput -> "搜索喜欢的音乐"
                        searchFailed -> "暂未取得结果"
                        !error.isNullOrBlank() -> "暂未取得结果"
                        !hasCurrentResults -> "搜索喜欢的音乐"
                        else -> "暂无搜索结果"
                    }
                    val message = when {
                        showSaved -> "在歌曲的 … 菜单中保存；不会自动下载。"
                        loading || searchPending -> "正在请求音源，请稍候。"
                        enabledSources.isEmpty() -> "已存在线歌单仍可浏览。"
                        selectedSource == null -> "在搜索框下方选择已启用的音源。"
                        query.isBlank() || !resultMatchesInput -> "输入歌名、歌手或专辑。"
                        !error.isNullOrBlank() -> "可重试，或在搜索框下方切换音源。"
                        !hasCurrentResults -> "关键词或来源已更改，请重新搜索。"
                        else -> "试试其他关键词或音源。"
                    }
                    OnlineEmptyState(title, message)
                }
            } else {
                items(visibleTracks, key = { it.stableKey }, contentType = { "online-track" }) { track ->
                    OnlineTrackRow(
                        track = track,
                        // A saved song can belong to a different source from the search chips.
                        source = track.sourceId?.let { sourceById[it] },
                        isCurrent = track.stableKey == currentKey,
                        artworkRepository = artworkRepository,
                        onPlay = playTrack,
                        onMenu = { hideKeyboard(); sourceMenu = false; menuTrack = it; menuVisible = true },
                        showSource = showSaved,
                    )
                }
            }
        }
        AppContextMenu(
            visible = menuVisible && isActive,
            anchor = null,
            onDismiss = { menuVisible = false },
            onShowingChanged = { showing ->
                // The host reports false only after its exit animation has finished.
                onOverlayVisibilityChange(showing)
                if (!showing) {
                    val action = afterDismiss
                    afterDismiss = null
                    menuTrack = null
                    showSourceDetails = false
                    action?.invoke()
                }
            },
            header = {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    OnlineText(if (sourceMenu) { if (showSourceDetails) "音源信息" else "搜索音源" }
                        else menuTrack?.title.orEmpty(), size = 15.sp, medium = true)
                    if (!sourceMenu) OnlineText(menuTrack?.artist.orEmpty(), size = 12.sp, color = OnlineSecondary)
                }
            },
        ) {
            if (sourceMenu && showSourceDetails) {
                OnlineText(onlineCatalogSourceDetails(sourceById[selectedSourceId]),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    size = 13.sp, color = OnlineSecondary, maxLines = Int.MAX_VALUE)
                if (showSaved) OnlineText("在线歌单可包含多个音源；每首歌的菜单中可查看对应来源与解析信息。",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    size = 12.sp, color = OnlineSecondary, maxLines = 4)
                SheetActionDivider()
                SheetActionRow("选择搜索音源", PlayerIconType.Info) { showSourceDetails = false }
            } else if (sourceMenu) {
                if (sources.isEmpty()) OnlineText("暂无可用音源", Modifier.padding(16.dp), color = OnlineSecondary)
                sources.forEachIndexed { index, source ->
                    if (index > 0) SheetActionDivider()
                    if (source.enabled) {
                        SheetActionRow(source.name + (if (source.supportsPlayback) "" else " · 仅搜索") +
                            (if (source.id == selectedSourceId) " · 当前" else ""), PlayerIconType.Info, onClick = {
                                menuAction {
                                    showSaved = false
                                    if (source.id != selectedSourceId) {
                                        submittedSource = ""
                                        searchPending = false
                                        searchFailed = false
                                        retryAction = null
                                        onSourceChange(source.id)
                                    }
                                }
                            })
                    } else {
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            OnlineText("${source.name} · 未启用", size = 15.sp, color = OnlineSecondary)
                            OnlineText(source.description.ifBlank { "配置未启用" }, size = 11.sp, color = OnlineSecondary, maxLines = 2)
                        }
                    }
                }
            } else menuTrack?.let { track ->
                val source = sourceById[track.sourceId]
                if (source?.enabled == true && source.supportsPlayback) {
                    SheetActionRow("播放", PlayerIconType.Play) { menuAction { playTrack(track) } }
                    SheetActionDivider()
                }
                SheetActionRow("加入歌单…", PlayerIconType.AddToPlaylist) { menuAction { onAddToPlaylist(listOf(track)) } }
                SheetActionDivider()
                val isSaved = track.stableKey in savedKeys
                SheetActionRow(if (isSaved) "从在线歌单移除" else "保存到在线歌单",
                    if (isSaved) PlayerIconType.Queue else PlayerIconType.AddToPlaylist) {
                    menuAction { if (isSaved) onRemove(track) else onSave(track) }
                }
                if (source?.enabled == true && source.supportsDownload) {
                    SheetActionDivider()
                    SheetActionRow("下载到设备", PlayerIconType.Download) {
                        menuAction { retryAction = { onDownload(track) }; onDownload(track) }
                    }
                }
                SheetActionDivider()
                SheetActionRow("分享歌曲", PlayerIconType.Share) {
                    menuAction {
                        // Share the public song page / metadata, never an expiring signed audio URL.
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, onlineShareText(track))
                        runCatching { context.startActivity(Intent.createChooser(send, "分享歌曲")) }
                            .onFailure { Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show() }
                    }
                }
                SheetActionDivider()
                SheetActionRow(if (showSourceDetails) "收起音源信息" else "音源信息", PlayerIconType.Info) {
                    showSourceDetails = !showSourceDetails
                }
                if (showSourceDetails) {
                    OnlineText(onlineSourceDetails(track, source, artworkRepository.playbackEvidence(track)),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        size = 12.sp, color = OnlineSecondary, maxLines = Int.MAX_VALUE)
                }
            }
        }
    }
}

@Composable
private fun OnlineSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    canSearch: Boolean,
    loading: Boolean,
    onSearch: () -> Unit,
) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = .085f)).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                    .semantics { contentDescription = "搜索在线歌曲、歌手或专辑" },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White, fontSize = 16.sp, fontFamily = PlayerTypography.familyFor(query),
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                cursorBrush = SolidColor(OnlineAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) OnlineText("歌名、歌手或专辑", size = 15.sp, color = OnlineSecondary)
                        field()
                    }
                },
            )
            if (query.isNotEmpty()) {
                OnlineIconButton(OnlineSymbol.Clear, "清除搜索", { onQueryChange("") })
            }
            OnlineIconButton(OnlineSymbol.Search, if (loading && !canSearch) "搜索中" else "搜索", onSearch,
                enabled = canSearch, color = OnlineAccent)
        }
}

@Composable
private fun OnlineTab(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.heightIn(min = 48.dp)
            .selectable(selected = active, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        OnlineText(label, size = 15.sp, color = if (active) OnlineAccent else OnlineSecondary, medium = active)
        if (active) Box(Modifier.align(Alignment.BottomCenter).width(24.dp).height(2.dp)
            .clip(RoundedCornerShape(1.dp)).background(OnlineAccent))
    }
}

@Composable
private fun OnlineEmptyState(title: String, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OnlineGlyph(OnlineSymbol.Search, Modifier.size(32.dp), OnlineSecondary)
        OnlineText(title, size = 20.sp, medium = true, maxLines = 2, align = TextAlign.Center)
        OnlineText(message, size = 14.sp, color = OnlineSecondary, maxLines = 4, align = TextAlign.Center)
    }
}

@Composable
private fun OnlineIconButton(symbol: OnlineSymbol, label: String, onClick: () -> Unit,
                             modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = OnlineSecondary) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        OnlineGlyph(symbol, Modifier.size(21.dp), if (enabled) color else OnlineSecondary.copy(alpha = .3f))
    }
}

@Composable
private fun OnlineText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    color: Color = Color.White,
    medium: Boolean = false,
    bold: Boolean = false,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color, fontSize = size, lineHeight = size * 1.35f,
            fontFamily = if (bold) PlayerTypography.bold else PlayerTypography.familyFor(text, medium), fontSynthesis = FontSynthesis.None,
            fontFeatureSettings = "tnum", textAlign = align,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

private enum class OnlineSymbol { Search, Clear, More, Chevron }

@Composable
private fun OnlineGlyph(symbol: OnlineSymbol, modifier: Modifier, color: Color = Color.White) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val unit = size.minDimension / 24f
        withTransform({
            translate((size.width - unit * 24f) / 2f, (size.height - unit * 24f) / 2f)
            scale(unit, unit, pivot = Offset.Zero)
        }) {
            when (symbol) {
                OnlineSymbol.Chevron -> drawPath(
                    Path().apply { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) },
                    color, style = Stroke(1.8f, cap = StrokeCap.Round),
                )
                OnlineSymbol.Search -> {
                    drawCircle(color, 6.5f, Offset(10f, 10f), style = Stroke(1.8f))
                    drawLine(color, Offset(15f, 15f), Offset(21f, 21f), 1.8f, StrokeCap.Round)
                }
                OnlineSymbol.Clear -> {
                    drawLine(color, Offset(7f, 7f), Offset(17f, 17f), 1.8f, StrokeCap.Round)
                    drawLine(color, Offset(17f, 7f), Offset(7f, 17f), 1.8f, StrokeCap.Round)
                }
                OnlineSymbol.More -> listOf(5f, 12f, 19f).forEach { x -> drawCircle(color, 1.7f, Offset(x, 12f)) }
            }
        }
    }
}

private fun onlineCatalogSourceDetails(source: OnlineSource?): String = buildString {
    if (source == null) {
        append("当前音源不在可用配置中，请选择已启用的音源。")
        return@buildString
    }
    append("搜索目录：${source.name}\n")
    if (!source.enabled) {
        append("当前未启用。\n${source.description.ifBlank { "配置未启用" }}")
        return@buildString
    }
    if (source.description.isNotBlank()) append("${source.description}\n")
    if (source.supportsPlayback) {
        append("播放地址由第三方解析提供，并非平台直连。\n")
        append("搜索结果与解析返回的身份字段不能证明实际音频一致；曲目、原唱和版本尚未经独立音频核验。\n")
        append(if (source.supportsDownload) "可请求下载，实际可用性受来源权限和解析结果限制。" else "当前音源不支持下载。")
    } else {
        append("仅搜索；未验证播放，不提供播放或下载承诺。")
    }
}

private fun onlineSourceDetails(track: Track, source: OnlineSource?, evidence: OnlinePlaybackEvidence?): String = buildString {
    append("搜索目录：${source?.name ?: track.sourceId ?: "未标注"}\n")
    append("目录曲目 ID：${track.sourceTrackId.orEmpty()}\n")
    if (source?.enabled != true) {
        append("当前音源未启用。\n${source?.description?.ifBlank { "配置未启用" } ?: "音源不在当前配置中"}")
        return@buildString
    }
    if (source?.supportsPlayback != true) {
        append("此来源未验证播放；搜索结果不代表可播放权限。")
        return@buildString
    }
    append("播放地址：第三方标准音质解析\n")
    if (evidence == null) {
        append("尚无本次运行的解析身份；无法确认实际音频一致。")
    } else {
        append("解析返回来源：${evidence.reportedSource ?: "未返回"}\n")
        append("解析返回曲目 ID：${evidence.reportedId ?: "未返回"}\n")
        evidence.title?.let { append("解析标题：$it\n") }
        evidence.artist?.let { append("解析艺人：$it\n") }
        if (evidence.reportedSource == null || evidence.reportedId == null) {
            append("身份字段缺失，无法确认实际音频一致。\n")
        } else {
            append("仅返回身份与请求匹配；未独立核验音频内容。\n")
        }
        append("地址主机：${evidence.audioHost}\nCDN 域名或标题不能单独证明来源或原唱。")
    }
}

private fun onlineShareText(track: Track): String {
    val id = track.sourceTrackId.orEmpty()
    val page = when (track.sourceId) {
        "kw" -> "https://www.kuwo.cn/play_detail/$id"
        "wy" -> "https://music.163.com/#/song?id=$id"
        "tx" -> "https://y.qq.com/n/ryqq/songDetail/$id"
        "bili" -> "https://www.bilibili.com/video/$id"
        else -> ""
    }
    return listOf("${track.title} — ${track.artist}", track.album, page).filter { it.isNotBlank() }.joinToString("\n")
}

private fun onlineTrackDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1000
    val minutes = seconds / 60
    val tail = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$tail" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$tail"
}
