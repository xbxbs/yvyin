package com.example.xuebimc

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalDragOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange

/** Grabber-only input: deltas are px, release velocity is px/s; the owner moves the player. */
@Composable
internal fun Modifier.playerDismissDrag(
    onDrag: (Float) -> Unit,
    onRelease: (Float) -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnCancel by rememberUpdatedState(onCancel)
    return pointerInput(Unit) {
        awaitEachGesture {
            // clickable owns taps; do not consume anything until vertical touch slop is crossed.
            val down = awaitFirstDown(requireUnconsumed = false)
            val velocityTracker = VelocityTracker()
            velocityTracker.addPointerInputChange(down)
            var dragging = false
            var releaseVelocity: Float? = null
            try {
                val dragStart = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    dragging = true
                    velocityTracker.addPointerInputChange(change)
                    change.consume()
                    currentOnDrag(overSlop)
                }
                if (dragStart != null) {
                    var pointerId = dragStart.id
                    while (true) {
                        val change = awaitVerticalDragOrCancellation(pointerId) ?: break
                        velocityTracker.addPointerInputChange(change)
                        if (change.changedToUpIgnoreConsumed()) {
                            releaseVelocity = velocityTracker.calculateVelocity().y
                            break
                        }
                        val deltaY = change.positionChange().y
                        change.consume()
                        currentOnDrag(deltaY)
                        pointerId = change.id
                    }
                }
            } finally {
                if (dragging) {
                    val velocityY = releaseVelocity
                    if (velocityY != null) currentOnRelease(velocityY) else currentOnCancel()
                }
            }
        }
    }
}
