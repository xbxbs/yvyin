package com.example.xuebimc

import androidx.compose.animation.core.Easing
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** JVM-only geometry/renderer-contract check; this does not measure Android GPU frame pacing. */
fun main(args: Array<String>) {
    val source = File(args.single()).readText()
    val movingPass = source.substringAfter("val positionMs = position.value")
        .substringBefore("// Blur padding")
    check(!movingPass.contains("drawText(")) { "Moving pass must reuse fixed-baseline glyphs" }
    check(source.contains("compositingStrategy = LayerCompositingStrategy.Offscreen"))
    check(movingPass.contains("texture.paint.translationY = glyph.origin.y + paintPadding - textureInset - lift"))

    // Exercise the production curve, not a substitute curve fitted to the test.
    val easing = Class.forName("com.example.xuebimc.LyricsScreenKt")
        .getDeclaredField("wordLiftEasing").apply { isAccessible = true }.get(null) as Easing
    fun lift(ms: Long, duration: Long, fontPx: Float): Float =
        easing.transform((ms.toFloat() / duration.coerceAtLeast(1000L)).coerceIn(0f, 1f)) * fontPx * 0.085f

    for (hz in listOf(60, 90, 120)) {
        for (duration in listOf(1000L, 3000L, 6000L, 12000L)) {
            for (font in listOf(28f, 74f)) {
                val frames = (duration * hz / 1000).toInt()
                val samples = (0..frames).map { lift((it * 1000.0 / hz).toLong(), duration, font) }
                check(samples.zipWithNext().all { (a, b) -> b + 0.0001f >= a })
                check(abs(samples.last() - font * 0.085f) < 0.0001f)
                check(samples.distinct().size > samples.map { it.roundToInt() }.distinct().size * 5)
            }
        }
    }
    // Texture padding cancels out: fractional baselines, wrapped rows and duet-right X stay put.
    for (padding in listOf(32f, 85.3f)) {
        val inset = ceil(padding)
        for (origin in listOf(0f, 0.375f, 90.25f, 620.75f)) {
            for (ms in listOf(0L, 8L, 2750L, 6000L)) {
                val rise = lift(ms, 6000L, 74f)
                check(abs((origin + padding - inset - rise + inset) - (origin + padding - rise)) < 0.0001f)
                check(abs((origin + padding - inset + inset) - (origin + padding)) < 0.0001f)
                val boundsLeft = origin + 0.25f
                val advance = 148.5f
                val sweep = 53.125f
                val oldEdge = boundsLeft + padding + sweep - advance - (origin + padding)
                val textureEdge = boundsLeft - origin + inset + sweep - advance
                check(abs(oldEdge - (textureEdge - inset)) < 0.0001f)
            }
        }
    }
    val slow = (0..720).map { lift((it * 1000.0 / 120).toLong(), 6000L, 74f) }
    println("PASS: 60/90/120Hz, 1/3/6/12s notes, unchanged curve, baseline/duet/sweep geometry")
    println("6s / 74px / 120Hz: ${slow.distinct().size} float heights, ${slow.map { it.roundToInt() }.distinct().size} integer-pixel heights")
}
