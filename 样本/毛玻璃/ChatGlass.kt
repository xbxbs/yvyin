package com.xuebi.zhongduan.ui.agent.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.xuebi.zhongduan.ui.designsystem.ZdSize
import com.xuebi.zhongduan.ui.designsystem.ZdTheme
import com.xuebi.zhongduan.ui.theme.Tone
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** 输入卡和卡外状态药丸共用材质参数。 */
@Composable
internal fun chatGlassStyle(): HazeStyle {
    val colors = ZdTheme.colors
    return remember(colors.dark) {
        HazeStyle(
            backgroundColor = colors.bg,
            tints = listOf(HazeTint((if (colors.dark) Color.White else Color.Black).copy(alpha = Tone.Chrome.COMPOSER_TINT))),
            blurRadius = ZdSize.composerBlurRadius,
            noiseFactor = 0f,
            fallbackTint = HazeTint(colors.bg.copy(alpha = Tone.Chrome.COMPOSER_FALLBACK))
        )
    }
}

/** 顶部只负责渐进模糊，页面底色遮罩在外层独立绘制，确保标题区域完整覆盖。 */
@Composable
internal fun chatHeaderGlassStyle(): HazeStyle {
    val colors = ZdTheme.colors
    return remember(colors.dark) {
        HazeStyle(
            backgroundColor = colors.bg,
            tints = listOf(HazeTint(Color.Transparent)),
            blurRadius = ZdSize.chatHeaderBlurRadius,
            noiseFactor = 0f,
            fallbackTint = HazeTint(Color.Transparent)
        )
    }
}

/** 描边在最外面的绘制节点，最后画在模糊与内容上方。 */
@Composable
internal fun Modifier.chatGlassSurface(shape: Shape): Modifier {
    val colors = ZdTheme.colors
    val backdrop = LocalComposerBackdrop.current
    val style = chatGlassStyle()
    val rim = remember(colors.dark) {
        Brush.verticalGradient(listOf(colors.glassRimHighlight, colors.glassRimShade))
    }
    return clip(shape)
        .border(ZdSize.composerRimWidth, rim, shape)
        .then(
            if (backdrop != null) Modifier.hazeEffect(backdrop, style)
            else Modifier.background(colors.bg.copy(alpha = Tone.Chrome.COMPOSER_FALLBACK))
        )
}

/** 卡内按钮只画淡底与细线，不再增加独立模糊采样。 */
@Composable
internal fun Modifier.composerControlSurface(shape: Shape): Modifier {
    val colors = ZdTheme.colors
    return clip(shape)
        .border(ZdSize.composerRimWidth, colors.composerControlBorder, shape)
        .background(colors.composerControlFill)
}
