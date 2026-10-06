package com.example.xuebimc

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

private val QueueSecondary = Color.White.copy(alpha = .54f)

private enum class QueueSection { Manual, Context, Autoplay }

private sealed interface QueueRow {
    val key: String

    data class Heading(override val key: String, val title: String, val detail: String = "") : QueueRow
    data class Song(
        val entry: QueueEntry,
        val current: Boolean = false,
        val upcoming: Boolean = false,
        val sourceLabel: String = "",
    ) : QueueRow {
        override val key: String = queueEntryKey(entry.id)
    }
}

private class QueueSnapshot(
    history: List<QueueEntry>,
    val current: QueueEntry?,
    manual: List<QueueEntry>,
    context: List<QueueEntry>,
    val sourceName: String,
) {
    // Occurrence IDs, never track IDs: two copies of the same song remain two independent rows.
    private val seen = mutableSetOf<String>().apply { current?.let { add(it.id) } }
    val manual = manual.filter { seen.add(it.id) }
    val context = context.filter { seen.add(it.id) }
    val history = history.filter { seen.add(it.id) }
    // Never sort the projection: mixed context/autoplay runs must retain actual playback order.
    val upcoming = this.manual + this.context
    val ids = upcoming.map { it.id }
    val positions = ids.withIndex().associate { it.value to it.index }
    val byId = upcoming.associateBy { it.id }
    private val manualIds = this.manual.mapTo(mutableSetOf()) { it.id }

    fun section(id: String): QueueSection = when {
        id in manualIds -> QueueSection.Manual
        byId[id]?.origin == QueueOrigin.Autoplay -> QueueSection.Autoplay
        else -> QueueSection.Context
    }

    fun canMoveDown(id: String): Boolean {
        val index = positions[id] ?: return false
        if (index >= ids.lastIndex) return false
        // The engine keeps manual entries ahead of context: do not offer a no-op "down" action.
        if (id in manualIds) return ids[index + 1] in manualIds
        val entry = byId[id] ?: return false
        val before = ids.getOrNull(index + 2)?.let(byId::get) ?: return true
        // A cross-source target promotes instead of moving down; the drag UI explains that rule.
        return before.id !in manualIds && before.origin == entry.origin && before.sourceName == entry.sourceName
    }

    fun rows(preview: List<String>?, draggingId: String?): List<QueueRow> = buildList {
        if (history.isNotEmpty()) {
            add(QueueRow.Heading("section:history", "播放历史", "向上滚动回顾 · ${history.size} 首"))
            history.forEach { add(QueueRow.Song(it)) }
        }
        current?.let { add(QueueRow.Song(it, current = true)) }
        val order = preview?.takeIf { it.size == ids.size && it.all(byId::containsKey) } ?: ids
        // The moving row adopts its preview destination section; no engine/playlist data is edited.
        val movingIndex = if (draggingId == null) -1 else order.indexOf(draggingId)
        val destination = if (preview != null && order != ids && movingIndex >= 0) {
            (order.getOrNull(movingIndex + 1) ?: order.getOrNull(movingIndex - 1))?.let(::section)
        } else null
        order.forEach { id ->
            val group = if (id == draggingId && destination != null) destination else section(id)
            val source = when (group) {
                QueueSection.Manual -> "手动优先"
                QueueSection.Context -> ""
                QueueSection.Autoplay -> "自动续播"
            }
            byId[id]?.let { add(QueueRow.Song(it, upcoming = true, sourceLabel = source)) }
        }
    }
}

/** Transparent, bounded player content; the host supplies the stage and MusicBackdrop. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun QueueSheetContent(playback: PlaybackController, onDismiss: () -> Unit) {
    val snapshot = remember(
        playback.queueHistory, playback.currentQueueEntry, playback.manualQueue,
        playback.contextQueue, playback.contextSourceName,
    ) {
        QueueSnapshot(
            playback.queueHistory, playback.currentQueueEntry, playback.manualQueue,
            playback.contextQueue, playback.contextSourceName,
        )
    }
    val scope = rememberCoroutineScope()
    val reorder = remember(scope) { QueueReorderState(scope) }
    val rows = remember(snapshot, reorder.previewIds, reorder.draggingId) {
        snapshot.rows(reorder.previewIds, reorder.draggingId)
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = rows.indexOfFirst {
            it is QueueRow.Song && (it.current || it.upcoming)
        }.coerceAtLeast(0),
    )
    val haptic = LocalHapticFeedback.current
    val motionEnabled = rememberQueueMotionEnabled()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val dragging = reorder.draggingId != null
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentPlayback by rememberUpdatedState(playback)

    // Playback advancement / queue replacement invalidates a drag instead of moving a stale ID.
    LaunchedEffect(snapshot.ids, snapshot.current?.id) {
        if (reorder.draggingId != null) reorder.cancel()
    }
    LaunchedEffect(snapshot.current?.id) {
        val currentIndex = rows.indexOfFirst { it is QueueRow.Song && it.current }
        if (currentIndex >= 0) listState.scrollToItem(currentIndex)
    }
    LaunchedEffect(motionEnabled) { if (!motionEnabled) reorder.stopSettling() }
    DisposableEffect(reorder) { onDispose { reorder.cancel() } }
    QueueEdgeScroll(reorder, listState)

    fun remove(id: String): Boolean {
        val controller = currentPlayback
        if (reorder.draggingId != null || controller.currentQueueEntry?.id == id) return false
        val exists = controller.queueHistory.any { it.id == id } ||
            controller.manualQueue.any { it.id == id } || controller.contextQueue.any { it.id == id }
        if (!exists) return false
        controller.removeQueueEntry(id)
        return true
    }

    fun move(id: String, beforeId: String?): Boolean {
        val controller = currentPlayback
        val ids = (controller.manualQueue + controller.contextQueue).map { it.id }
        if (controller.currentQueueEntry?.id == id || id !in ids) return false
        if (beforeId != null && (beforeId == id || beforeId !in ids)) return false
        controller.moveQueueEntry(id, beforeId)
        return true
    }

    BackHandler(enabled = confirmClear || dragging) {
        if (dragging) reorder.cancel(motionEnabled) else confirmClear = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QueueText(
                "待播清单", Modifier.weight(1f).semantics {
                    customActions = listOf(CustomAccessibilityAction("返回播放器") {
                        reorder.cancel()
                        onDismiss()
                        true
                    })
                }, size = 23, medium = true, maxLines = 1,
            )
            QueueModeControls(playback)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QueueText(
                "来自：${snapshot.sourceName.ifBlank { "当前播放列表" }}",
                Modifier.weight(1f), color = QueueSecondary, size = 12, maxLines = 1,
            )
            Box(
                Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                    .clickable(
                        enabled = snapshot.ids.isNotEmpty() && !dragging && !confirmClear,
                        role = Role.Button, onClick = { confirmClear = true },
                    ).padding(start = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                QueueText(
                    "清空", size = 13,
                    color = if (snapshot.ids.isNotEmpty() && !dragging) LibraryAccent else QueueSecondary,
                )
            }
        }
        if (confirmClear) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            QueueText(
                "清空当前 ${snapshot.ids.size} 首待播歌曲？正在播放和历史记录会保留。" +
                    if (playback.autoplayEnabled) "自动续播仍开启，清空后仍会续播。" else "",
                Modifier.padding(vertical = 16.dp), color = QueueSecondary, size = 15,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { confirmClear = false }
                        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center,
                ) { QueueText("取消", color = QueueSecondary) }
                Box(
                    Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) {
                        confirmClear = false
                        playback.clearUpcoming()
                    }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center,
                ) { QueueText("确认清空", color = LibraryAccent) }
            }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Leave enough trailing room to anchor current at the top even for a short queue;
            // otherwise LazyColumn backfills visible history when only one song remains.
            val visibleSongs = snapshot.ids.size + if (snapshot.current != null) 1 else 0
            val bottomSpace = maxOf(12.dp, maxHeight - 68.dp * visibleSongs)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !dragging,
                contentPadding = PaddingValues(bottom = bottomSpace),
            ) {
                items(rows, key = { it.key }, contentType = {
                    if (it is QueueRow.Song) "queue-song" else "queue-heading"
                }) { row ->
                    when (row) {
                        is QueueRow.Heading -> Column(Modifier.padding(top = 15.dp, bottom = 9.dp)) {
                            QueueText(row.title, size = 16, medium = true)
                            if (row.detail.isNotBlank()) QueueText(row.detail, color = QueueSecondary, size = 12)
                        }
                        is QueueRow.Song -> {
                            val id = row.entry.id
                            val index = snapshot.positions[id] ?: -1
                            val actions = buildList {
                                if (row.upcoming && index > 0) add(CustomAccessibilityAction("上移") {
                                    if (reorder.draggingId != null) false
                                    else {
                                        val position = currentSnapshot.positions[id] ?: -1
                                        if (position <= 0) false else move(id, currentSnapshot.ids[position - 1])
                                    }
                                })
                                if (row.upcoming && snapshot.canMoveDown(id)) {
                                    add(CustomAccessibilityAction("下移") {
                                        if (reorder.draggingId != null) false
                                        else {
                                            val position = currentSnapshot.positions[id] ?: -1
                                            if (!currentSnapshot.canMoveDown(id)) false
                                            else move(id, currentSnapshot.ids.getOrNull(position + 2))
                                        }
                                    })
                                }
                                if (!row.current) add(CustomAccessibilityAction("移除") { remove(id) })
                            }
                            QueueSongRow(
                                row, playback.playing, motionEnabled, reorder, listState, actions,
                                // Item placement is deliberately OFF throughout a drag (including other rows).
                                modifier = if (dragging || reorder.settlingId != null || !motionEnabled) Modifier else Modifier.animateItem(
                                    fadeInSpec = null,
                                    placementSpec = spring(dampingRatio = 1f, stiffness = 500f),
                                    fadeOutSpec = null,
                                ),
                                onPlay = {
                                    if (reorder.draggingId == null) {
                                        if (row.current) onDismiss() else playback.playQueueEntry(id)
                                    }
                                },
                                onRemove = { remove(id) },
                                onDragStart = {
                                    val liveIds = (playback.manualQueue + playback.contextQueue).map { it.id }
                                    if (liveIds == currentSnapshot.ids && reorder.begin(
                                            id, liveIds, playback.currentQueueEntry?.id, listState,
                                        )
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { delta ->
                                    if (reorder.draggingId == id) {
                                        reorder.drag(delta, listState)
                                        reorder.updateTarget(listState)
                                    }
                                },
                                onDragEnd = {
                                    if (reorder.draggingId == id) {
                                        val liveIds = (playback.manualQueue + playback.contextQueue).map { it.id }
                                        reorder.finish(liveIds, playback.currentQueueEntry?.id, motionEnabled)
                                            ?.let { move(it.first, it.second) }
                                    }
                                },
                                onDragCancel = { if (reorder.draggingId == id) reorder.cancel(motionEnabled) },
                            )
                        }
                    }
                }
                if (snapshot.ids.isEmpty()) item(key = "queue:empty") {
                    QueueText(
                        "暂无待播歌曲",
                        Modifier.padding(vertical = 14.dp), color = QueueSecondary, size = 12,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun QueueModeControls(playback: PlaybackController) {
    val autoplay = playback.autoplayEnabled
    Row {
        QueueModeButton(
            "随机", if (playback.shuffled) "已开启" else "已关闭", QueueSymbol.Shuffle,
            active = playback.shuffled, enabled = !autoplay,
            onClick = playback::toggleShuffle,
        )
        QueueModeButton(
            "循环", when (playback.repeatMode) {
                RepeatMode.Off -> "已关闭"
                RepeatMode.All -> "全部"
                RepeatMode.One -> "单曲"
            },
            if (playback.repeatMode == RepeatMode.One) QueueSymbol.RepeatOne else QueueSymbol.Repeat,
            active = playback.repeatMode != RepeatMode.Off, enabled = !autoplay,
            onClick = playback::cycleRepeat,
        )
        QueueModeButton(
            "自动续播", if (autoplay) "已开启" else "已关闭", QueueSymbol.Infinity,
            active = autoplay, enabled = true,
            onClick = playback::toggleAutoplay,
        )
    }
}

@Composable
private fun QueueModeButton(
    title: String,
    state: String,
    symbol: QueueSymbol,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> Color.White.copy(alpha = .32f)
        active -> LibraryAccent
        else -> Color.White.copy(alpha = .75f)
    }
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active && enabled) LibraryAccent.copy(alpha = .14f) else Color.Transparent)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = if (enabled) state else "$state；自动续播时不可更改"
                selected = active
            }
            .toggleable(value = active, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        QueueIcon(symbol, Modifier.size(23.dp), tint)
    }
}

@Composable
private fun QueueSongRow(
    row: QueueRow.Song,
    playing: Boolean,
    motionEnabled: Boolean,
    reorder: QueueReorderState,
    listState: LazyListState,
    actions: List<CustomAccessibilityAction>,
    modifier: Modifier,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val id = row.entry.id
    val track = row.entry.track
    val scope = rememberCoroutineScope()
    val swipe = remember(id) { QueueSwipeState(scope) }
    val isDragged = reorder.draggingId == id
    val isSettling = reorder.settlingId == id
    val anyDrag = reorder.draggingId != null
    val density = LocalDensity.current
    val revealWidth = with(density) { 72.dp.toPx() }
    LaunchedEffect(row.current, anyDrag, motionEnabled) {
        if (row.current || anyDrag || !motionEnabled) swipe.reset(false)
    }

    Box(
        modifier.fillMaxWidth().zIndex(if (isDragged || isSettling) 1f else 0f)
            .graphicsLayer { translationY = reorder.translation(id, listState) },
    ) {
        Box(
            Modifier.matchParentSize().clip(RoundedCornerShape(12.dp))
                .drawWithContent {
                    val exposed = (-swipe.offset).coerceIn(0f, size.width)
                    if (exposed > 0f) clipRect(left = size.width - exposed) {
                        drawRect(LibraryAccent.copy(alpha = .85f))
                        this@drawWithContent.drawContent()
                    }
                }
                .padding(end = 20.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            QueueIcon(
                QueueSymbol.Remove,
                Modifier.size(22.dp).graphicsLayer {
                    alpha = (-swipe.offset / revealWidth).coerceIn(0f, 1f)
                },
                Color.White,
            )
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .graphicsLayer {
                    translationX = swipe.offset
                    shape = RoundedCornerShape(12.dp)
                    clip = true
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = 68.dp)
                    .queueSwipeToRemove(id, !row.current && !anyDrag, swipe, motionEnabled, onRemove)
                    .semantics(mergeDescendants = true) {
                        stateDescription = when {
                            row.current && playing -> "正在播放"
                            row.current -> "当前歌曲，已暂停"
                            row.upcoming -> "待播歌曲"
                            else -> "播放历史"
                        }
                        customActions = actions
                    }
                    .clickable(enabled = !anyDrag, role = Role.Button, onClickLabel = "播放这首歌曲", onClick = onPlay)
                    .padding(end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArtwork(
                    track, Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)).clearAndSetSemantics { },
                    requestSize = 128,
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    QueueText(
                        track.title, color = if (row.current) LibraryAccent else Color.White,
                        size = 16, medium = row.current, maxLines = 2,
                    )
                    QueueText(
                        track.artist.ifBlank { "未知艺术家" } +
                            if (row.sourceLabel.isNotBlank()) " · ${row.sourceLabel}" else "",
                        color = QueueSecondary, size = 13, maxLines = 1,
                    )
                }
                if (row.current) {
                    Spacer(Modifier.width(10.dp))
                    QueueWaveform(playing, motionEnabled, Modifier.size(22.dp, 25.dp).clearAndSetSemantics { })
                }
            }
            if (row.upcoming) Box(
                Modifier.size(48.dp, 68.dp)
                    // The handle is NOT a playback target. TalkBack uses the song's named actions.
                    .clearAndSetSemantics { }
                    .queueReorderHandle(id, onDragStart, onDrag, onDragEnd, onDragCancel),
                contentAlignment = Alignment.Center,
            ) {
                QueueIcon(
                    QueueSymbol.Handle, Modifier.size(28.dp, 20.dp),
                    if (isDragged) LibraryAccent else Color.White.copy(alpha = .31f),
                )
            }
        }
    }
}

@Composable
private fun QueueEdgeScroll(reorder: QueueReorderState, listState: LazyListState) {
    val density = LocalDensity.current
    LaunchedEffect(reorder.draggingId, listState, density) {
        val id = reorder.draggingId ?: return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val maxSpeed = with(density) { 540.dp.toPx() }
        var previous = withFrameNanos { it }
        while (isActive && reorder.draggingId == id) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, .032f)
            previous = now
            val speed = reorder.edgeVelocity(listState, edge, maxSpeed)
            if (speed != 0f) listState.scrollBy(speed * seconds)
            reorder.updateTarget(listState)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun rememberQueueMotionEnabled(): Boolean {
    var enabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val scale = currentCoroutineContext()[MotionDurationScale]
        snapshotFlow { scale?.scaleFactor ?: 1f }.collectLatest {
            enabled = it.isFinite() && it > 0f
        }
    }
    return enabled
}

@Composable
private fun QueueWaveform(playing: Boolean, motionEnabled: Boolean, modifier: Modifier) {
    val pulse = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playing, motionEnabled, lifecycle) {
        if (!playing || !motionEnabled) {
            pulse.snapTo(0f)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                pulse.animateTo(1f, spring(dampingRatio = 1f, stiffness = 32f))
                pulse.animateTo(0f, spring(dampingRatio = 1f, stiffness = 32f))
            }
        }
    }
    Canvas(modifier) {
        // Animation state is read in draw only: no per-frame row/list recomposition or measurement.
        val progress = pulse.value
        val width = size.width / 7f
        repeat(4) { index ->
            val height = if (!playing || !motionEnabled) .20f + (index % 2) * .13f else {
                .22f + .7f * ((sin(progress * PI * 2 + index * 1.65).toFloat() + 1f) / 2f)
            }
            val pixels = size.height * height
            drawRoundRect(
                color = LibraryAccent,
                topLeft = Offset(index * width * 2f, (size.height - pixels) / 2f),
                size = Size(width, pixels), cornerRadius = CornerRadius(width / 2f),
            )
        }
    }
}

@Composable
private fun QueueText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: Int = 14,
    medium: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color, fontSize = size.sp, lineHeight = (size + 5).sp,
            fontFamily = PlayerTypography.familyFor(text, medium),
        ),
    )
}

private enum class QueueSymbol { Shuffle, Repeat, RepeatOne, Infinity, Handle, Remove }

@Composable
private fun QueueIcon(symbol: QueueSymbol, modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                drawLine(tint, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.8f, cap = StrokeCap.Round)
            }
            when (symbol) {
                QueueSymbol.Infinity -> {
                    val path = Path().apply {
                        moveTo(12f, 12f)
                        cubicTo(8.5f, 6f, 2f, 5.7f, 2f, 12f)
                        cubicTo(2f, 18.3f, 8.5f, 18f, 12f, 12f)
                        cubicTo(15.5f, 6f, 22f, 5.7f, 22f, 12f)
                        cubicTo(22f, 18.3f, 15.5f, 18f, 12f, 12f)
                    }
                    drawPath(path, tint, style = stroke)
                }
                QueueSymbol.Shuffle -> {
                    drawPath(Path().apply {
                        moveTo(3f, 6f); lineTo(5f, 6f)
                        cubicTo(10f, 6f, 14f, 18f, 19f, 18f); lineTo(21f, 18f)
                        moveTo(3f, 18f); lineTo(5f, 18f)
                        cubicTo(7f, 18f, 8.5f, 16f, 9.5f, 14.5f)
                        moveTo(14.5f, 9.5f); cubicTo(16f, 7f, 17f, 6f, 19f, 6f); lineTo(21f, 6f)
                    }, tint, style = stroke)
                    line(18f, 3f, 21f, 6f); line(21f, 6f, 18f, 9f)
                    line(18f, 15f, 21f, 18f); line(21f, 18f, 18f, 21f)
                }
                QueueSymbol.Repeat, QueueSymbol.RepeatOne -> {
                    drawPath(Path().apply {
                        moveTo(4f, 11f); lineTo(4f, 9f)
                        quadraticTo(4f, 6f, 7f, 6f); lineTo(20f, 6f)
                        moveTo(20f, 13f); lineTo(20f, 15f)
                        quadraticTo(20f, 18f, 17f, 18f); lineTo(4f, 18f)
                    }, tint, style = stroke)
                    line(17f, 3f, 20f, 6f); line(20f, 6f, 17f, 9f)
                    line(7f, 15f, 4f, 18f); line(4f, 18f, 7f, 21f)
                    if (symbol == QueueSymbol.RepeatOne) {
                        line(10.5f, 10.5f, 12.5f, 9.5f); line(12.5f, 9.5f, 12.5f, 14.5f)
                    }
                }
                QueueSymbol.Handle -> repeat(2) { index ->
                    drawLine(
                        tint, Offset(2f, 8f + index * 7f), Offset(22f, 8f + index * 7f),
                        strokeWidth = 1.15f, cap = StrokeCap.Round,
                    )
                }
                QueueSymbol.Remove -> {
                    line(4f, 6f, 20f, 6f); line(9f, 3f, 15f, 3f)
                    drawPath(Path().apply {
                        moveTo(6f, 6f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 6f)
                    }, tint, style = stroke)
                    line(10f, 10f, 10.5f, 16.5f); line(14f, 10f, 13.5f, 16.5f)
                }
            }
        }
    }
}
