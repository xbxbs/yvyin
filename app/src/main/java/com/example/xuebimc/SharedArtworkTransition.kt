package com.example.xuebimc

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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * Wrap the existing full-window app Box, OUTSIDE every hazeSource. Keep the mini composed until
 * playerExpansion reaches zero, and disable sharing for lyrics, queue and modal overlays.
 *
 * Both endpoints keep their existing TrackArtwork. We lift their already decoded display lists;
 * there is no third Image, bitmap readback, decode, timer or second spring. Only draw reads p.
 *
 * A root sibling overlay follows the SAME p as the sheet, including reversed/cancelled drags.
 * Decide ownership before drawing either endpoint. The mini is an immediate first-frame image;
 * upgrading to the player's recorded layer never changes the chosen geometry or restarts p.
 * The defaults undo Main's centered .93 base scale and full-height player translation. If Main
 * changes either transform, supply the matching functions here; never ease p a second time.
 */
@Composable
internal fun SharedArtworkTransition(
    playerExpansion: () -> Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundScale: (Float) -> Float = { 1f - .07f * it },
    playerTranslationY: (progress: Float, heightPx: Float) -> Float = { p, height -> (1f - p) * height },
    playerAtRootOrigin: Boolean = false,
    onReadyForOpen: (String?) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val progress = rememberUpdatedState(playerExpansion)
    val sharing = rememberUpdatedState(enabled)
    val baseScale = rememberUpdatedState(backgroundScale)
    val playerOffset = rememberUpdatedState(playerTranslationY)
    val rootOrigin = rememberUpdatedState(playerAtRootOrigin)
    val reportReady = rememberUpdatedState(onReadyForOpen)
    val shadowLayer = rememberGraphicsLayer()
    val host = remember {
        ArtworkTransfer(
            progress = { progress.value().coerceIn(0f, 1f) },
            enabled = { sharing.value },
            baseScale = { baseScale.value(it) },
            playerOffset = { p, height -> playerOffset.value(p, height) },
            playerAtRootOrigin = { rootOrigin.value },
        )
    }
    LaunchedEffect(host) {
        // A registration fence, not a timer: Main may hold its existing p at zero until this key
        // matches. Destination first draw is NOT required if the mini display list is available.
        snapshotFlow { host.preparedKey() }.distinctUntilChanged().collect { reportReady.value(it) }
    }
        Box(modifier.fillMaxSize().onGloballyPositioned { host.root = it }.drawWithContent {
            host.beginFrame()
            drawContent()
        }) {
            CompositionLocalProvider(LocalArtworkTransfer provides host, content = content)
            // This sibling is above player + base world and never inside their Haze sources.
            // No pointer input/semantics: the overlay cannot intercept controls or dismiss drag.
            Box(
                Modifier.matchParentSize()
                    .drawWithCache {
                        val clip = Path()
                        onDrawBehind {
                            val frame = host.frame() ?: return@onDrawBehind
                            val layer = frame.layer
                            val width = layer.size.width.toFloat()
                            val height = layer.size.height.toFloat()
                            if (width <= 0f || height <= 0f) return@onDrawBehind
                            // The lifted cover owns its shadow as well. Leaving the player's
                            // shadow behind would reveal a second, sliding cover during flight.
                            if (frame.shadowElevation > 0f) {
                                shadowLayer.record(
                                    size = IntSize(frame.bounds.width.roundToInt().coerceAtLeast(1),
                                        frame.bounds.height.roundToInt().coerceAtLeast(1)),
                                ) {
                                    drawRoundRect(Color.Black, cornerRadius = CornerRadius(frame.radius))
                                }
                                shadowLayer.setRoundRectOutline(cornerRadius = frame.radius)
                                shadowLayer.shadowElevation = frame.shadowElevation
                                shadowLayer.ambientShadowColor = Color.Black.copy(alpha = .24f)
                                shadowLayer.spotShadowColor = Color.Black.copy(alpha = .36f)
                                withTransform({ translate(frame.bounds.left, frame.bounds.top) }) {
                                    drawLayer(shadowLayer)
                                }
                            }
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

/** Mini hook: .size(...).sharedMiniArtwork(track, actualRadius).clip(actualShape). */
@Composable
internal fun Modifier.sharedMiniArtwork(track: Track?, cornerRadius: Dp = 6.dp): Modifier {
    val host = LocalArtworkTransfer.current ?: return this
    val key = track?.stableKey?.takeIf { it.isNotBlank() } ?: return this
    val layer = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { cornerRadius.toPx() }
    val source = remember(host, key, layer) { ArtworkSource(key, layer, radius) }
    SideEffect { source.radius = radius }
    DisposableEffect(host, source) {
        host.source = source
        onDispose { if (host.source === source) host.source = null }
    }
    return this.onGloballyPositioned { source.coordinates = it }.drawWithCache {
        onDrawWithContent {
            layer.record { this@onDrawWithContent.drawContent() }
            source.recorded = true
            // An unavailable/mismatched destination always leaves the original mini visible.
            if (host.frame() == null) drawLayer(layer)
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

/** Live geometry in the untransformed viewport; reads run in drawing, not recomposition. */
@Composable
internal fun Modifier.sharedPlayerArtwork(
    track: Track?,
    fullBounds: () -> Rect,
    enabled: Boolean,
    cornerRadius: Dp = 9.dp,
    shadowElevationPx: () -> Float = { 0f },
): Modifier {
    val host = LocalArtworkTransfer.current ?: return this
    val key = track?.stableKey?.takeIf { it.isNotBlank() } ?: return this
    if (!enabled) return this
    val layer = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { cornerRadius.toPx() }
    val destination = remember(host, key, layer) { ArtworkDestination(key, layer, fullBounds, radius, shadowElevationPx) }
    SideEffect {
        destination.bounds = fullBounds
        destination.radius = radius
        destination.shadowElevation = shadowElevationPx
    }
    DisposableEffect(host, destination) {
        host.destination = destination
        onDispose { if (host.destination === destination) host.destination = null }
    }
    return drawWithCache {
        onDrawWithContent {
            layer.record { this@onDrawWithContent.drawContent() }
            destination.recorded = true
            if (host.frame() == null) drawLayer(layer)
        }
    }
}

/** Draw-time ownership also removes the full-size endpoint shadow while the overlay flies. */
@Composable
internal fun sharedArtworkInFlight(): () -> Boolean {
    val host = LocalArtworkTransfer.current
    return remember(host) { { host?.inFlight() == true } }
}

private val LocalArtworkTransfer = staticCompositionLocalOf<ArtworkTransfer?> { null }

private class ArtworkSource(val key: String, val layer: GraphicsLayer, radius: Float) {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    var radius by mutableStateOf(radius)
    var recorded by mutableStateOf(false)
}

private class ArtworkViewport {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
}

private class ArtworkDestination(
    val key: String,
    val layer: GraphicsLayer,
    bounds: () -> Rect,
    radius: Float,
    shadowElevation: () -> Float,
) {
    var bounds by mutableStateOf(bounds)
    var radius by mutableStateOf(radius)
    var shadowElevation by mutableStateOf(shadowElevation)
    var recorded by mutableStateOf(false)
}

private data class ArtworkFrame(
    val key: String,
    val layer: GraphicsLayer,
    val bounds: Rect,
    val radius: Float,
    val shadowElevation: Float,
)

private class ArtworkTransfer(
    val progress: () -> Float,
    val enabled: () -> Boolean,
    val baseScale: (Float) -> Float,
    val playerOffset: (Float, Float) -> Float,
    val playerAtRootOrigin: () -> Boolean,
) {
    var root by mutableStateOf<LayoutCoordinates?>(null)
    var source by mutableStateOf<ArtworkSource?>(null)
    var viewport by mutableStateOf<ArtworkViewport?>(null)
    var destination by mutableStateOf<ArtworkDestination?>(null)
    private var decided = false
    private var transferKey: String? = null
    private var transferAllowed = false
    private var drawingFrame: ArtworkFrame? = null

    fun preparedKey(): String? {
        if (!enabled()) return null
        val small = source ?: return null
        val large = destination ?: return null
        if (small.key != large.key || root?.isAttached != true || small.coordinates?.isAttached != true ||
            viewport?.coordinates?.isAttached != true || !large.bounds().usable()) return null
        if (!small.layer.usable(small.recorded) && !large.layer.usable(large.recorded)) return null
        return small.key
    }

    fun inFlight(): Boolean {
        val p = progress()
        return p > 0f && p < 1f && preparedKey() != null
    }

    /** One decision for mini, player and overlay, made before any of them paints. */
    fun beginFrame() {
        val p = progress()
        if (!p.isFinite() || p <= 0f || p >= 1f) {
            decided = false
            transferAllowed = false
            transferKey = null
            drawingFrame = null
            return
        }
        val candidate = calculateFrame(p)
        if (!decided) {
            decided = true
            transferAllowed = candidate != null
            transferKey = candidate?.key
        } else if (candidate == null || candidate.key != transferKey) {
            transferAllowed = false
        }
        // If the regular cover already started this motion, never teleport it back to a
        // mini-origin trajectory later. Main's readiness fence avoids that fallback on opening.
        drawingFrame = candidate.takeIf { transferAllowed }
    }

    fun frame(): ArtworkFrame? {
        // Read the same observable p in endpoint draw nodes too, so cached parent layers redraw
        // at ownership changes. Recording a new destination cannot change this frame's owner.
        progress()
        enabled()
        return drawingFrame
    }

    private fun calculateFrame(p: Float): ArtworkFrame? {
        if (!enabled()) return null
        val small = source ?: return null
        val large = destination ?: return null
        if (small.key != large.key) return null
        val layer = when {
            large.layer.usable(large.recorded) -> large.layer
            small.layer.usable(small.recorded) -> small.layer
            else -> return null
        }
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
        val playerOrigin = if (playerAtRootOrigin()) Offset.Zero else
            root.localPositionOf(player, Offset.Zero) - Offset(0f, playerOffset(p, root.size.height.toFloat()))
        val fullBounds = large.bounds().translate(playerOrigin)
        if (!miniBounds.usable() || !fullBounds.usable()) return null
        return ArtworkFrame(
            small.key, layer,
            lerp(miniBounds, fullBounds, p),
            (small.radius + (large.radius - small.radius) * p).coerceAtLeast(0f),
            large.shadowElevation().coerceAtLeast(0f) * p,
        )
    }
}

private fun GraphicsLayer.usable(recorded: Boolean) = recorded && !isReleased && size.width > 0 && size.height > 0

private fun Rect.usable() = left.isFinite() && top.isFinite() && right.isFinite() &&
    bottom.isFinite() && width > 0f && height > 0f
