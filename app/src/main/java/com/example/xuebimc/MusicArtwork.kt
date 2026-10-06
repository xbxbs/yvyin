package com.example.xuebimc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** requestSize is in pixels, bucketed to 64/128/256/512 for bounded, shared decoding. */
@Composable
fun TrackArtwork(
    track: Track?,
    modifier: Modifier = Modifier,
    requestSize: Int = 256,
) {
    val artwork = rememberMusicArtwork(track, requestSize)
    val description = if (track == null) "暂无歌曲封面" else "${track.title} · ${track.artist} 封面"
    val bitmap = artwork?.image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = description,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        ArtworkPlaceholder(modifier.semantics {
            contentDescription = description
            role = Role.Image
        })
    }
}

/**
 * Cached soft color fields, never a screen-sized bitmap or a per-frame blur.
 * Callers gate playback/screen visibility with motionEnabled; lifecycle and reduced motion
 * are also respected here. Pausing freezes the phase, so resuming never restarts the drift.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MusicBackdrop(
    track: Track?,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true,
) {
    // Matches the player's cover request, so both consumers share a single decode.
    // Keep the previous field while the next cover is loading: no fallback-color flash.
    val palette = rememberMusicArtwork(track, 512)?.palette
        ?: if (track == null) DefaultArtworkPalette else null
    val backdrop = remember { BackdropState(palette ?: DefaultArtworkPalette) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val canMove = motionEnabled && LocalAnimatedBackground.current && track != null
    LaunchedEffect(palette, canMove, lifecycle) {
        val durationScale = currentCoroutineContext()[MotionDurationScale]
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // WindowRecomposer supplies an observable scale, including Remove animations.
            snapshotFlow { durationScale?.scaleFactor ?: 1f }.collectLatest { scale ->
                val systemMotion = scale.isFinite() && scale > 0f
                val animate = canMove && systemMotion
                if (palette != null) backdrop.retarget(palette, animate)
                if (!animate) {
                    // Playback pause freezes even an unfinished fade without another draw.
                    // Only the accessibility setting settles immediately to a static field.
                    if (!systemMotion) backdrop.blend = 1f
                    return@collectLatest
                }
                var previousFrame = withFrameNanos { it }
                while (true) {
                    withFrameNanos { frame ->
                        // Clamp a stalled frame, and never count time spent paused/hidden.
                        val seconds = ((frame - previousFrame) / 1_000_000_000f)
                            .coerceIn(0f, .05f) / scale.coerceAtLeast(.05f)
                        previousFrame = frame
                        backdrop.phase = (backdrop.phase + seconds * .034906585f) % 6.2831855f
                        backdrop.blend = (backdrop.blend + seconds / 1.8f).coerceAtMost(1f)
                    }
                }
            }
        }
    }
    Box(modifier.drawWithCache {
        // Only endpoint palettes/size invalidate this cache; frame state is read in draw.
        val from = BackdropField(backdrop.from, size)
        val to = BackdropField(backdrop.to, size)
        val shade = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .08f),
            .48f to Color.Black.copy(alpha = .14f),
            1f to Color.Black.copy(alpha = .36f),
        )
        onDrawBehind {
            val phase = backdrop.phase
            val blend = backdrop.easedBlend
            clipRect {
                if (blend < 1f) drawBackdropField(from, phase, 1f)
                if (blend > 0f) drawBackdropField(to, phase, blend)
                drawRect(shade)
            }
        }
    })
}

private class BackdropState(initial: ArtworkPalette) {
    var from by mutableStateOf(initial)
    var to by mutableStateOf(initial)
    var blend by mutableFloatStateOf(1f)
    var phase by mutableFloatStateOf(0f)
    val easedBlend: Float get() = blend * blend * (3f - 2f * blend)

    fun retarget(palette: ArtworkPalette, animate: Boolean) {
        if (palette == to) return
        val fraction = easedBlend
        from = if (fraction >= 1f) to else ArtworkPalette(
            top = lerp(from.top, to.top, fraction),
            bottom = lerp(from.bottom, to.bottom, fraction),
            glow = lerp(from.glow, to.glow, fraction),
            secondary = lerp(from.secondary, to.secondary, fraction),
            accent = lerp(from.accent, to.accent, fraction),
        )
        to = palette
        blend = if (animate) 0f else 1f
    }
}

private class BackdropField(palette: ArtworkPalette, size: Size) {
    val radius = maxOf(size.width, size.height).coerceAtLeast(1f) * .76f
    val base = Brush.verticalGradient(listOf(palette.top, palette.bottom))
    val primary = softGlow(palette.glow, radius)
    val secondary = softGlow(palette.secondary, radius * .94f)
    val accent = softGlow(palette.accent, radius * .84f)

    private fun softGlow(color: Color, radius: Float): Brush = Brush.radialGradient(
        0f to color.copy(alpha = .90f),
        .28f to color.copy(alpha = .72f),
        .62f to color.copy(alpha = .27f),
        1f to color.copy(alpha = 0f),
        center = Offset.Zero,
        radius = radius,
    )
}

private fun DrawScope.drawBackdropField(field: BackdropField, phase: Float, alpha: Float) {
    drawRect(field.base, alpha = alpha)
    // Integer harmonics give continuous 36–60s orbits, with no discontinuity at phase wrap.
    val breath = sin(phase * 2f) * .055f
    withTransform({
        translate(size.width * (.22f + .18f * sin(phase * 3f)),
            size.height * (.24f + .13f * cos(phase * 4f)))
        scale(1.12f + breath, .94f - breath, Offset.Zero)
    }) {
        drawCircle(field.primary, field.radius, Offset.Zero, alpha = alpha)
    }
    withTransform({
        translate(size.width * (.78f + .17f * cos(phase * 4f + 1.2f)),
            size.height * (.49f + .18f * sin(phase * 3f + .8f)))
        scale(.98f - breath, 1.12f + breath, Offset.Zero)
    }) {
        drawCircle(field.secondary, field.radius * .94f, Offset.Zero, alpha = alpha)
    }
    withTransform({
        translate(size.width * (.42f + .23f * sin(phase * 5f + 2.1f)),
            size.height * (.82f + .12f * cos(phase * 3f + 1.7f)))
        scale(1.18f + breath, .86f - breath, Offset.Zero)
    }) {
        drawCircle(field.accent, field.radius * .84f, Offset.Zero, alpha = alpha)
    }
}

@Composable
private fun ArtworkPlaceholder(modifier: Modifier) {
    Box(modifier.drawWithCache {
        val side = minOf(size.width, size.height)
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        fun point(x: Float, y: Float) = Offset(left + side * x, top + side * y)
        val background = Brush.linearGradient(
            colors = listOf(Color(0xFF403249), Color(0xFF292431), Color(0xFF19161F)),
            start = Offset.Zero,
            end = Offset(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f)),
        )
        val haze = Brush.radialGradient(
            colors = listOf(Color(0xFF806282).copy(alpha = .25f), Color.Transparent),
            center = point(.23f, .22f),
            radius = (side * .8f).coerceAtLeast(1f),
        )
        val beam = Path().apply {
            moveTo(left + side * .43f, top + side * .33f)
            lineTo(left + side * .68f, top + side * .25f)
            lineTo(left + side * .68f, top + side * .34f)
            lineTo(left + side * .43f, top + side * .42f)
            close()
        }
        val ink = Color(0xFFDBCCDD).copy(alpha = .64f)
        onDrawBehind {
            drawRect(background)
            drawRect(haze)
            drawCircle(Color.White.copy(alpha = .025f), side * .37f, point(.5f, .5f))
            drawPath(beam, ink)
            drawLine(ink, point(.43f, .36f), point(.43f, .68f), side * .032f, StrokeCap.Round)
            drawLine(ink, point(.68f, .28f), point(.68f, .60f), side * .032f, StrokeCap.Round)
            drawOval(ink, point(.28f, .63f), Size(side * .16f, side * .11f))
            drawOval(ink, point(.53f, .55f), Size(side * .16f, side * .11f))
        }
    })
}

private data class ArtworkPalette(
    val top: Color,
    val bottom: Color,
    val glow: Color,
    val secondary: Color,
    val accent: Color,
)

private val DefaultArtworkPalette = ArtworkPalette(
    top = Color(0xFF322A3A),
    bottom = Color(0xFF16131C),
    glow = Color(0xFF66506F),
    secondary = Color(0xFF334C62),
    accent = Color(0xFF62463E),
)

private data class ArtworkKey(
    val stableKey: String,
    val artworkUri: String?,
    val size: Int,
)

private data class CachedArtwork(
    val image: ImageBitmap?,
    val palette: ArtworkPalette,
    val byteCount: Int,
    val loadedAt: Long = SystemClock.uptimeMillis(),
)

@Composable
private fun rememberMusicArtwork(track: Track?, requestSize: Int): CachedArtwork? {
    val context = LocalContext.current.applicationContext
    val stableKey = track?.stableKey
    val artworkUri = track?.artworkUri?.toString()
    val size = when {
        requestSize <= 64 -> 64
        requestSize <= 128 -> 128
        requestSize <= 256 -> 256
        else -> 512
    }
    // Playback headers/URLs do not identify artwork and are never sent to the image host.
    val key = remember(stableKey, artworkUri, size) {
        stableKey?.let { ArtworkKey(it, artworkUri, size) }
    }
    // A new URI gets fresh state immediately; a previous track's art never flashes here.
    val artwork = remember(key) { mutableStateOf(key?.let(MusicArtworkCache::peek)) }
    LaunchedEffect(context, key) {
        if (track != null && key != null) {
            val loaded = MusicArtworkCache.load(context, track, key)
            if (loaded.image != null || artwork.value?.image == null) artwork.value = loaded
            // A remembered miss must not last forever; one retry, shared by the cache TTL.
            if (loaded.image == null && artwork.value?.image == null) {
                delay(MusicArtworkCache.retryDelayMillis(loaded))
                if (artwork.value?.image == null) {
                    val retried = MusicArtworkCache.load(context, track, key)
                    if (retried.image != null || artwork.value?.image == null) artwork.value = retried
                }
            }
        }
    }
    LaunchedEffect(key) {
        if (key != null) {
            // A concurrent successful decode also rescues consumers holding an earlier miss.
            MusicArtworkCache.updates.collect {
                MusicArtworkCache.peek(key)?.takeIf { it.image != null }?.let { artwork.value = it }
            }
        }
    }
    return artwork.value
}

private object MusicArtworkCache {
    private const val CACHE_BYTES = 12 * 1024 * 1024
    private const val MISS_LIFETIME_MS = 30_000L
    // Serial decoding also coalesces simultaneous cover/backdrop requests after a cache recheck.
    private val decodeMutex = Mutex()
    val updates = MutableStateFlow(0L)
    private var repository: LocalMusicRepository? = null
    private val cache = object : LruCache<ArtworkKey, CachedArtwork>(CACHE_BYTES) {
        override fun sizeOf(key: ArtworkKey, value: CachedArtwork): Int =
            value.byteCount.coerceAtLeast(64 * 1024) // Bound missing/tiny-cover entries as well.
        // Never recycle on eviction: Compose may still be displaying the bitmap.
    }

    fun peek(key: ArtworkKey): CachedArtwork? = lookup(key, allowSmaller = true)

    fun retryDelayMillis(artwork: CachedArtwork): Long =
        (MISS_LIFETIME_MS - (SystemClock.uptimeMillis() - artwork.loadedAt)).coerceAtLeast(0L)

    private fun lookup(key: ArtworkKey, allowSmaller: Boolean): CachedArtwork? = synchronized(cache) {
        var size = key.size
        var miss: CachedArtwork? = null
        while (size <= 512) {
            val candidate = key.copy(size = size)
            val entry = cache.get(candidate)
            if (entry != null) {
                if (entry.image != null) return@synchronized entry
                if (SystemClock.uptimeMillis() - entry.loadedAt >= MISS_LIFETIME_MS) {
                    cache.remove(candidate)
                } else if (size == key.size) {
                    miss = entry
                }
            }
            size *= 2
        }
        if (allowSmaller) {
            size = key.size / 2
            while (size >= 64) {
                cache.get(key.copy(size = size))?.takeIf { it.image != null }?.let { return@synchronized it }
                size /= 2
            }
        }
        // A failed size never hides a successful image of the same song AND artwork URI.
        miss
    }

    suspend fun load(context: Context, track: Track, key: ArtworkKey): CachedArtwork =
        withContext(Dispatchers.IO) {
            decodeMutex.withLock {
                currentCoroutineContext().ensureActive()
                val cached = lookup(key, allowSmaller = false)
                if (cached != null) {
                    if (cached.image != null) cached else peek(key) ?: cached
                } else {
                    val entry = read(context, track, key.size)
                    currentCoroutineContext().ensureActive()
                    synchronized(cache) { cache.put(key, entry) }
                    if (entry.image != null) updates.value += 1L
                    // Upgrade failure preserves a smaller preview; headers never split its key.
                    if (entry.image != null) entry else peek(key) ?: entry
                }
            }
        }

    private suspend fun read(context: Context, track: Track, size: Int): CachedArtwork {
        return try {
            val source = repository ?: LocalMusicRepository(context.applicationContext).also { repository = it }
            val bitmap = source.loadArtwork(track, size)?.let { fitArtwork(it, size) }
            currentCoroutineContext().ensureActive()
            CachedArtwork(
                image = bitmap?.asImageBitmap(),
                palette = bitmap?.let(::sampleArtworkPalette) ?: DefaultArtworkPalette,
                byteCount = bitmap?.allocationByteCount ?: 0,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing files, revoked permissions, or malformed art should leave a quiet placeholder.
            CachedArtwork(null, DefaultArtworkPalette, 0)
        }
    }
}

/** Defensive final bound; the repository also receives the size limit when decoding. */
private fun fitArtwork(source: Bitmap, maxSize: Int): Bitmap? {
    if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
    val largest = maxOf(source.width, source.height)
    val fitted = if (largest > maxSize) {
        val scale = maxSize.toFloat() / largest
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        source
    }
    // Hardware pixels cannot be read; copy only after bounding dimensions, off the UI thread.
    return if (fitted.config == Bitmap.Config.HARDWARE) fitted.copy(Bitmap.Config.ARGB_8888, false) else fitted
}

/** Hue buckets keep warm/cool accents apart instead of averaging them into gray. */
private fun sampleArtworkPalette(bitmap: Bitmap): ArtworkPalette {
    val buckets = Array(12) { FloatArray(4) }
    val neutral = FloatArray(4)
    val hsv = FloatArray(3)
    var brightness = 0f
    // At most 16x16 samples, once per cached decode, on the existing IO dispatcher.
    for (row in 0 until 16) {
        val y = ((row + .5f) * bitmap.height / 16f).toInt().coerceIn(0, bitmap.height - 1)
        for (column in 0 until 16) {
            val x = ((column + .5f) * bitmap.width / 16f).toInt().coerceIn(0, bitmap.width - 1)
            val pixel = bitmap.getPixel(x, y)
            val alpha = AndroidColor.alpha(pixel) / 255f
            if (alpha < .1f) continue
            val red = AndroidColor.red(pixel) / 255f
            val green = AndroidColor.green(pixel) / 255f
            val blue = AndroidColor.blue(pixel) / 255f
            AndroidColor.colorToHSV(pixel, hsv)
            neutral[0] += red * alpha
            neutral[1] += green * alpha
            neutral[2] += blue * alpha
            neutral[3] += alpha
            brightness += hsv[2] * alpha
            if (hsv[1] < .10f || hsv[2] < .04f) continue
            val sum = buckets[(hsv[0] / 30f).toInt().coerceIn(0, 11)]
            val weight = alpha * hsv[1] * hsv[1] * (.25f + .75f * hsv[2])
            sum[0] += red * weight
            sum[1] += green * weight
            sum[2] += blue * weight
            sum[3] += weight
        }
    }
    fun average(sum: FloatArray): Color? = if (sum[3] > 0f) {
        Color(
            red = (sum[0] / sum[3]).coerceIn(0f, 1f),
            green = (sum[1] / sum[3]).coerceIn(0f, 1f),
            blue = (sum[2] / sum[3]).coerceIn(0f, 1f),
        )
    } else null
    val fallback = average(neutral) ?: return DefaultArtworkPalette
    val dominant = buckets.indices.maxByOrNull { buckets[it][3] } ?: 0
    fun distance(a: Int, b: Int): Int = abs(a - b).let { minOf(it, 12 - it) }
    fun distinct(first: Int, second: Int = first): Int {
        var best = first
        var bestScore = 0f
        for (index in buckets.indices) {
            val separation = minOf(distance(index, first), distance(index, second))
            val weight = buckets[index][3]
            if (separation < 2 || weight < buckets[dominant][3] * .04f) continue
            val score = weight * (.4f + separation / 6f)
            if (score > bestScore) {
                best = index
                bestScore = score
            }
        }
        return best
    }
    val secondary = distinct(dominant)
    val accent = distinct(dominant, secondary)
    val first = average(buckets[dominant]) ?: fallback
    val second = average(buckets[secondary]) ?: first
    val third = average(buckets[accent]) ?: first
    val light = (brightness / neutral[3]).coerceIn(0f, 1f)
    val energy = .64f + .30f * light
    val luminanceCap = .035f + .145f * light
    return ArtworkPalette(
        top = backdropTint(first, .23f + .18f * light, .05f),
        bottom = backdropTint(second, .09f + .08f * light, .016f),
        glow = backdropTint(first, energy, luminanceCap),
        secondary = backdropTint(second, energy, luminanceCap),
        accent = backdropTint(third, energy * .92f, luminanceCap * .9f),
    )
}

/** Boost existing chroma only; bound brightness for dark artwork and white foreground text. */
private fun backdropTint(source: Color, valueScale: Float, luminanceCap: Float): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(source.toArgb(), hsv)
    hsv[1] = (hsv[1] + .28f * hsv[1] * (1f - hsv[1])).coerceIn(0f, 1f)
    hsv[2] *= valueScale
    val tinted = Color(AndroidColor.HSVToColor(hsv))
    if (tinted.luminance() <= luminanceCap) return tinted
    var low = 0f
    var high = hsv[2]
    repeat(8) {
        hsv[2] = (low + high) * .5f
        if (Color(AndroidColor.HSVToColor(hsv)).luminance() > luminanceCap) {
            high = hsv[2]
        } else {
            low = hsv[2]
        }
    }
    hsv[2] = low
    return Color(AndroidColor.HSVToColor(hsv))
}
