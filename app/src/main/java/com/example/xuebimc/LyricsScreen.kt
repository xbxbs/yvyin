package com.example.xuebimc

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class LyricGlyph(
    val bounds: Rect,
    val advance: Float,
    val layout: TextLayoutResult,
    val origin: Offset,
)

private class LyricWordPaint(
    val startMs: Long,
    val endMs: Long,
    val glyphs: List<LyricGlyph>,
    val width: Float,
)

private class LyricGlyphTexture(val ink: GraphicsLayer, val paint: GraphicsLayer) {
    var recorded = false
    var sweepEdge: Float? = null
    var focus = Float.NaN
}

private class LyricMeasure(val layout: TextLayoutResult, val words: List<LyricWordPaint>) {
    /** Romanization + translation block under the line; part of the row so it moves with it. */
    var sub: TextLayoutResult? = null
    var subGap: Float = 0f
    val height: Float get() = layout.size.height + (sub?.let { subGap + it.size.height } ?: 0f)
}

private class LyricMotion {
    val position = AmllSpring(0f)
    val size = AmllSpring(100f, LyricMotionPolicy.scaleSpring())
    var y by mutableFloatStateOf(0f)
    var scale by mutableFloatStateOf(1f)
    var initialized = false
    var wasManual = false
    var seekRevision = 0
}

private val wordFloatEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val wordLiftEasing = CubicBezierEasing(0.22f, 0f, 0.36f, 1f)
private val filterEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

// Mask the completed image (including blur), not the opacity of an entire row.
// The offscreen layer is intentionally the ONLY viewport-sized clipping layer.
private fun Modifier.lyricEdgeMask(): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithCache {
    val topFade = minOf(64.dp.toPx(), size.height * 0.22f)
    val bottomFade = minOf(96.dp.toPx(), size.height * 0.28f)
    val stops = (0..16).map { it / 16f * topFade } +
        (0..16).map { size.height - bottomFade + it / 16f * bottomFade }
    val mask = Brush.verticalGradient(
        *stops.map { y ->
            (y / size.height.coerceAtLeast(1f)) to Color.White.copy(
                alpha = LyricMotionPolicy.edgeOpacity(y, size.height, topFade, bottomFade),
            )
        }.toTypedArray(),
        endY = size.height.coerceAtLeast(1f),
    )
    onDrawWithContent {
        drawContent()
        drawRect(mask, blendMode = BlendMode.DstIn)
    }
}

@Composable
fun LyricsViewport(
    lines: List<LyricLine>,
    positionMs: () -> Long,
    isPlaying: Boolean,
    seekRevision: Int = 0,
    modifier: Modifier = Modifier,
    entranceProgress: Float = 1f,
    onSeek: (Long) -> Unit,
    onInteraction: () -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val scope = rememberCoroutineScope()
    val seekAction by rememberUpdatedState(onSeek)
    val interact by rememberUpdatedState(onInteraction)
    // Read time in the draw phase: a playback tick must not recompose 17 rows.
    val readPosition by rememberUpdatedState(positionMs)
    val playbackPosition = remember { derivedStateOf { readPosition() } }
    val blurEffects = remember(density.density) {
        if (Build.VERSION.SDK_INT >= 31) {
            List<RenderEffect?>(81) { step ->
                if (step == 0) null else android.graphics.RenderEffect.createBlurEffect(
                    step * 0.1f * density.density,
                    step * 0.1f * density.density,
                    android.graphics.Shader.TileMode.DECAL,
                ).asComposeRenderEffect()
            }
        } else listOf<RenderEffect?>(null)
    }

    BoxWithConstraints(modifier.lyricEdgeMask()) {
        val inset = maxWidth * 0.088f
        val stageWidth = maxWidth - inset * 2f
        val textWidth = with(density) { stageWidth.roundToPx().coerceAtLeast(1) }
        // RenderEffect crops to its layer. Reserve transparent pixels for its
        // blur kernel, ascenders, and animated glyphs on ALL four sides.
        val paintPadding = 32.dp
        val anchorY = with(density) { (maxWidth * 0.20f).coerceIn(64.dp, 88.dp).toPx() }
        val fontSize = maxWidth.value * 0.08f / density.fontScale
        val rowGap = with(density) { (maxWidth * 0.064f).toPx() }
        val introSpace = with(density) { 40.dp.toPx() }
        val style = remember(fontSize) {
            TextStyle(
                color = Color.White,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.2f).sp,
                letterSpacing = 0.sp,
                // Apple Music lyrics are heavy. The bundled CJK fonts are demo subsets with a single
                // weight, so use the system face which ships real Bold for every CJK glyph.
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Bold,
                fontSynthesis = FontSynthesis.None,
                lineBreak = LineBreak.Heading,
                // Keep horizontal advances unhinted. Vertical lift is a texture transform below;
                // TextMotion alone does not guarantee subpixel vertical text rasterization.
                textMotion = TextMotion.Animated,
            )
        }
        val subStyle = remember(fontSize) {
            TextStyle(
                color = Color.White,
                fontSize = (fontSize * 0.56f).sp,
                lineHeight = (fontSize * 0.56f * 1.3f).sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSynthesis = FontSynthesis.None,
                textMotion = TextMotion.Animated,
            )
        }
        val measured = remember(lines, textWidth, density.density, density.fontScale, style, subStyle, textMeasurer) {
            val glyphLayouts = mutableMapOf<String, TextLayoutResult>()
            lines.map { line ->
                // Duet partner lines sit on the right, like Apple Music / AMLL.
                val lineStyle = if (line.alignEnd) style.copy(textAlign = TextAlign.End) else style
                // End alignment and the row's scale pivot must share the same stage width.
                // A wrap-content paragraph otherwise drifts inward when the right singer dims.
                val rowConstraints = Constraints(minWidth = textWidth, maxWidth = textWidth)
                val original = textMeasurer.measure(line.text, lineStyle, constraints = rowConstraints)
                val breakAt = if (original.lineCount > 1) {
                    line.text.indices.filter { line.text[it].isWhitespace() }.mapNotNull { index ->
                        val before = textMeasurer.measure(line.text.substring(0, index), style).size.width
                        val after = textMeasurer.measure(line.text.substring(index + 1), style).size.width
                        if (before <= textWidth && after <= textWidth) index to abs(before - after) else null
                    }.minByOrNull { it.second }?.first
                } else null
                val layout = if (breakAt == null) original else textMeasurer.measure(
                    line.text.replaceRange(breakAt, breakAt + 1, "\n"),
                    lineStyle,
                    constraints = rowConstraints,
                )
                measureLyricWords(line, layout, textMeasurer, style, glyphLayouts).also { measure ->
                    val subText = listOfNotNull(line.background, line.romanization, line.translation)
                        .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                    if (subText.isNotEmpty()) {
                        measure.sub = textMeasurer.measure(
                            subText,
                            if (line.alignEnd) subStyle.copy(textAlign = TextAlign.End) else subStyle,
                            constraints = rowConstraints,
                        )
                        measure.subGap = fontSize * density.density * 0.28f
                    }
                }
            }
        }
        val offsets = remember(measured, rowGap) {
            FloatArray(measured.size + 1).also { values ->
                measured.forEachIndexed { index, measure ->
                    values[index + 1] = values[index] + measure.height + rowGap
                }
            }
        }
        val activeIndex by remember(lines, playbackPosition) {
            derivedStateOf { lyricIndexAt(lines, playbackPosition.value.coerceAtLeast(0L)) }
        }
        // Index of the line an instrumental gap leads into (0 = prelude), or -1 while singing.
        val interludeNext by remember(lines, playbackPosition) {
            derivedStateOf { interludeTarget(lines, playbackPosition.value) }
        }
        val intro = interludeNext >= 0
        val followIndex = if (intro) interludeNext else activeIndex
        var manual by remember(lines) { mutableStateOf(false) }
        var touching by remember(lines) { mutableStateOf(false) }
        var gestureRevision by remember(lines) { mutableIntStateOf(0) }
        var pinnedIndex by remember(lines) { mutableIntStateOf(followIndex) }
        var pinnedIntro by remember(lines) { mutableStateOf(intro) }
        val trackingIndex = if (manual) pinnedIndex else followIndex
        val trackingIntro = if (manual) pinnedIntro else intro
        val springParameters = remember(lines, trackingIndex, trackingIntro) {
            LyricMotionPolicy.followSpring(
                intervalMillis = if (trackingIndex > 0) {
                    (lines[trackingIndex].startMs - lines[trackingIndex - 1].startMs).toDouble()
                } else null,
                isInterludeActive = trackingIntro,
            )
        }
        val stagger = remember(offsets, measured, anchorY, trackingIndex, trackingIntro) {
            LyricMotionPolicy.staggerDelays(
                measured.indices.map { index ->
                    (anchorY + offsets[index] - offsets.getOrElse(trackingIndex) { 0f } +
                        measured[index].height + if (trackingIntro && index >= trackingIndex) introSpace else 0f).toDouble()
                },
                trackingIndex,
            ).lineDelayMillis
        }
        val motions = remember(lines) {
            List(lines.size) { index ->
                LyricMotion().also { motion ->
                    val initialY = anchorY + offsets[index] - offsets.getOrElse(trackingIndex) { 0f } +
                        if (trackingIntro && index >= trackingIndex) introSpace else 0f
                    motion.position.snapTo(initialY)
                    motion.y = initialY
                    motion.initialized = true
                    motion.seekRevision = seekRevision
                }
            }
        }
        val scrollState = rememberScrollableState { delta ->
            if (motions.isEmpty()) return@rememberScrollableState 0f
            if (!manual) {
                pinnedIndex = followIndex
                pinnedIntro = intro
                manual = true
                // Take over the exact rendered positions, including a partially
                // completed wave. No second scroll-offset animation is added.
                motions.forEach { motion ->
                    motion.position.snapTo(motion.y)
                    motion.wasManual = true
                }
            }
            val firstY = motions.first().y
            val lastOffset = offsets.getOrElse(lines.lastIndex) { 0f }
            val upper = anchorY + if (pinnedIntro) introSpace else 0f
            val lower = anchorY - lastOffset
            val consumed = if (delta >= 0f) delta.coerceAtMost((upper - firstY).coerceAtLeast(0f))
                else delta.coerceAtLeast((lower - firstY).coerceAtMost(0f))
            motions.forEach { motion ->
                motion.position.snapTo(motion.y + consumed)
                motion.y = motion.position.position.toFloat()
            }
            consumed
        }
        LaunchedEffect(seekRevision) {
            scrollState.stopScroll(MutatePriority.PreventUserInput)
            manual = false
        }
        // iOS semantics: a tap that lands on a moving list only stops it; it never seeks.
        var tapBlocked by remember(lines) { mutableStateOf(false) }
        var scrollEndedAt by remember(lines) { mutableLongStateOf(0L) }
        LaunchedEffect(scrollState) {
            snapshotFlow { scrollState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling) scrollEndedAt = SystemClock.uptimeMillis()
            }
        }
        LaunchedEffect(motions, activeIndex, intro, isPlaying) {
            motions.forEachIndexed { index, motion ->
                motion.size.setTargetPosition(
                    if ((index == activeIndex && !intro) || !isPlaying) 100f else 95f,
                )
            }
        }
        LaunchedEffect(motions, offsets, anchorY, trackingIndex, trackingIntro, manual, seekRevision) {
            motions.forEachIndexed { index, motion ->
                val target = anchorY + offsets[index] - offsets.getOrElse(trackingIndex) { 0f } +
                    if (trackingIntro && index >= trackingIndex) introSpace else 0f
                when {
                    !motion.initialized -> {
                        motion.position.snapTo(target)
                        motion.initialized = true
                    }
                    manual -> motion.wasManual = true
                    else -> {
                        val seeking = motion.seekRevision != seekRevision
                        val returning = motion.wasManual || seeking
                        motion.wasManual = false
                        motion.position.updateParameters(
                            if (returning) LyricMotionPolicy.returnSpring() else springParameters,
                        )
                        motion.position.setTargetPosition(target, if (returning) 0.0 else stagger[index])
                    }
                }
                motion.seekRevision = seekRevision
                motion.y = motion.position.position.toFloat()
            }
        }
        LaunchedEffect(motions) {
            var previous = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    val deltaMillis = (now - previous) / 1_000_000.0
                    previous = now
                    motions.forEach { motion ->
                        motion.position.update(deltaMillis)
                        motion.size.update(deltaMillis)
                        motion.y = motion.position.position.toFloat()
                        motion.scale = motion.size.position.toFloat() / 100f
                    }
                }
            }
        }
        LaunchedEffect(manual, scrollState.isScrollInProgress, touching, gestureRevision) {
            if (manual && !scrollState.isScrollInProgress && !touching) {
                delay(3_000L)
                manual = false
            }
        }

        // Only recompose the window when a row enters/leaves it, not on every
        // drag/fling frame. Scrolling now also has Compose's native inertia.
        val windowStart by remember(motions, measured) {
            derivedStateOf {
                val firstVisible = motions.indices.firstOrNull { index ->
                    motions[index].y + measured[index].height >= 0f
                } ?: 0
                (firstVisible - 3).coerceIn(0, (lines.size - 17).coerceAtLeast(0))
            }
        }
        // Browsing changes the distance reference, never the lyric brightness.
        // Keep the row nearest the reading anchor legible without lighting up
        // the whole screen or removing the surrounding depth blur.
        val blurAnchorIndex by remember(manual, activeIndex, motions, anchorY) {
            derivedStateOf {
                if (!manual) activeIndex else motions.indices.minByOrNull {
                    abs(motions[it].y - anchorY)
                } ?: activeIndex
            }
        }
        val entrance = entranceProgress.coerceIn(0f, 1f)
        Box(
            Modifier.width(maxWidth).height(maxHeight)
                .graphicsLayer {
                    alpha = entrance
                    translationY = (1f - entrance) * 18.dp.toPx()
                }
                .pointerInput(lines) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val origin = down.position
                        val touchSlop = viewConfiguration.touchSlop
                        tapBlocked = scrollState.isScrollInProgress ||
                            SystemClock.uptimeMillis() - scrollEndedAt < 300L
                        touching = true
                        gestureRevision++
                        interact()
                        try {
                            do {
                                // Decide before a row sees ACTION_UP. Movement at a scroll boundary
                                // is still a drag, even when scrollable cannot consume any distance.
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointer = event.changes.firstOrNull { it.id == down.id }
                                if (pointer == null || (pointer.position - origin).getDistance() > touchSlop ||
                                    event.changes.count { it.pressed } > 1) tapBlocked = true
                            } while (event.changes.any { it.pressed })
                        } finally {
                            touching = false
                        }
                    }
                }
                .scrollable(scrollState, Orientation.Vertical),
        ) {
            for (index in windowStart until minOf(windowStart + 17, lines.size)) {
                key(lines, index) {
                    AnimatedLyricRow(
                        line = lines[index],
                        measured = measured[index],
                        motion = motions[index],
                        position = playbackPosition,
                        relativeIndex = index - blurAnchorIndex,
                        active = index == activeIndex && !intro,
                        blurEffects = blurEffects,
                        paintPadding = with(density) { paintPadding.toPx() },
                        modifier = Modifier.offset(x = inset - paintPadding)
                            .width(stageWidth + paintPadding * 2f),
                        onSeek = seek@{
                            if (tapBlocked || scrollState.isScrollInProgress) return@seek
                            scope.launch {
                                scrollState.stopScroll(MutatePriority.PreventUserInput)
                                manual = false
                                seekAction(lines[index].startMs.coerceAtLeast(0L))
                                interact()
                                gestureRevision++
                            }
                        },
                    )
                }
            }
            if (intro) {
                val next = interludeNext
                val gapStart = if (next <= 0) 0L else lines[next - 1].endMs
                val gapEnd = lines.getOrNull(next)?.startMs ?: 0L
                InterludeDots(
                    startMs = gapStart,
                    endMs = gapEnd,
                    position = playbackPosition,
                    modifier = Modifier.padding(start = inset).width(64.dp).height(introSpace.let { with(density) { it.toDp() } })
                        .graphicsLayer {
                            translationY = (motions.getOrNull(next)?.y ?: (anchorY + introSpace)) - introSpace
                        },
                )
            }
        }
    }
}

@Composable
private fun AnimatedLyricRow(
    line: LyricLine,
    measured: LyricMeasure,
    motion: LyricMotion,
    position: State<Long>,
    relativeIndex: Int,
    active: Boolean,
    blurEffects: List<RenderEffect?>,
    paintPadding: Float,
    modifier: Modifier,
    onSeek: () -> Unit,
) {
    val density = LocalDensity.current
    val focus by animateFloatAsState(
        if (active) 1f else 0f,
        tween(if (active) 300 else 450, easing = wordFloatEasing),
        label = "lyricClarity",
    )
    val rowOpacity by animateFloatAsState(
        1f,
        tween(400, easing = filterEasing),
        label = "lyricOpacity",
    )
    val rowBlur by animateFloatAsState(
        LyricMotionPolicy.blurLevel(
            index = relativeIndex,
            scrollToIndex = 0,
            isFocused = active,
            isInViewport = true,
            isNarrowViewport = true,
        ).coerceAtMost(8f),
        tween(400, easing = filterEasing),
        label = "lyricBlur",
    )
    // Only the focused/fading row owns glyph textures. Reading this threshold in the cache
    // builder releases them after the fade without rebuilding the cache on every focus tick.
    val needsGlyphTextures = remember(line, measured) {
        derivedStateOf { line.wordTimed && measured.words.isNotEmpty() && focus >= 0.002f }
    }
    Box(
        modifier.requiredHeight(with(density) { (measured.height + paintPadding * 2f).toDp() })
            .graphicsLayer {
                val actualY = motion.y
                translationY = actualY - paintPadding
                transformOrigin = TransformOrigin(
                    if (line.alignEnd) 1f - paintPadding / size.width else paintPadding / size.width, 0.5f,
                )
                scaleX = motion.scale
                scaleY = motion.scale
                alpha = rowOpacity
                renderEffect = blurEffects[(rowBlur / 0.1f).roundToInt().coerceIn(0, blurEffects.lastIndex)]
            }.drawWithCache {
        val fontPixels = measured.layout.layoutInput.style.fontSize.toPx()
        // The ink is recorded ONCE at a fixed baseline, including the existing overflow room.
        // Repeated characters share an immutable display list; each occurrence has its own
        // offscreen paint layer so the moving sweep cannot tint another occurrence.
        val textureInset = ceil(paintPadding)
        val inkLayers = mutableMapOf<TextLayoutResult, GraphicsLayer>()
        val textures = if (needsGlyphTextures.value) measured.words.flatMap { it.glyphs }.associateWith { glyph ->
            val ink = inkLayers.getOrPut(glyph.layout) {
                obtainGraphicsLayer().apply {
                    record(size = IntSize(
                        glyph.layout.size.width + textureInset.toInt() * 2,
                        glyph.layout.size.height + textureInset.toInt() * 2,
                    )) {
                        drawText(glyph.layout, color = Color.White,
                            topLeft = Offset(textureInset, textureInset))
                    }
                }
            }
            LyricGlyphTexture(ink, obtainGraphicsLayer().apply {
                // Auto may replay text at the translated baseline; Offscreen moves pixels,
                // not text outlines, preserving fractional Y even on a multi-second note.
                compositingStrategy = LayerCompositingStrategy.Offscreen
            })
        } else emptyMap()
        onDrawBehind {
        val focused = focus.coerceIn(0f, 1f)
        val dimAlpha = 0.3f + 0.1f * focused
        val brightAlpha = 0.3f + 0.7f * focused
        measured.sub?.let { sub ->
            drawText(sub, color = Color.White.copy(alpha = 0.26f + 0.34f * focused),
                topLeft = Offset(paintPadding, paintPadding + measured.layout.size.height + measured.subGap))
        }
        if (!line.wordTimed) {
            // Plain LRC supplies line times, not word times. Never fabricate a
            // syllable sweep for imported songs without those timestamps.
            drawText(measured.layout, color = Color.White.copy(alpha = brightAlpha),
                topLeft = Offset(paintPadding, paintPadding))
            return@onDrawBehind
        }
        if (textures.isEmpty()) {
            drawText(measured.layout, color = Color.White.copy(alpha = dimAlpha),
                topLeft = Offset(paintPadding, paintPadding))
            return@onDrawBehind
        }
        val positionMs = position.value
        measured.words.forEach { word ->
            val progress = wordProgress(word, positionMs)
            val floatProgress = ((positionMs - word.startMs).toFloat() /
                (word.endMs - word.startMs).coerceAtLeast(1000L)).coerceIn(0f, 1f)
            val lift = wordLiftEasing.transform(floatProgress) * fontPixels * 0.085f * focused
            val feather = fontPixels * 0.5f
            val sweep = (word.width + feather) * progress - feather * 0.5f
            word.glyphs.forEach { glyph ->
                val texture = textures.getValue(glyph)
                val edge = if (progress > 0f && progress < 1f) {
                    glyph.bounds.left - glyph.origin.x + textureInset + sweep - glyph.advance
                } else null
                if (!texture.recorded || texture.sweepEdge != edge ||
                    (edge != null && texture.focus != focused)) {
                    texture.paint.record(size = texture.ink.size) {
                        drawLayer(texture.ink)
                        if (edge != null) drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.White.copy(alpha = brightAlpha), Color.White.copy(alpha = dimAlpha)),
                                edge - feather * 0.5f,
                                edge + feather * 0.5f,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    texture.recorded = true
                    texture.sweepEdge = edge
                    texture.focus = focused
                }
                texture.paint.alpha = if (edge != null) 1f else if (progress >= 1f) brightAlpha else dimAlpha
                // Preserve layout/baseline/TTML end alignment. Never round this translation or
                // pass lift to drawText: the old path rerasterized each tiny vertical change.
                texture.paint.translationX = glyph.origin.x + paintPadding - textureInset
                texture.paint.translationY = glyph.origin.y + paintPadding - textureInset - lift
                drawLayer(texture.paint)
            }
        }
        }
    })
    // Blur padding is visual overflow, not a larger tap target. Keeping the
    // hitbox at the text bounds prevents adjacent rows stealing each other's taps.
    Box(
        modifier.padding(horizontal = with(density) { paintPadding.toDp() })
            .height(with(density) { measured.height.toDp() })
            .graphicsLayer {
                translationY = motion.y
                transformOrigin = TransformOrigin(if (line.alignEnd) 1f else 0f, 0.5f)
                scaleX = motion.scale
                scaleY = motion.scale
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onSeek,
            )
            .semantics { contentDescription = line.text },
    )
}

/**
 * The accepted breathing scale/exit is preserved; waiting illumination follows AMLL core 0.6.0.
 * Everything reads the playback clock, so pausing freezes the exact visible state.
 */
private val DotLightingEasing = CubicBezierEasing(.56f, .01f, .45f, 1f)

@Composable
private fun InterludeDots(startMs: Long, endMs: Long, position: State<Long>, modifier: Modifier) {
    Canvas(modifier) {
        val duration = (endMs - startMs).coerceAtLeast(1L).toFloat()
        val t = (position.value - startMs).toFloat().coerceIn(0f, duration)
        val remaining = duration - t
        val radius = 4.2.dp.toPx()
        val spacing = radius * 3.4f
        val enter = FastOutSlowInEasing.transform((t / 420f).coerceIn(0f, 1f))
        val breath = 0.93f + 0.07f * (0.5f + 0.5f * cos(t / 1750f * 2f * PI.toFloat()))
        val exit = when {
            remaining > 600f -> 1f
            remaining > 260f -> 1f + 0.12f * FastOutSlowInEasing.transform((600f - remaining) / 340f)
            else -> 1.12f * FastOutSlowInEasing.transform(remaining / 260f)
        }
        val scale = enter * breath * exit
        if (scale <= 0.001f) return@Canvas
        val groupAlpha = (if (remaining < 260f) remaining / 260f else 1f) * enter
        val pivot = Offset(radius + spacing, size.height * 0.5f)
        val waitingMs = (duration - 1_000f).coerceAtLeast(0f)
        val shortWait = waitingMs < 3_000f
        val segmentMs = ((waitingMs + 750f) / 3f).roundToInt().toFloat().coerceAtLeast(1f)
        val thirdDuration = (waitingMs - segmentMs * 2f).coerceAtLeast(1f)
        val thirdTarget = (thirdDuration / segmentMs).coerceIn(0f, 1f)
        withTransform({ scale(scale, scale, pivot) }) {
            for (index in 0 until 3) {
                val fill = when {
                    // A short gap has no room for three rushed progress-bar-like flashes.
                    shortWait -> 1f
                    t >= waitingMs -> if (index < 2) 1f else {
                        thirdTarget + (1f - thirdTarget) * ((t - waitingMs) / 750f).coerceIn(0f, 1f)
                    }
                    else -> {
                        val dotDuration = if (index == 2) thirdDuration else segmentMs
                        val progress = ((t - index * segmentMs) / dotDuration).coerceIn(0f, 1f)
                        DotLightingEasing.transform(progress) * if (index == 2) thirdTarget else 1f
                    }
                }
                val arrival = ((t - index * 80f) / 750f).coerceIn(0f, 1f)
                drawCircle(
                    Color.White.copy(alpha = (0.20f + 0.70f * fill) * arrival * arrival * groupAlpha),
                    radius,
                    Offset(radius + index * spacing, size.height * 0.5f),
                )
            }
        }
    }
}

private const val INTERLUDE_MIN_MS = 4_000L

/** Next line index when [positionMs] sits in the prelude or an instrumental gap, else -1. */
private fun interludeTarget(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    if (positionMs < lines.first().startMs) return 0
    val current = lyricIndexAt(lines, positionMs)
    val next = current + 1
    if (next > lines.lastIndex) return -1
    val gapStart = lines[current].endMs
    val gapEnd = lines[next].startMs
    return if (gapEnd - gapStart >= INTERLUDE_MIN_MS && positionMs >= gapStart && positionMs < gapEnd) next else -1
}

private fun wordProgress(word: LyricWordPaint, positionMs: Long): Float = when {
    positionMs < word.startMs -> 0f
    word.endMs <= word.startMs || positionMs >= word.endMs -> 1f
    else -> ((positionMs - word.startMs).toDouble() / (word.endMs - word.startMs)).toFloat().coerceIn(0f, 1f)
}

private fun measureLyricWords(
    line: LyricLine,
    layout: TextLayoutResult,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    glyphLayouts: MutableMap<String, TextLayoutResult>,
): LyricMeasure {
    val text = line.text
    val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
    iterator.setText(text)
    var textOffset = 0
    val words = line.words.map { cue ->
        val matchedOffset = text.indexOf(cue.text, textOffset)
        val wordStart = if (matchedOffset >= 0) matchedOffset else textOffset
        val wordEnd = (wordStart + cue.text.length).coerceAtMost(text.length)
        textOffset = wordEnd
        var character = wordStart
        var advance = 0f
        val glyphs = mutableListOf<LyricGlyph>()
        while (character < wordEnd) {
            val next = iterator.following(character).let {
                if (it == BreakIterator.DONE) wordEnd else it.coerceAtMost(wordEnd)
            }
            var bounds = layout.getBoundingBox(character)
            for (offset in character + 1 until next) {
                val nextBounds = layout.getBoundingBox(offset)
                bounds = Rect(
                    minOf(bounds.left, nextBounds.left), minOf(bounds.top, nextBounds.top),
                    maxOf(bounds.right, nextBounds.right), maxOf(bounds.bottom, nextBounds.bottom),
                )
            }
            if (bounds.width > 0f && text.substring(character, next).any { it != '\n' && it != '\r' }) {
                val glyphText = text.substring(character, next)
                val glyphLayout = glyphLayouts.getOrPut(glyphText) { textMeasurer.measure(glyphText, style) }
                val baseline = layout.getLineBaseline(layout.getLineForOffset(character))
                glyphs.add(LyricGlyph(bounds, advance, glyphLayout,
                    Offset(bounds.left, baseline - glyphLayout.firstBaseline)))
                advance += bounds.width
            }
            character = next
        }
        LyricWordPaint(cue.startMs, cue.endMs, glyphs, advance)
    }
    return LyricMeasure(layout, words)
}

private fun lyricIndexAt(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var result = 0
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (lines[middle].startMs <= positionMs) {
            result = middle
            low = middle + 1
        } else high = middle - 1
    }
    return result
}

private fun nearestLyricOffset(offsets: FloatArray, target: Float): Int {
    var low = 0
    var high = (offsets.size - 2).coerceAtLeast(0)
    while (low < high) {
        val middle = (low + high + 1) ushr 1
        if (offsets[middle] <= target) low = middle else high = middle - 1
    }
    return low
}
