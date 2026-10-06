package com.example.xuebimc

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/** Direct Kyant0 Backdrop pipeline: blur + vibrancy + lens refraction + contour highlight. */
internal fun Modifier.kyantLiquidGlass(backdrop: Backdrop, shape: Shape): Modifier =
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(8.dp.toPx())
            lens(24.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = true)
        },
        highlight = { Highlight.Default.copy(alpha = .82f) },
        shadow = { Shadow.Default.copy(color = Color.Black.copy(alpha = .18f), radius = 18.dp) },
        onDrawSurface = { drawRect(Color.Black.copy(alpha = .28f)) },
    )
