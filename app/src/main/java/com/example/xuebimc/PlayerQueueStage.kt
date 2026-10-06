package com.example.xuebimc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** A transparent sibling of the lyric stage, never a sheet or a full-page gesture target. */
@Composable
internal fun PlayerQueueStage(
    pageWidth: Dp,
    pageHeight: Dp,
    queueVisible: Boolean,
    reveal: () -> Float,
    content: @Composable (Modifier, Float) -> Unit,
) {
    val mounted by remember(reveal) { derivedStateOf { reveal() > 0f } }
    if (!mounted) return

    // Fixed geometry clears the grabber's entire touch target and the timeline's 44dp target.
    // Neither layout dimension reads animation state; only this stage's layer moves/fades.
    val top = pageHeight * .074f + 28.dp
    val bottom = pageHeight * .660f - 30.dp
    Box(
        Modifier.offset(x = pageWidth * .067f, y = top)
            .size(pageWidth * .866f, (bottom - top).coerceAtLeast(1.dp))
            .clipToBounds()
            .zIndex(if (queueVisible) 1f else 0f)
            .graphicsLayer {
                alpha = reveal()
                translationY = 8.dp.toPx() * (1f - alpha)
            }
            .then(if (!queueVisible) Modifier.clearAndSetSemantics { } else Modifier)
            .pointerInput(queueVisible) {
                // An outgoing queue cannot activate a song; this handler is stage-bounded.
                if (!queueVisible) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
    ) {
        // Opacity belongs exclusively to the layer; never sample animation in the slot.
        // Keep the legacy Float argument stable so the queue does not recompose per frame.
        content(Modifier.fillMaxSize(), 1f)
    }
}
