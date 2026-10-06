package com.example.xuebimc

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Wrap the existing full-window app Box, OUTSIDE every hazeSource. Keep the mini composed until
 * playerExpansion reaches zero, and disable sharing for lyrics, queue and modal overlays.
 *
 * Both endpoints keep their existing TrackArtwork. We lift its already decoded display list;
 * there is no third Image, bitmap readback, decode, timer or second spring. Only draw reads p.
 *
 * 1.7.7's sharedElementWithCallerManagedVisibility creates its own time-driven Transition.
 * It cannot seek to the existing drag p, and its lookahead cannot predict ancestor graphicsLayer
 * translations. Use the native root overlay, but drive the bounds from the SAME p as the sheet.
 * The defaults undo Main's centered .93 base scale and full-height player translation. If Main
 * changes either transform, supply the matching functions here; never ease p a second time.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedArtworkTransition(
    playerExpansion: () -> Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundScale: (Float) -> Float = { 1f - .07f * it },
    playerTranslationY: (progress: Float, heightPx: Float) -> Float = { p, height -> (1f - p) * height },
    content: @Composable () -> Unit,
) {
    val progress = rememberUpdatedState(playerExpansion)
    val sharing = rememberUpdatedState(enabled)
    val baseScale = rememberUpdatedState(backgroundScale)
    val playerOffset = rememberUpdatedState(playerTranslationY)
    val host = remember {
        ArtworkTransfer(
            progress = { progress.value().coerceIn(0f, 1f) },
            enabled = { sharing.value },
            baseScale = { baseScale.value(it) },
            playerOffset = { p, height -> playerOffset.value(p, height) },
        )
    }
    SharedTransitionLayout(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { host.root = it }) {
            CompositionLocalProvider(LocalArtworkTransfer provides host, content = content)
            // This sibling is above player + base world and never inside their Haze sources.
            // No pointer input/semantics: the overlay cannot intercept controls or dismiss drag.
            Box(
                Modifier.matchParentSize()
                    .renderInSharedTransitionScopeOverlay(
                        renderInOverlay = { host.frame() != null },
                        zIndexInOverlay = 1f,
                    )
                    .drawWithCache {
                        val clip = Path()
                        onDrawBehind {
                            val frame = host.frame() ?: return@onDrawBehind
                            val layer = frame.destination.layer
                            val width = layer.size.width.toFloat()
                            val height = layer.size.height.toFloat()
                            if (width <= 0f || height <= 0f) return@onDrawBehind
                            clip.reset()
                            clip.addRoundRect(RoundRect(frame.bounds, CornerRadius(frame.radius)))
                            clipPath(clip) {
                                withTransform({
                                    translate(frame.bounds.left, frame.bounds.top)
                                    scale(frame.bounds.width / width, frame.bounds.height / height, Offset.Zero)
                                }) { drawLayer(layer) }
                            }
                        }
                    },
            )
        }
    }
}

/** Mini hook: .size(...).sharedMiniArtwork(track, actualRadius).clip(actualShape). */
@Composable
internal fun Modifier.sharedMiniArtwork(track: Track?, cornerRadius: Dp = 6.dp): Modifier {
    val host = LocalArtworkTransfer.current ?: return this
    val key = track?.stableKey?.takeIf { it.isNotBlank() } ?: return this
    val radius = with(LocalDensity.current) { cornerRadius.toPx() }
    val source = remember(host, key) { ArtworkSource(key, radius) }
    SideEffect { source.radius = radius }
    DisposableEffect(host, source) {
        host.source = source
        onDispose { if (host.source === source) host.source = null }
    }
    return this.onGloballyPositioned { source.coordinates = it }.drawWithCache {
        onDrawWithContent {
            // An unavailable/mismatched destination always leaves the original mini visible.
            if (host.frame() == null) drawContent()
        }
    }
}

/** Player's viewport, inside Main's translating container; not a new layout or gesture layer. */
@Composable
internal fun Modifier.sharedArtworkPlayerViewport(): Modifier {
    val host = LocalArtworkTransfer.current ?: return this
    val viewport = remember(host) { ArtworkViewport() }
    DisposableEffect(host, viewport) {
        host.viewport = viewport
        onDispose { if (host.viewport === viewport) host.viewport = null }
    }
    return onGloballyPositioned { viewport.coordinates = it }
}

/** fullBounds are the existing cover geometry in the untransformed PlayerScreen viewport. */
@Composable
internal fun Modifier.sharedPlayerArtwork(
    track: Track?,
    fullBounds: Rect,
    enabled: Boolean,
    cornerRadius: Dp = 9.dp,
): Modifier {
    val host = LocalArtworkTransfer.current ?: return this
    val key = track?.stableKey?.takeIf { it.isNotBlank() } ?: return this
    if (!enabled) return this
    val layer = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { cornerRadius.toPx() }
    val destination = remember(host, key, layer) { ArtworkDestination(key, layer, fullBounds, radius) }
    SideEffect {
        destination.bounds = fullBounds
        destination.radius = radius
    }
    DisposableEffect(host, destination) {
        host.destination = destination
        onDispose { if (host.destination === destination) host.destination = null }
    }
    // Arm on the following frame, not halfway through drawing the first frame. Otherwise the
    // mini may already have painted before the destination's display list becomes available.
    LaunchedEffect(destination, destination.recorded) {
        if (destination.recorded) {
            withFrameNanos { }
            destination.ready = true
        }
    }
    return drawWithCache {
        onDrawWithContent {
            layer.record { this@onDrawWithContent.drawContent() }
            destination.recorded = true
            if (host.frame() == null) drawLayer(layer)
        }
    }
}

private val LocalArtworkTransfer = staticCompositionLocalOf<ArtworkTransfer?> { null }

private class ArtworkSource(val key: String, radius: Float) {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    var radius by mutableStateOf(radius)
}

private class ArtworkViewport {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
}

private class ArtworkDestination(
    val key: String,
    val layer: GraphicsLayer,
    bounds: Rect,
    radius: Float,
) {
    var bounds by mutableStateOf(bounds)
    var radius by mutableStateOf(radius)
    var recorded by mutableStateOf(false)
    var ready by mutableStateOf(false)
}

private data class ArtworkFrame(val destination: ArtworkDestination, val bounds: Rect, val radius: Float)

private class ArtworkTransfer(
    val progress: () -> Float,
    val enabled: () -> Boolean,
    val baseScale: (Float) -> Float,
    val playerOffset: (Float, Float) -> Float,
) {
    var root by mutableStateOf<LayoutCoordinates?>(null)
    var source by mutableStateOf<ArtworkSource?>(null)
    var viewport by mutableStateOf<ArtworkViewport?>(null)
    var destination by mutableStateOf<ArtworkDestination?>(null)

    fun frame(): ArtworkFrame? {
        if (!enabled()) return null
        val p = progress()
        // At either endpoint restore ordinary rendering, including original clipping/semantics.
        if (!p.isFinite() || p <= 0f || p >= 1f) return null
        val small = source ?: return null
        val large = destination ?: return null
        if (small.key != large.key || !large.ready || large.layer.isReleased) return null
        val root = root?.takeIf { it.isAttached } ?: return null
        val mini = small.coordinates?.takeIf { it.isAttached } ?: return null
        val player = viewport?.coordinates?.takeIf { it.isAttached } ?: return null
        val scale = baseScale(p)
        if (!scale.isFinite() || scale <= 0f) return null
        // Ignore ancestor clipping: the mini is under the expanded sheet, but still an endpoint.
        val observedMini = root.localBoundingBoxOf(mini, clipBounds = false)
        val pivot = Offset(root.size.width / 2f, root.size.height / 2f)
        val miniBounds = Rect(
            (observedMini.topLeft - pivot) / scale + pivot,
            (observedMini.bottomRight - pivot) / scale + pivot,
        )
        val playerOrigin = root.localPositionOf(player, Offset.Zero) -
            Offset(0f, playerOffset(p, root.size.height.toFloat()))
        val fullBounds = large.bounds.translate(playerOrigin)
        if (!miniBounds.usable() || !fullBounds.usable()) return null
        return ArtworkFrame(
            large,
            lerp(miniBounds, fullBounds, p),
            (small.radius + (large.radius - small.radius) * p).coerceAtLeast(0f),
        )
    }
}

private fun Rect.usable() = left.isFinite() && top.isFinite() && right.isFinite() &&
    bottom.isFinite() && width > 0f && height > 0f
