package com.example.xuebimc

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset

/** A bounded, low-contrast moving highlight layered over the Haze surface. */
@Composable
internal fun Modifier.liquidGlassSheen(): Modifier {
    val transition = rememberInfiniteTransition(label = "liquid-glass-sheen")
    val phase by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "liquid-glass-phase",
    )
    return drawWithCache {
        val start = size.width * (phase - .55f)
        val end = start + size.width * .55f
        val sheen = Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = .045f), Color.Transparent),
            Offset(start, 0f), Offset(end, size.height),
        )
        onDrawWithContent {
            drawContent()
            drawRect(sheen)
        }
    }
}
