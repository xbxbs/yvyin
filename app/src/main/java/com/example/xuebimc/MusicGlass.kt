package com.example.xuebimc

import androidx.compose.animation.core.Easing
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

internal val LocalAnimatedBackground = staticCompositionLocalOf { true }
internal val LocalLiquidGlass = staticCompositionLocalOf { false }
private val GlassPageBackground = Color.Black

/** Same single-layer material as the user-provided ChatGlass sample. */
@Composable
internal fun Modifier.musicGlassSurface(backdrop: HazeState, shape: Shape): Modifier {
    val style = remember {
        HazeStyle(
            backgroundColor = GlassPageBackground,
            tints = listOf(HazeTint(Color.White.copy(alpha = .08f))),
            blurRadius = 32.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(GlassPageBackground.copy(alpha = .94f)),
        )
    }
    val rim = remember {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f), Color.White.copy(alpha = .03f)))
    }
    val hairline = with(LocalDensity.current) { .5f.toDp() }
    return clip(shape).border(hairline, rim, shape).hazeEffect(backdrop, style)
}

/** Blur radius fades toward the body; a separate opaque scrim protects header text. */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun MusicHeaderGlass(
    backdrop: HazeState,
    headerHeight: Dp,
    featherHeight: Dp = 24.dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val totalHeight = (headerHeight + featherHeight).coerceAtLeast(1.dp)
    val fadeStart = (headerHeight.coerceAtLeast(0.dp) / totalHeight).coerceIn(0f, 1f)
    val scrim = remember(fadeStart) {
        Brush.verticalGradient(*buildList {
            add(0f to GlassPageBackground)
            for (index in 0..16) {
                val p = index / 16f
                val fade = p * p * (3f - 2f * p)
                add((fadeStart + (1f - fadeStart) * p) to GlassPageBackground.copy(alpha = 1f - fade))
            }
        }.toTypedArray())
    }
    val style = remember {
        HazeStyle(
            backgroundColor = GlassPageBackground,
            tints = listOf(HazeTint(Color.Transparent)),
            blurRadius = 16.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(Color.Transparent),
        )
    }
    val fadeStartPx = with(LocalDensity.current) { totalHeight.toPx() * fadeStart }
    val progressiveBlur = remember(fadeStartPx) {
        HazeProgressive.verticalGradient(
            easing = Easing { p -> p * p * (3f - 2f * p) },
            startY = fadeStartPx,
            startIntensity = 1f,
            endIntensity = 0f,
            preferPerformance = true,
        )
    }
    Box(
        modifier.fillMaxWidth().height(totalHeight).clipToBounds()
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(scrim)
                }
            }
            .hazeEffect(backdrop, style) {
                // Do not detach/recreate the effect while its independent scrim remains drawn.
                // The page owner disables sampling only after the page is fully covered.
                blurEnabled = enabled
                inputScale = HazeInputScale.None
                progressive = progressiveBlur
            },
    )
}
