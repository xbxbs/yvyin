package com.example.xuebimc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

internal fun playlistEntryKey(id: String) = "entry:$id"

/** Only midpoint crossings change the preview. The repository is called once on release. */
@Stable
internal class PlaylistReorderState(val list: LazyListState, initial: List<String>) {
    var order by mutableStateOf(initial)
        private set
    var draggingId by mutableStateOf<String?>(null)
        private set
    private var center by mutableFloatStateOf(0f)
    private var original = initial
    private var awaitingLayoutIndex: Int? = null

    fun sync(ids: List<String>) {
        if (draggingId == null) order = ids
        else if (ids.toSet() != original.toSet()) { cancel(); order = ids }
    }

    fun start(id: String): Boolean {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == playlistEntryKey(id) } ?: return false
        original = order
        awaitingLayoutIndex = null
        center = item.offset + item.size / 2f
        draggingId = id
        return true
    }

    fun drag(delta: Float) { center += delta; crossVisibleMidpoint() }

    fun crossVisibleMidpoint() {
        val id = draggingId ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == playlistEntryKey(id) } ?: return
        // Several pointer events can arrive before LazyColumn measures the new key order.
        // Do not swap back against the previous layout in that interval.
        awaitingLayoutIndex?.let { expected ->
            if (current.index != expected) return
            awaitingLayoutIndex = null
        }
        val currentCenter = current.offset + current.size / 2f
        val target = visible.filter { item ->
            val key = item.key as? String
            key != null && key.startsWith("entry:") && key != playlistEntryKey(id) &&
                if (center > currentCenter) item.offset + item.size / 2f in currentCenter..center
                else item.offset + item.size / 2f in center..currentCenter
        }.minByOrNull { abs(it.offset + it.size / 2f - center) } ?: return
        val from = order.indexOf(id)
        val to = order.indexOf((target.key as String).removePrefix("entry:"))
        if (from < 0 || to < 0 || from == to) return
        awaitingLayoutIndex = target.index
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun translation(id: String): Float {
        if (draggingId != id) return 0f
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == playlistEntryKey(id) } ?: return 0f
        return center - item.offset - item.size / 2f
    }

    fun edgeVelocity(edge: Float): Float {
        if (draggingId == null) return 0f
        val info = list.layoutInfo
        val stickyEnd = info.visibleItemsInfo.firstOrNull { it.key == "playlist:controls" }
            ?.let { it.offset + it.size } ?: info.viewportStartOffset
        val top = maxOf(info.viewportStartOffset, stickyEnd).toFloat()
        val bottom = info.viewportEndOffset.toFloat()
        return when {
            center < top + edge -> -((top + edge - center) / edge).coerceIn(0f, 1f)
            center > bottom - edge -> ((center - bottom + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    fun finish(commit: (List<String>) -> Unit) {
        if (draggingId == null) return
        draggingId = null
        awaitingLayoutIndex = null
        if (order != original) commit(order.toList())
    }

    fun cancel() { if (draggingId != null) order = original; draggingId = null; awaitingLayoutIndex = null }
}

@Composable
internal fun rememberPlaylistReorderState(list: LazyListState, ids: List<String>): PlaylistReorderState {
    val state = remember(list) { PlaylistReorderState(list, ids) }
    LaunchedEffect(ids) { state.sync(ids) }
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    val speed = with(density) { 620.dp.toPx() }
    LaunchedEffect(state.draggingId) {
        if (state.draggingId == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (state.draggingId != null) {
            val now = withFrameNanos { it }
            val elapsed = ((now - previous) / 1_000_000_000f).coerceIn(0f, .032f)
            previous = now
            val velocity = state.edgeVelocity(edge)
            if (velocity != 0f) {
                list.scrollBy(velocity * speed * elapsed)
                state.crossVisibleMidpoint()
            }
        }
    }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}

@Composable
internal fun Modifier.playlistDragHandle(
    state: PlaylistReorderState, id: String, enabled: Boolean, onCommit: (List<String>) -> Unit,
): Modifier {
    val commit by rememberUpdatedState(onCommit)
    val haptics = LocalHapticFeedback.current
    return if (!enabled) this else pointerInput(state, id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { if (state.start(id)) haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onDragEnd = { state.finish(commit) }, onDragCancel = state::cancel,
            onDrag = { change, delta -> change.consume(); state.drag(delta.y) },
        )
    }
}

/** A left swipe reveals the same four accessible actions as long press; it never plays. */
@Composable
internal fun Modifier.playlistSwipeActions(enabled: Boolean, motion: Boolean, onOpen: () -> Unit): Modifier {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState(onOpen)
    val motionAllowed by rememberUpdatedState(motion)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var distance = remember { 0f }
    return graphicsLayer { translationX = offset.value }
        .then(if (!enabled) Modifier else Modifier.pointerInput(threshold) {
            fun reset() {
                scope.launch {
                    if (motionAllowed) offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 650f))
                    else offset.snapTo(0f)
                }
            }
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f; scope.launch { offset.stop() } },
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    distance = (distance + dx).coerceIn(-threshold * 1.5f, 0f)
                    scope.launch { offset.snapTo(distance) }
                },
                onDragEnd = { if (distance <= -threshold) open(); reset() },
                onDragCancel = { reset() },
            )
        })
}
