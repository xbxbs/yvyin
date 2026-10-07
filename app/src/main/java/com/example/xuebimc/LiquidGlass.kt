package com.example.xuebimc

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

private val NavigationGlassTint = Color(0xFF252527).copy(alpha = .46f)
private val NavigationGlassEdge = Highlight(
    width = .75.dp,
    alpha = .7f,
    style = HighlightStyle.Default(intensity = .34f, angle = 45f, falloff = 1.3f),
)

/**
 * KernelSU's floating bar material, expressed with the existing Kyant Backdrop API:
 * a lightly blurred, neutral lens, not a saturated chromatic lens over album artwork.
 * [exportedBackdrop] records this surface without its foreground labels for the selected pill.
 */
internal fun Modifier.kyantLiquidGlass(
    backdrop: Backdrop,
    shape: Shape,
    exportedBackdrop: LayerBackdrop? = null,
    pressProgress: () -> Float = { 0f },
    highlightPosition: () -> Float = { .5f },
): Modifier =
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(4.dp.toPx())
            lens(24.dp.toPx(), 24.dp.toPx())
        },
        highlight = { NavigationGlassEdge },
        shadow = {
            Shadow.Default.copy(
                color = Color.Black.copy(alpha = .2f),
                radius = 10.dp,
                offset = DpOffset(0.dp, 3.dp),
            )
        },
        exportedBackdrop = exportedBackdrop,
        onDrawSurface = {
            drawRect(NavigationGlassTint)
            val press = pressProgress().coerceIn(0f, 1f)
            if (press > 0f) {
                // Light appears under the finger only; there is no permanent coloured glow.
                drawRect(Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = .1f * press), Color.Transparent),
                    center = Offset(size.width * highlightPosition(), size.height / 2f),
                    radius = size.height * 1.2f,
                ))
            }
        },
    )

/** Moving foreground lens; its input includes the accent-colour label layer. */
internal fun Modifier.liquidNavigationPill(
    backdrop: Backdrop,
    shape: Shape,
    pressProgress: () -> Float,
    velocity: () -> Float,
    motionAllowed: Boolean,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        val press = if (motionAllowed) pressProgress().coerceIn(0f, 1f) else 0f
        lens(10.dp.toPx() * press, 14.dp.toPx() * press, depthEffect = true)
    },
    highlight = {
        NavigationGlassEdge.copy(alpha = .32f + .48f * pressProgress().coerceIn(0f, 1f))
    },
    shadow = null,
    innerShadow = {
        val press = if (motionAllowed) pressProgress().coerceIn(0f, 1f) else 0f
        InnerShadow(
            radius = 8.dp * press,
            offset = DpOffset(0.dp, 2.dp * press),
            color = Color.Black.copy(alpha = .15f),
            alpha = press,
        )
    },
    layerBlock = {
        val press = if (motionAllowed) pressProgress().coerceIn(0f, 1f) else 0f
        val stretch = if (motionAllowed) (kotlin.math.abs(velocity()) * .006f).coerceAtMost(.055f) else 0f
        scaleX = 1f + .16f * press + stretch
        scaleY = 1f + .12f * press - stretch * .5f
    },
    onDrawSurface = {
        val press = pressProgress().coerceIn(0f, 1f)
        drawRect(Color.White.copy(alpha = .105f * (1f - press)))
        drawRect(Color.White.copy(alpha = .035f * press))
        },
)
