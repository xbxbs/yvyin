package com.example.xuebimc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The mini-player is the source container, not a detached cover above a bottom-sheet entrance. */
@Composable
internal fun PlayerSurfaceTransition(progress: () -> Float, trackKey: String?, content: @Composable () -> Unit) {
    val latestProgress = rememberUpdatedState(progress)
    val latestKey = rememberUpdatedState(trackKey)
    val host = remember { PlayerSurfaceHost({ latestProgress.value() }, { latestKey.value }) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { host.root = it }) {
        CompositionLocalProvider(LocalPlayerSurface provides host, content = content)
    }
}

private class MiniPlayerSurface(val key: String, val layer: GraphicsLayer) {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    var recorded = false
}

private class PlayerSurfaceHost(val progress: () -> Float, val trackKey: () -> String?) {
    var root by mutableStateOf<LayoutCoordinates?>(null)
    var mini by mutableStateOf<MiniPlayerSurface?>(null)

    fun sourceBounds(): Rect? {
        val source = mini?.takeIf { it.key == trackKey() && it.recorded && !it.layer.isReleased } ?: return null
        val rootCoordinates = root?.takeIf { it.isAttached } ?: return null
        val coordinates = source.coordinates?.takeIf { it.isAttached } ?: return null
        val observed = rootCoordinates.localBoundingBoxOf(coordinates, clipBounds = false)
        // The library recedes around the window center. Unapply that transform once so the
        // source remains the same physical mini card throughout opening, reverse and release.
        val p = progress().coerceIn(0f, 1f)
        val scale = 1f - .07f * p
        val pivot = Offset(rootCoordinates.size.width / 2f, rootCoordinates.size.height / 2f)
        val bounds = Rect((observed.topLeft - pivot) / scale + pivot, (observed.bottomRight - pivot) / scale + pivot)
        return bounds.takeIf { it.width > 0f && it.height > 0f && it.left.isFinite() && it.top.isFinite() }
    }
}

private val LocalPlayerSurface = staticCompositionLocalOf<PlayerSurfaceHost?> { null }

/** Register the WHOLE mini player (material, title, controls), without changing its resting layout. */
@Composable
internal fun Modifier.sharedMiniPlayerSurface(key: String): Modifier {
    val host = LocalPlayerSurface.current ?: return this
    val layer = rememberGraphicsLayer()
    val source = remember(host, key, layer) { MiniPlayerSurface(key, layer) }
    DisposableEffect(host, source) {
        host.mini = source
        onDispose { if (host.mini === source) host.mini = null }
    }
    return onGloballyPositioned { source.coordinates = it }.drawWithContent {
        layer.alpha = 1f
        layer.record { this@drawWithContent.drawContent() }
        source.recorded = true
        val p = host.progress()
        if (p <= 0f || p >= 1f || host.sourceBounds() == null) drawLayer(layer)
    }
}

private data class PlayerContainerClip(val visibleHeight: Float, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(0f, 0f, size.width, visibleHeight.coerceIn(0f, size.height), CornerRadius(radius)))
}

/**
 * Only this outer presentation layer changes. At p=1 it is the identity transform and unclipped;
 * PlayerScreen's positions, type, artwork, background and lyric calculations remain untouched.
 * graphicsLayer (not a drawing-only translation) keeps hit testing aligned during interruption.
 */
@Composable
internal fun Modifier.sharedPlayerSurface(): Modifier {
    val host = LocalPlayerSurface.current ?: return this
    val miniRadius = with(LocalDensity.current) { 20.dp.toPx() }
    val handoffLayer = rememberGraphicsLayer()
    return graphicsLayer {
        val p = host.progress().coerceIn(0f, 1f)
        val source = host.sourceBounds()
        transformOrigin = TransformOrigin(0f, 0f)
        if (source != null && size.width > 0f) {
            val bounds = lerp(source, Rect(Offset.Zero, size), p)
            val scale = (bounds.width / size.width).coerceAtLeast(.01f)
            translationX = bounds.left
            translationY = bounds.top
            scaleX = scale
            scaleY = scale
            shape = PlayerContainerClip(bounds.height / scale, miniRadius * (1f - p) / scale)
            clip = p < 1f
            alpha = if (p <= 0f) 0f else 1f
        } else {
            // First-ever playback may have no measured mini card yet. Fade in place rather
            // than inventing an unrelated sheet rising from outside the window.
            translationX = 0f; translationY = 0f
            scaleX = 1f; scaleY = 1f
            clip = false
            alpha = p
        }
    }.drawWithContent {
        drawContent()
        val p = host.progress().coerceIn(0f, 1f)
        val source = host.mini
        val bounds = host.sourceBounds()
        // Retain the actual mini title/buttons on the growing surface for the handoff. The
        // cover is independently owned by the existing shared-artwork layer, never redrawn.
        if (p > 0f && p < .28f && source != null && bounds != null && source.layer.size.width > 0) {
            val t = (p / .28f).coerceIn(0f, 1f)
            val scale = size.width / source.layer.size.width.toFloat()
            handoffLayer.record(size = source.layer.size) { drawLayer(source.layer) }
            handoffLayer.alpha = 1f - t * t * (3f - 2f * t)
            withTransform({ scale(scale, scale, Offset.Zero) }) { drawLayer(handoffLayer) }
        }
    }
}

/** Drag normalization uses the card's actual travel so the header follows the finger. */
@Composable
internal fun playerSurfaceTravelDistance(fallback: () -> Float): () -> Float {
    val host = LocalPlayerSurface.current
    val latestFallback = rememberUpdatedState(fallback)
    return remember(host) { { host?.sourceBounds()?.top?.takeIf { it > 1f } ?: latestFallback.value().coerceAtLeast(1f) } }
}
