package com.example.xuebimc

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val SEEK_INTERVAL_MS = 180L
private const val REVEAL_INTERVAL_MS = 720L

internal data class PlayerSeekGestureKey(
    // Queue occurrence ID when hosted by the app, so consecutive copies also cancel an old hold.
    val trackKey: String?,
    val durationMs: Long,
    val lyricsVisible: Boolean,
    val queueVisible: Boolean,
)

internal class PlayerSeekGesture(
    val key: PlayerSeekGestureKey,
    val guard: PlayerSeekGestureGuard,
    val positionMs: () -> Long,
    val onSeek: (Long) -> Unit,
    val onRevealControls: () -> Unit,
)

// Observe at the page root: a second pointer may hit an entirely different button.
// This never consumes events or changes the other controls' click handling.
internal class PlayerSeekGestureGuard {
    private var blockedUntilAllUp = false
    private var cancelHeldGesture: (() -> Unit)? = null

    fun observe(event: PointerEvent) {
        // ACTION_CANCEL need not deliver an all-up event; a fresh down resets its latch.
        if (event.changes.none { it.previousPressed }) blockedUntilAllUp = false
        if (event.changes.count { it.pressed || it.previousPressed } > 1) {
            blockedUntilAllUp = true
            cancel()
        }
        if (event.changes.none { it.pressed }) blockedUntilAllUp = false
    }

    fun acquire(cancel: () -> Unit): Boolean {
        if (blockedUntilAllUp || cancelHeldGesture != null) return false
        cancelHeldGesture = cancel
        return true
    }

    fun release(cancel: () -> Unit) {
        if (cancelHeldGesture === cancel) cancelHeldGesture = null
    }

    fun cancel() {
        cancelHeldGesture?.invoke()
    }
}

// Saturate before adding, so even an unusually large duration cannot overflow.
private fun seekTarget(start: Long, amount: Long, duration: Long, forward: Boolean): Long =
    if (forward) start + minOf(amount, duration - start) else start - minOf(amount, start)

/** Press feedback is immediate; only a horizontal drag or an uncancelled up changes the value. */
@Composable
internal fun Modifier.playerSliderGesture(
    gesture: PlayerSeekGesture,
    enabled: Boolean,
    onInteractionChanged: (pressed: Boolean, dragging: Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
): Modifier {
    val currentGesture by rememberUpdatedState(gesture)
    val currentEnabled by rememberUpdatedState(enabled)
    val interaction by rememberUpdatedState(onInteractionChanged)
    val changeValue by rememberUpdatedState(onValueChange)
    val key = gesture.key
    return pointerInput(key, gesture.guard, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.isConsumed || currentEvent.changes.count { it.pressed } != 1 ||
                down.position.x !in 0f..size.width.toFloat() ||
                down.position.y !in 0f..size.height.toFloat()
            ) return@awaitEachGesture
            var cancelled = false
            var dragging = false
            val cancel: () -> Unit = {
                cancelled = true
                interaction(false, false)
            }
            if (!gesture.guard.acquire(cancel)) return@awaitEachGesture
            try {
                down.consume()
                interaction(true, false)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (cancelled || !currentEnabled || currentGesture.key != key ||
                        change == null || change.isConsumed ||
                        event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) }
                    ) break
                    val delta = change.position - down.position
                    if (!dragging) {
                        // Give vertical page gestures priority; never turn them into tap-to-seek.
                        if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) break
                        if (abs(delta.x) > viewConfiguration.touchSlop) {
                            dragging = true
                            interaction(true, true)
                        } else if (change.position.x !in 0f..size.width.toFloat() ||
                            change.position.y !in 0f..size.height.toFloat()
                        ) break
                    }
                    if (!change.pressed) {
                        if (change.previousPressed) {
                            change.consume()
                            changeValue((change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                        break
                    }
                    if (dragging) {
                        change.consume()
                        changeValue((change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                    }
                }
            } finally {
                interaction(false, false)
                gesture.guard.release(cancel)
            }
        }
    }
}

@Composable
internal fun Modifier.playerSeekGesture(
    gesture: PlayerSeekGesture,
    forward: Boolean,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier {
    val currentGesture by rememberUpdatedState(gesture)
    val currentClick by rememberUpdatedState(onClick)
    val currentEnabled by rememberUpdatedState(enabled)
    val key = gesture.key
    fun isCurrent() = currentEnabled && currentGesture.key == key
    if (!enabled) return clearAndSetSemantics { }

    return pointerInput(key, forward, enabled, gesture.guard, interactionSource) {
        val longPressMillis = viewConfiguration.longPressTimeoutMillis
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.isConsumed) return@awaitEachGesture
                // Own touch input before clickable; it still supplies keyboard/click semantics.
                down.consume()
                if (!isCurrent() || currentEvent.changes.count { it.pressed } != 1 ||
                    down.position.x !in 0f..size.width.toFloat() ||
                    down.position.y !in 0f..size.height.toFloat()
                ) {
                    return@awaitEachGesture
                }
                val press = PressInteraction.Press(down.position)
                var pressActive = false
                var cancelled = false
                var released = false
                var holdStarted = false
                var holdJob: Job? = null
                fun finishPress(release: Boolean) {
                    if (!pressActive) return
                    interactionSource.tryEmit(
                        if (release) PressInteraction.Release(press) else PressInteraction.Cancel(press)
                    )
                    pressActive = false
                }
                val cancelGesture: () -> Unit = {
                    cancelled = true
                    holdJob?.cancel()
                    finishPress(false)
                }
                if (!gesture.guard.acquire(cancelGesture)) return@awaitEachGesture
                try {
                    pressActive = interactionSource.tryEmit(press)
                    currentGesture.onRevealControls()
                    // Invalid/unknown duration keeps ordinary click handling, without a hold timer.
                    if (key.durationMs > 0L) {
                        holdJob = launch {
                            delay(longPressMillis)
                            if (cancelled || !isCurrent()) return@launch
                            holdStarted = true
                            val startedAt = SystemClock.uptimeMillis()
                            var nextRevealAt = startedAt + REVEAL_INTERVAL_MS
                            currentGesture.onRevealControls()
                            var target = currentGesture.positionMs().coerceIn(0L, key.durationMs)
                            var atBoundary = if (forward) target == key.durationMs else target == 0L
                            while (isActive && !cancelled && isCurrent()) {
                                val now = SystemClock.uptimeMillis()
                                if (now >= nextRevealAt) {
                                    currentGesture.onRevealControls()
                                    nextRevealAt = now + REVEAL_INTERVAL_MS
                                }
                                if (!atBoundary) {
                                    // Ramp from 4x to 32x over seven seconds, with no frame callbacks.
                                    val speed = (4.0 + (now - startedAt) / 250.0).coerceAtMost(32.0)
                                    val step = (SEEK_INTERVAL_MS * speed).toLong()
                                    target = seekTarget(target, step, key.durationMs, forward)
                                    currentGesture.onSeek(target)
                                    atBoundary = target == 0L || target == key.durationMs
                                }
                                // At an edge only keep controls visible; never resend the edge seek.
                                delay(SEEK_INTERVAL_MS)
                            }
                        }
                    }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (cancelled || !isCurrent() || change == null || change.isConsumed ||
                            event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) } ||
                            change.position.x !in 0f..size.width.toFloat() ||
                            change.position.y !in 0f..size.height.toFloat()
                        ) {
                            change?.consume()
                            cancelGesture()
                            break
                        }
                        change.consume()
                        if (!change.pressed) {
                            released = change.previousPressed
                            break
                        }
                    }
                } finally {
                    holdJob?.cancel()
                    finishPress(released && !cancelled)
                    gesture.guard.release(cancelGesture)
                }
                // No timeout or join on release: an ordinary tap calls the original action now.
                if (released && !cancelled && !holdStarted && isCurrent()) currentClick()
            }
        }
    }.clickable(
        interactionSource = interactionSource,
        indication = null,
        role = Role.Button,
        onClick = {
            if (isCurrent()) {
                gesture.guard.cancel()
                currentClick()
            }
        },
    ).semantics {
        if (key.durationMs > 0L) {
            customActions = listOf(
                CustomAccessibilityAction(if (forward) "快进 10 秒" else "快退 10 秒") {
                    if (!isCurrent()) {
                        false
                    } else {
                        gesture.guard.cancel()
                        val start = currentGesture.positionMs().coerceIn(0L, key.durationMs)
                        val target = seekTarget(start, 10_000L, key.durationMs, forward)
                        if (target != start) {
                            currentGesture.onRevealControls()
                            currentGesture.onSeek(target)
                        }
                        true
                    }
                }
            )
        }
    }
}
