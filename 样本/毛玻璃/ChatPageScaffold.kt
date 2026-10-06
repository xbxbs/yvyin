package com.xuebi.zhongduan.ui.agent.chat

import androidx.compose.animation.core.Easing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xuebi.zhongduan.ui.designsystem.ZdSpacing
import com.xuebi.zhongduan.ui.designsystem.ZdTheme
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

internal val LocalComposerBackdrop = staticCompositionLocalOf<HazeState?> { null }

/**
 * 聊天页的三层：正文在中间，羽化顶栏在上，局部毛玻璃输入卡片在下。
 *
 * 空间归属只有三块，互不侵占：
 * - **页眉**：实测高度，正文让开并裁切。
 * - **书写条**：实测高度（含导航栏与键盘），交给正文作底部内边距——
 *   最后一行永远能滚到输入区上沿之上，不会被压住。
 * - **回到最新**：唯一的浮动按钮，住在书写条上沿的右端，贴着玻璃的右缘。
 *   它只在离底部较远时出现；那时它下面压着的是旧内容，而不是最后一行。
 *   以前它和状态坞、错误卡上下乱插，是因为底部有好几块会长会缩的东西，
 *   现在底部只剩书写条一块，它的位置就只有一个答案。
 */
@Composable
internal fun ChatPageScaffold(
    header: @Composable () -> Unit,
    conversation: @Composable (Modifier, Dp, Dp) -> Unit,
    composer: @Composable (Modifier) -> Unit,
    browseAction: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * header 是否盖在画布上。
     * false 时画布让出页眉高度并裁切；true 时画布铺满，首条仍用页眉高度做内边距。
     */
    immersiveHeader: Boolean = false
) {
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val headerInset = with(density) { headerHeightPx.toDp() }
    val composerInset = with(density) { composerHeightPx.toDp() }
    val composerBackdrop = remember { HazeState() }
    val headerFeatherHeight = ZdSpacing.xxl

    Box(modifier = modifier.fillMaxSize()) {
        conversation(
            Modifier
                .fillMaxSize()
                .hazeSource(composerBackdrop)
                .then(if (immersiveHeader) Modifier else Modifier.padding(top = headerInset).clipToBounds()),
            composerInset,
            if (immersiveHeader) headerInset + headerFeatherHeight else 0.dp
        )

        // 背景模糊层与标题分开绘制，标题和按钮本身不参与模糊。
        if (immersiveHeader) {
            HeaderGlass(
                backdrop = composerBackdrop,
                headerHeight = headerInset,
                featherHeight = headerFeatherHeight,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .onSizeChanged { headerHeightPx = it.height }
        ) {
            header()
        }

        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .onSizeChanged { composerHeightPx = it.height }
                .navigationBarsPadding()
                .imePadding()
        ) {
            // 底部只允许一块 chrome：状态行、排队、附件、输入、工具都在这一棵树里。
            CompositionLocalProvider(LocalComposerBackdrop provides composerBackdrop) {
                composer(Modifier.fillMaxWidth())
            }
        }

        // 回到最新：输入区上沿正中（照 Claude）。放右端时正好压在正文行尾，
        // 长段原文、代码被它挡掉最后几个字；正中落在段落之间的留白里，挡得最少。
        // 底缘离输入区上沿一小步，读作「挂在输入区上」，而不是飘在正文中间。
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = composerInset + ZdSpacing.sm)
        ) {
            browseAction()
        }
    }
}

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun HeaderGlass(
    backdrop: HazeState,
    headerHeight: Dp,
    featherHeight: Dp,
    modifier: Modifier = Modifier
) {
    val style = chatHeaderGlassStyle()
    val totalHeight = headerHeight + featherHeight
    val fadeStart = chatHeaderFadeStart(headerHeight, featherHeight)
    val background = ZdTheme.colors.bg.copy(alpha = 1f)
    val scrim = remember(background, fadeStart) {
        val stops = buildList {
            add(0f to background)
            for (index in 0..16) {
                val progress = index / 16f
                val fade = progress * progress * (3f - 2f * progress)
                add((fadeStart + (1f - fadeStart) * progress) to background.copy(alpha = 1f - fade))
            }
        }
        Brush.verticalGradient(*stops.toTypedArray())
    }
    val density = LocalDensity.current
    val fadeStartPx = with(density) { totalHeight.toPx() * fadeStart }
    // 渐进模糊改变采样半径，而非淡出固定模糊截图后混回清晰原文。
    val headerProgressive = remember(fadeStartPx) {
        HazeProgressive.verticalGradient(
            easing = Easing { progress -> progress * progress * (3f - 2f * progress) },
            startY = fadeStartPx,
            startIntensity = 1f,
            endIntensity = 0f,
            preferPerformance = true
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(totalHeight)
            .clipToBounds()
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    // 在模糊结果之上独立覆盖，不依赖 Haze 的 tint/强度调制。
                    drawRect(brush = scrim)
                }
            }
            .hazeEffect(backdrop, style) {
                // 显式像素起点与着色器采样尺寸保持一致，避免自动缩采样改变羽化位置。
                inputScale = HazeInputScale.None
                progressive = headerProgressive
            }
    )
}

internal fun chatHeaderFadeStart(headerHeight: Dp, featherHeight: Dp): Float =
    (headerHeight.coerceAtLeast(0.dp) /
        (headerHeight + featherHeight).coerceAtLeast(1.dp)).coerceIn(0f, 1f)
