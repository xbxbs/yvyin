package com.example.xuebimc

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object LyricMotionPolicy {
    data class SpringParameters(
        val stiffness: Float,
        val dampingRatio: Float,
    )

    data class StaggerDelays(
        val lineDelayMillis: List<Double>,
        val bottomLineDelayMillis: Double,
    )

    // AMLL's scale spring uses mass=2, stiffness=100, damping=25.
    // It is intentionally slower than the vertical position spring.
    fun scaleSpring() = SpringParameters(50f, (25.0 / (2.0 * sqrt(200.0))).toFloat())

    /** Pixel alpha at the viewport edge, applied AFTER line blur/compositing. */
    fun edgeOpacity(y: Float, height: Float, topFade: Float, bottomFade: Float): Float {
        if (height <= 0f || y <= 0f || y >= height) return 0f
        fun smooth(value: Float): Float {
            val t = value.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        return smooth(y / topFade.coerceAtLeast(1f)) *
            smooth((height - y) / bottomFade.coerceAtLeast(1f))
    }

    /** Playback-only tuning; keep lineSpring's upstream policy for existing callers. */
    fun followSpring(
        intervalMillis: Double? = null,
        isInterludeActive: Boolean = false,
    ): SpringParameters {
        require(intervalMillis == null || intervalMillis.isFinite())
        if (isInterludeActive || intervalMillis == null) return returnSpring()

        val urgency = (800.0 - intervalMillis.coerceIn(100.0, 800.0)) / 700.0
        // Unlike upstream's urgency^0.2, this has no steep jump near 800ms.
        val smoothUrgency = urgency * urgency * (3.0 - 2.0 * urgency)
        // AMLL mass=0.9; k=90..130 lies between its slow 90 and normal 170..220.
        // Critical damping uses c=2*sqrt(k*m), rather than the slow preset's c=15.
        // From rest, 95% travel takes about 395..474ms, versus 303..345ms upstream.
        return SpringParameters(((90.0 + 40.0 * smoothUrgency) / 0.9).toFloat(), 1f)
    }

    /** Manual-scroll/seek recovery: slow AMLL stiffness, critically damped. */
    fun returnSpring() = SpringParameters(100f, 1f) // k=90, m=0.9, c=18.

    fun lineSpring(
        intervalMillis: Double? = null,
        isSeeking: Boolean = false,
        isInterludeActive: Boolean = false,
        isEndOfSong: Boolean = false,
        mass: Double = 0.9,
    ): SpringParameters {
        require(mass.isFinite() && mass > 0.0)
        require(intervalMillis == null || intervalMillis.isFinite())

        val physicalStiffness: Double
        val physicalDamping: Double
        when {
            isSeeking || isInterludeActive -> {
                physicalStiffness = 90.0
                physicalDamping = 15.0
            }
            isEndOfSong -> {
                physicalStiffness = 140.0
                physicalDamping = 22.0
            }
            intervalMillis == null -> {
                physicalStiffness = 90.0
                physicalDamping = 15.0
            }
            else -> {
                val urgency = (800.0 - intervalMillis.coerceIn(100.0, 800.0)) / 700.0
                physicalStiffness = 170.0 + 50.0 * urgency.pow(0.2)
                physicalDamping = 2.2 * sqrt(physicalStiffness)
            }
        }

        val stiffness = (physicalStiffness / mass).toFloat()
        val dampingRatio = physicalDamping / (2.0 * sqrt(physicalStiffness * mass))
        require(stiffness.isFinite() && stiffness > 0f)
        return SpringParameters(stiffness, dampingRatio.coerceAtMost(1.0).toFloat())
    }

    fun staggerDelays(
        lineBottoms: List<Double>,
        scrollToIndex: Int,
        disableStagger: Boolean = false,
    ): StaggerDelays {
        require(lineBottoms.all { it.isFinite() })
        var elapsedMillis = 0.0
        var stepMillis = if (disableStagger) 0.0 else 50.0
        val delays = lineBottoms.mapIndexed { index, bottom ->
            val assignedDelay = elapsedMillis
            if (bottom >= 0.0) {
                elapsedMillis += stepMillis
                if (index >= scrollToIndex) stepMillis *= 1.0 / 1.05
            }
            assignedDelay
        }
        return StaggerDelays(delays, elapsedMillis)
    }

    fun blurLevel(
        index: Int,
        scrollToIndex: Int,
        isFocused: Boolean,
        isInViewport: Boolean,
        isNarrowViewport: Boolean,
        latestHighlightedIndex: Int? = null,
        enableBlur: Boolean = true,
        isTouchScrolled: Boolean = false,
    ): Float {
        if (!enableBlur || isFocused) return 0f

        val distance = if (index < scrollToIndex) {
            scrollToIndex.toDouble() - index.toDouble()
        } else {
            abs(index.toDouble() - (latestHighlightedIndex ?: scrollToIndex).toDouble())
        }
        // Preserve depth during touch browsing too: only the distance reference
        // changes. At rest/preamble the anchor line stays readable, not enlarged.
        val neighbourBlur = if (isNarrowViewport) 2.2 else 2.4
        val blur = (if (distance < 1.0) 0.65 else neighbourBlur + (distance - 1.0) * 1.45)
            .coerceAtMost(8.0).toFloat()
        // Leaving the viewport must not reduce blur; edge opacity stays separate.
        return if (isInViewport) blur else blur.coerceAtLeast(6f)
    }
}
