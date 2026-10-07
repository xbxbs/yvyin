package com.example.xuebimc

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Only the song's visual content changes here. The caller owns every resting bound,
 * shared-artwork transform and playback action; none wait for this transition.
 * Keying by playback identity keeps late metadata updates from replaying the fade.
 */
@Composable
internal fun <T> PlayerTrackChange(
    value: T,
    contentKey: (T) -> Any?,
    modifier: Modifier = Modifier,
    label: String,
    content: @Composable (T) -> Unit,
) {
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val duration = if (motionScale.isFinite() && motionScale > 0f) 220 else 0
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        contentKey = contentKey,
        transitionSpec = {
            (fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))).using(null)
        },
        label = label,
    ) { displayed ->
        // The outgoing song remains visual-only while the next song is already active.
        Box(if (contentKey(displayed) != contentKey(value)) Modifier.clearAndSetSemantics {} else Modifier) {
            content(displayed)
        }
    }
}

internal data class PlayerTrackPresentation(
    val key: String?,
    val track: Track?,
    val title: String,
    val artist: String,
)
