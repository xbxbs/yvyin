package com.example.xuebimc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Direct-manipulation state for the navigation lens. The gesture owns its live position while
 * dragging; release transfers both position and velocity to an interruptible Compose spring.
 * This is an app-local implementation, not KernelSU's DampedDragAnimation/gesture inspector.
 */
@Stable
internal class LiquidNavigationMotion(
    selectedIndex: Int,
    private val count: Int,
    private val scope: CoroutineScope,
) {
    private val location = Animatable(selectedIndex.toFloat(), visibilityThreshold = .001f)
    private val pressure = Animatable(0f, visibilityThreshold = .001f)
    private var locationJob: Job? = null
    private var pressureJob: Job? = null
    private var dragging by mutableStateOf(false)
    private var dragLocation by mutableFloatStateOf(selectedIndex.toFloat())
    private var dragSpeed by mutableFloatStateOf(0f)
    private var rawDragLocation = selectedIndex.toFloat()

    var committedIndex by mutableIntStateOf(selectedIndex)
        private set
    var motionAllowed: Boolean = true

    val value: Float get() = if (dragging) dragLocation else location.value
    val velocity: Float get() = if (dragging) dragSpeed else location.velocity
    val pressProgress: Float get() = pressure.value

    fun syncSelection(index: Int) {
        if (committedIndex != index) {
            committedIndex = index
            settle(index.toFloat())
            setPressed(false)
        }
    }

    fun press(index: Int) {
        setPressed(true)
        settle(index.toFloat())
    }

    fun startDrag() {
        val liveValue = value
        locationJob?.cancel()
        rawDragLocation = liveValue
        dragLocation = liveValue
        dragSpeed = 0f
        dragging = true
    }

    fun dragBy(amount: Float, speed: Float) {
        rawDragLocation += amount
        val last = (count - 1).toFloat()
        val edge = rawDragLocation.coerceIn(0f, last)
        val excess = rawDragLocation - edge
        // Small, bounded resistance at the end tabs instead of a hard frozen edge.
        dragLocation = edge + excess / (1f + abs(excess) * 18f)
        dragSpeed = speed
    }

    fun commit(index: Int, releaseVelocity: Float = 0f): Boolean {
        val target = index.coerceIn(0, count - 1)
        val changed = committedIndex != target
        committedIndex = target
        settle(target.toFloat(), releaseVelocity)
        setPressed(false)
        return changed
    }

    fun cancel() {
        settle(committedIndex.toFloat())
        setPressed(false)
    }

    private fun settle(target: Float, initialVelocity: Float = velocity) {
        val presentationValue = value
        locationJob?.cancel()
        locationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            location.snapTo(presentationValue)
            dragging = false
            if (motionAllowed) {
                location.animateTo(
                    target,
                    spring(dampingRatio = 1f, stiffness = 620f, visibilityThreshold = .001f),
                    initialVelocity = initialVelocity,
                )
            } else location.snapTo(target)
        }
    }

    private fun setPressed(pressed: Boolean) {
        pressureJob?.cancel()
        pressureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val target = if (pressed) 1f else 0f
            if (motionAllowed) {
                pressure.animateTo(target, spring(dampingRatio = 1f, stiffness = 1100f))
            } else pressure.snapTo(target)
        }
    }
}

/** A short projected release, bounded to avoid a tiny fast fling skipping every destination. */
internal fun liquidNavigationReleaseIndex(value: Float, velocity: Float, count: Int): Int =
    (value + (velocity * .1f).coerceIn(-.6f, .6f)).roundToInt().coerceIn(0, count - 1)

internal fun liquidNavigationIndexAt(
    x: Float,
    width: Float,
    inset: Float,
    count: Int,
    isRtl: Boolean,
): Int {
    val lane = ((width - inset * 2f) / count).coerceAtLeast(1f)
    val logicalX = if (isRtl) width - x else x
    return ((logicalX - inset) / lane).toInt().coerceIn(0, count - 1)
}

/** Pointer input lives on the bar, while its individual tabs retain keyboard/TalkBack actions. */
internal fun Modifier.liquidNavigationGestures(
    motion: LiquidNavigationMotion,
    count: Int,
    insetPx: Float,
    isRtl: Boolean,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
): Modifier = if (!enabled) this else pointerInput(motion, count, insetPx, isRtl) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val lane = ((size.width - insetPx * 2f) / count).coerceAtLeast(1f)
        val direction = if (isRtl) -1f else 1f
        val touchSlop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        motion.press(liquidNavigationIndexAt(down.position.x, size.width.toFloat(), insetPx, count, isRtl))
        var previous = down.position
        var dragging = false
        var cancelled = false
        var released = false
        var handled = false
        var lastPosition = down.position
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1) cancelled = true
                tracker.addPosition(change.uptimeMillis, change.position)
                lastPosition = change.position
                if (change.isConsumed) cancelled = true
                if (!change.pressed) {
                    change.consume()
                    released = true
                    break
                }
                val distance = change.position - down.position
                if (change.position.y < -touchSlop || change.position.y > size.height + touchSlop) {
                    cancelled = true
                }
                if (!dragging && abs(distance.y) > touchSlop && abs(distance.y) > abs(distance.x)) {
                    cancelled = true
                }
                if (!cancelled) {
                    val speed = (tracker.calculateVelocity().x / lane * direction).coerceIn(-20f, 20f)
                    if (!dragging && abs(distance.x) > touchSlop && abs(distance.x) > abs(distance.y)) {
                        motion.startDrag()
                        dragging = true
                        motion.dragBy((distance.x - distance.x.sign * touchSlop) / lane * direction, speed)
                    } else if (dragging) {
                        motion.dragBy((change.position.x - previous.x) / lane * direction, speed)
                    }
                }
                change.consume()
                previous = change.position
            }
            val inside = lastPosition.x in -touchSlop..(size.width + touchSlop) &&
                lastPosition.y in -touchSlop..(size.height + touchSlop)
            if (!released || cancelled || !inside) {
                motion.cancel()
            } else {
                val speed = if (dragging) {
                    (tracker.calculateVelocity().x / lane * direction).coerceIn(-20f, 20f)
                } else 0f
                val target = if (dragging) liquidNavigationReleaseIndex(motion.value, speed, count)
                else liquidNavigationIndexAt(lastPosition.x, size.width.toFloat(), insetPx, count, isRtl)
                if (motion.commit(target, speed)) onCommit(target)
            }
            handled = true
        } finally {
            if (!handled) motion.cancel()
        }
    }
}
