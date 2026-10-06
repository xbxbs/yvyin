package com.example.xuebimc

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun OnlineSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
    sourceName: String,
    sourceModifier: Modifier,
    onSourceClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    BackHandler(enabled = focused, onBack = onCancel)
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
            // The visible field is 36dp; controls retain a separate 48dp touch height.
            Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(OnlineControlSurface))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxSize().onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = "搜索在线歌曲、歌手或专辑" },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White, fontSize = 17.sp,
                    fontFamily = PlayerTypography.familyFor(query), fontSynthesis = FontSynthesis.None,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                cursorBrush = SolidColor(OnlineAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { field ->
                    Row(Modifier.fillMaxSize().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OnlineGlyph(OnlineSymbol.Search, Modifier.size(18.dp), OnlineSecondary)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) OnlineText("歌名、歌手或专辑", size = 15.sp, color = OnlineSecondary)
                            field()
                        }
                        if (query.isNotEmpty()) {
                            Box(
                                Modifier.width(40.dp).height(48.dp).clip(CircleShape)
                                    .clickable(role = Role.Button) { onQueryChange("") }
                                    .semantics { contentDescription = "清除搜索" },
                                contentAlignment = Alignment.Center,
                            ) { OnlineGlyph(OnlineSymbol.Clear, Modifier.size(20.dp), OnlineSecondary) }
                        } else Spacer(Modifier.width(8.dp))
                        Box(Modifier.width(.5.dp).height(16.dp).background(OnlineTertiary))
                        Row(
                            sourceModifier.height(48.dp).widthIn(min = 48.dp, max = 108.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button, onClickLabel = "选择搜索音源", onClick = onSourceClick)
                                .semantics { contentDescription = "搜索音源：$sourceName" }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OnlineText(sourceName, Modifier.weight(1f, fill = false), size = 13.sp)
                            Spacer(Modifier.width(5.dp))
                            OnlineGlyph(OnlineSymbol.Chevron, Modifier.size(10.dp), OnlineSecondary)
                        }
                    }
                },
            )
        }
        AnimatedVisibility(
            visible = focused,
            enter = expandHorizontally(spring(dampingRatio = .84f, stiffness = 460f), expandFrom = Alignment.End) +
                fadeIn(spring(stiffness = 460f)),
            exit = shrinkHorizontally(spring(dampingRatio = .84f, stiffness = 460f), shrinkTowards = Alignment.End) +
                fadeOut(spring(stiffness = 460f)),
        ) {
            Box(
                Modifier.padding(start = 8.dp).height(48.dp).widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { OnlineText("取消", size = 15.sp, color = OnlineAccent) }
        }
    }
}

@Composable
internal fun OnlineSegmentedControl(showSaved: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val slide = animateFloatAsState(
        targetValue = if (showSaved) 1f else 0f,
        animationSpec = spring(dampingRatio = .84f, stiffness = 460f), label = "online-section",
    )
    BoxWithConstraints(modifier.fillMaxWidth().height(48.dp)) {
        val segmentWidth = (maxWidth - 4.dp) / 2
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(32.dp)
            .clip(RoundedCornerShape(16.dp)).background(OnlineControlSurface).padding(2.dp)) {
            Box(Modifier.offset { IntOffset((segmentWidth.toPx() * slide.value).roundToInt(), 0) }
                .width(segmentWidth).height(28.dp).clip(RoundedCornerShape(14.dp))
                // A light gray slider retains white-label contrast; no shadow or red underline.
                .background(Color(0xFF636366)))
        }
        Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            listOf("搜索结果", "在线歌单").forEachIndexed { index, label ->
                val active = showSaved == (index == 1)
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp))
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index == 1) }),
                    contentAlignment = Alignment.Center,
                ) { OnlineText(label, size = 13.sp, medium = active) }
            }
        }
    }
}

@Composable
internal fun OnlineSourceOption(source: OnlineSource, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = source.enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = if (!source.enabled) "未启用" else if (selected) "当前搜索音源" else "未选择" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            OnlineText(source.name + when {
                !source.enabled -> " · 未启用"
                !source.supportsPlayback -> " · 仅搜索"
                else -> ""
            }, size = 15.sp, color = if (source.enabled) Color.White else OnlineSecondary)
            if (!source.enabled) OnlineText(source.description.ifBlank { "配置未启用" },
                size = 11.sp, color = OnlineSecondary, maxLines = 2)
        }
        Spacer(Modifier.width(12.dp))
        if (selected) OnlineGlyph(OnlineSymbol.Check, Modifier.size(20.dp), OnlineSecondary)
        else Spacer(Modifier.size(20.dp))
    }
}

@Composable
internal fun OnlineSearchSkeleton() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics {
        contentDescription = "正在搜索"
        liveRegion = LiveRegionMode.Polite
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }) {
        repeat(6) { index ->
            Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(OnlineControlSurface))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) .62f else .46f).height(14.dp)
                        .clip(RoundedCornerShape(4.dp)).background(OnlineControlSurface))
                    Box(Modifier.fillMaxWidth(.72f).height(10.dp)
                        .clip(RoundedCornerShape(3.dp)).background(OnlineControlSurface))
                }
                Spacer(Modifier.width(16.dp))
                Box(Modifier.width(26.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(OnlineControlSurface))
            }
        }
    }
}

@Composable
internal fun OnlineRecentSearches(
    queries: List<String>,
    canSearch: Boolean,
    onClear: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            OnlineText("最近搜索", Modifier.weight(1f), size = 17.sp, medium = true)
            if (queries.isNotEmpty()) Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onClear).semantics { contentDescription = "清除最近搜索" },
                contentAlignment = Alignment.Center,
            ) { OnlineText("清除", size = 13.sp, color = OnlineSecondary) }
        }
        if (queries.isEmpty()) OnlineText("暂无最近搜索", Modifier.padding(vertical = 12.dp), size = 15.sp, color = OnlineSecondary)
        queries.forEach { query ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = canSearch, role = Role.Button, onClickLabel = "搜索$query") { onSelect(query) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OnlineGlyph(OnlineSymbol.Search, Modifier.size(18.dp), OnlineSecondary)
                Spacer(Modifier.width(12.dp))
                OnlineText(query, Modifier.weight(1f), size = 17.sp, color = if (canSearch) Color.White else OnlineSecondary)
            }
        }
    }
}

/** True = toward later rows / collapse; false = toward earlier rows / expand. */
@Composable
internal fun OnlineScrollDirectionEffect(state: LazyListState, enabled: Boolean, onDirection: (Boolean) -> Unit) {
    val currentCallback by rememberUpdatedState(onDirection)
    val threshold = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(state, enabled, threshold) {
        if (!enabled) return@LaunchedEffect
        var userScroll = false
        var previousIndex = state.firstVisibleItemIndex
        var previousOffset = state.firstVisibleItemScrollOffset
        var distance = 0f
        var reportedDirection: Boolean? = null
        launch {
            state.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    userScroll = true
                    distance = 0f
                    reportedDirection = null
                    previousIndex = state.firstVisibleItemIndex
                    previousOffset = state.firstVisibleItemScrollOffset
                }
            }
        }
        snapshotFlow {
            Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.isScrollInProgress)
        }.collect { (index, offset, scrolling) ->
            if (!scrolling) {
                userScroll = false
                distance = 0f
            } else if (userScroll) {
                val delta = when {
                    index > previousIndex -> threshold
                    index < previousIndex -> -threshold
                    else -> (offset - previousOffset).toFloat()
                }
                if (delta != 0f) {
                    distance = if (distance * delta < 0f) delta else distance + delta
                    if (abs(distance) >= threshold) {
                        val towardLaterRows = distance > 0f
                        if (reportedDirection != towardLaterRows) {
                            currentCallback(towardLaterRows)
                            reportedDirection = towardLaterRows
                        }
                        distance = 0f
                    }
                }
            }
            if (index == 0 && offset == 0 && reportedDirection != false) {
                currentCallback(false)
                reportedDirection = false
            }
            previousIndex = index
            previousOffset = offset
        }
    }
}

internal class OnlineRecentSearchStore(context: Context) {
    private val preferences = context.getSharedPreferences("online_recent_searches", Context.MODE_PRIVATE)

    fun load(): List<String> = runCatching {
        val array = JSONArray(preferences.getString("queries", "[]") ?: "[]")
        (0 until array.length()).asSequence().map { array.optString(it, "").trim().take(160) }
            .filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }.take(10).toList()
    }.getOrDefault(emptyList())

    fun record(query: String, previous: List<String>): List<String> {
        val normalized = query.trim().take(160)
        if (normalized.isEmpty()) return previous
        val updated = (listOf(normalized) + previous).distinctBy { it.lowercase(Locale.ROOT) }.take(10)
        preferences.edit().putString("queries", JSONArray(updated).toString()).apply()
        return updated
    }

    fun clear() {
        preferences.edit().remove("queries").apply()
    }
}
