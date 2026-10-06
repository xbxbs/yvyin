package com.example.xuebimc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

internal fun queueEntryKey(id: String): String = "entry:$id"

/** Pointer deltas are observed only by graphicsLayer, never by list composition/layout. */
internal class QueueSwipeState(private val scope: CoroutineScope) {
    var offset by mutableFloatStateOf(0f)
        private set
    private var returnJob: Job? = null

    fun drag(delta: Float, width: Float) {
        returnJob?.cancel()
        returnJob = null
        offset = (offset + delta).coerceIn(-width, 0f)
    }

    fun reset(animate: Boolean) {
        returnJob?.cancel()
        returnJob = null
        if (!animate) {
            offset = 0f
            return
        }
        if (offset == 0f) return
        returnJob = scope.launch {
            Animatable(offset).animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f)) {
                offset = value
            }
        }
    }
}

/** Left-only, direction-locked deletion. Vertical/diagonal movement stays with LazyColumn. */
@Composable
internal fun Modifier.queueSwipeToRemove(
    id: String,
    enabled: Boolean,
    state: QueueSwipeState,
    motionEnabled: Boolean,
    onRemove: () -> Unit,
): Modifier {
    val latestRemove by rememberUpdatedState(onRemove)
    val latestMotion by rememberUpdatedState(motionEnabled)
    return pointerInput(id, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var totalX = 0f
            var totalY = 0f
            var claimed = false
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                    if (change.changedToUpIgnoreConsumed()) {
                        released = claimed
                        if (claimed) change.consume()
                        break
                    }
                    val delta = change.positionChange()
                    if (!claimed) {
                        totalX += delta.x
                        totalY += delta.y
                        val slop = viewConfiguration.touchSlop
                        if (abs(totalY) > slop || totalX > slop) break
                        if (totalX < -slop && abs(totalX) > abs(totalY) * 1.6f) {
                            claimed = true
                            change.consume()
                            state.drag(totalX + slop, size.width.toFloat())
                        }
                    } else {
                        change.consume()
                        state.drag(delta.x, size.width.toFloat())
                    }
                }
            } finally {
                // Only a normal pointer-up beyond the threshold commits; cancellation never does.
                val threshold = (size.width * .28f).coerceAtLeast(72f * density)
                if (released && state.offset <= -threshold) latestRemove()
                state.reset(latestMotion)
            }
        }
    }
}

/** Ephemeral IDs only. No playback mutation or persistence happens while the finger moves. */
@OptIn(ExperimentalFoundationApi::class)
internal class QueueReorderState(private val scope: CoroutineScope) {
    var draggingId by mutableStateOf<String?>(null)
        private set
    var previewIds by mutableStateOf<List<String>?>(null)
        private set
    var centerY by mutableFloatStateOf(0f)
        private set
    var initialHeight = 0
        private set
    var settlingId by mutableStateOf<String?>(null)
        private set
    private var settlingCenter = 0f
    private var settleProgress = Animatable(1f)
    private var settleJob: Job? = null
    private var originalIds: List<String> = emptyList()
    private var playingIdAtStart: String? = null
    private var keyPositions: Map<String, Int> = emptyMap()
    private var draggingKey: String? = null
    private var reorderedLayout: LazyListLayoutInfo? = null
    private var touched = false

    fun begin(id: String, ids: List<String>, currentId: String?, list: LazyListState): Boolean {
        if (draggingId != null || id !in ids) return false
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == queueEntryKey(id) }
            ?: return false
        stopSettling()
        originalIds = ids
        playingIdAtStart = currentId
        setPreview(ids)
        draggingKey = queueEntryKey(id)
        reorderedLayout = null
        initialHeight = item.size
        centerY = item.offset + item.size / 2f
        touched = false
        draggingId = id
        return true
    }

    fun drag(delta: Float, list: LazyListState) {
        val info = list.layoutInfo
        val top = info.viewportStartOffset + initialHeight / 2f
        val bottom = (info.viewportEndOffset - initialHeight / 2f).coerceAtLeast(top)
        centerY = (centerY + delta).coerceIn(top, bottom)
        touched = touched || abs(delta) > 0f
    }

    /** Called for pointer/scroll/layout changes, not to continuously re-layout a translated row. */
    fun updateTarget(list: LazyListState) {
        val id = draggingId ?: return
        if (!touched) return
        val ids = previewIds ?: return
        val layout = list.layoutInfo
        // Wait for the preceding preview to be measured before comparing any new midpoints.
        if (reorderedLayout === layout) return
        val source = positionOfKey(draggingKey)
        if (source < 0) return
        val dragged = layout.visibleItemsInfo.firstOrNull { it.key == draggingKey }
            ?: return
        val direction = centerY.compareTo(dragged.offset + dragged.size / 2f)
        if (direction == 0) return
        val visible = layout.visibleItemsInfo
        val target = if (direction > 0) {
            visible.lastOrNull { item ->
                val targetIndex = positionOfKey(item.key)
                targetIndex > source && centerY > item.offset + item.size / 2f
            }
        } else {
            visible.firstOrNull { item ->
                val targetIndex = positionOfKey(item.key)
                targetIndex in 0 until source && centerY < item.offset + item.size / 2f
            }
        } ?: return
        val destination = positionOfKey(target.key)
        if (destination < 0) return
        reorderedLayout = layout
        // Stable-key anchoring must not pin the dragged first-visible row to the viewport edge.
        // This runs once per crossed row, not once per animation frame.
        if (dragged.index == list.firstVisibleItemIndex || target.index == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        setPreview(ids.toMutableList().apply {
            removeAt(source)
            add(destination, id)
        })
    }

    fun translation(id: String, list: LazyListState): Float {
        if (draggingId != id && settlingId != id) return 0f
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == queueEntryKey(id) }
            ?: return 0f
        val midpoint = item.offset + item.size / 2f
        return if (draggingId == id) centerY - midpoint
        else (settlingCenter - midpoint) * (1f - settleProgress.value)
    }

    fun edgeVelocity(list: LazyListState, edge: Float, maxSpeed: Float): Float {
        if (draggingId == null || !touched) return 0f
        val info = list.layoutInfo
        val top = info.viewportStartOffset.toFloat()
        val bottom = info.viewportEndOffset.toFloat()
        val band = edge.coerceAtMost((bottom - top) / 3f).coerceAtLeast(1f)
        val upper = centerY - initialHeight / 2f
        val lower = centerY + initialHeight / 2f
        val index = positionOfKey(draggingKey)
        if (index < 0) return 0f
        val last = (previewIds?.size ?: 0) - 1
        return when {
            // Never scroll a dragged upcoming row away into history or the footer.
            index > 0 && upper < top + band -> -maxSpeed * ((top + band - upper) / band).coerceIn(0f, 1f)
            index < last && lower > bottom - band -> maxSpeed * ((lower - bottom + band) / band).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    /** Null means canceled/no-op. A successful gesture produces exactly one engine command. */
    fun finish(liveIds: List<String>, currentId: String?, animate: Boolean): Pair<String, String?>? {
        val id = draggingId
        val ids = previewIds
        val move = if (liveIds == originalIds && currentId == playingIdAtStart &&
            id != null && ids != null && ids != originalIds
        ) {
            id to ids.getOrNull(ids.indexOf(id) + 1)
        } else null
        cancel(animate)
        return move
    }

    fun cancel(animate: Boolean = false) {
        val releasedId = draggingId
        val releasedCenter = centerY
        stopSettling()
        draggingId = null
        previewIds = null
        originalIds = emptyList()
        playingIdAtStart = null
        keyPositions = emptyMap()
        draggingKey = null
        reorderedLayout = null
        touched = false
        if (animate && releasedId != null) {
            settlingCenter = releasedCenter
            settleProgress = Animatable(0f)
            settlingId = releasedId
            settleJob = scope.launch {
                settleProgress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 500f))
                settlingId = null
            }
        }
    }

    fun stopSettling() {
        settleJob?.cancel()
        settleJob = null
        settlingId = null
    }

    private fun setPreview(ids: List<String>) {
        keyPositions = ids.withIndex().associate { queueEntryKey(it.value) to it.index }
        previewIds = ids
    }

    private fun positionOfKey(key: Any?): Int =
        (key as? String)?.let { keyPositions[it] } ?: -1
}

@Composable
internal fun Modifier.queueReorderHandle(
    id: String,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val latestStart by rememberUpdatedState(onStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestEnd by rememberUpdatedState(onEnd)
    val latestCancel by rememberUpdatedState(onCancel)
    return pointerInput(id) {
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = { latestStart() },
                onDrag = { change, delta ->
                    change.consume()
                    latestDrag(delta.y)
                },
                onDragEnd = { latestEnd() },
                onDragCancel = { latestCancel() },
            )
        } finally {
            latestCancel()
        }
    }
}
