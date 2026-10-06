package com.example.xuebimc

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Native port of AMLL core 0.6.0 src/utils/spring.ts (AGPL-3.0-only).
 * A delayed target does NOT stop the old solver. Retargeting inherits its
 * current position and velocity, including when another target is queued.
 * All rows are advanced by ONE frame clock, not a coroutine per animation.
 */
internal class AmllSpring(
    initialPosition: Float,
    parameters: LyricMotionPolicy.SpringParameters = LyricMotionPolicy.lineSpring(),
) {
    var position = initialPosition.toDouble()
        private set
    var velocity = 0.0
        private set
    private var target = position
    private var from = position
    private var initialVelocity = 0.0
    private var elapsed = 0.0
    private var stiffness = parameters.stiffness.toDouble()
    private var dampingRatio = parameters.dampingRatio.toDouble()
    private var queuedTarget: Double? = null
    private var delaySeconds = 0.0
    private var moving = false

    fun snapTo(value: Float) {
        position = value.toDouble()
        target = position
        from = position
        velocity = 0.0
        initialVelocity = 0.0
        elapsed = 0.0
        queuedTarget = null
        delaySeconds = 0.0
        moving = false
    }

    fun updateParameters(parameters: LyricMotionPolicy.SpringParameters) {
        val nextStiffness = parameters.stiffness.toDouble()
        val nextDampingRatio = parameters.dampingRatio.toDouble()
        if (stiffness == nextStiffness && dampingRatio == nextDampingRatio) return
        stiffness = nextStiffness
        dampingRatio = nextDampingRatio
        // Parameter changes must not cancel a staggered target or its deadline.
        // Rebase only a running solver, retaining both position and velocity.
        if (moving) resetSolver()
    }

    fun setTargetPosition(value: Float, delayMillis: Double = 0.0) {
        val next = value.toDouble()
        if (abs(target - next) < 0.001) {
            queuedTarget = null
            delaySeconds = 0.0
            return
        }
        if (delayMillis > 0.0) {
            val pending = queuedTarget
            val requestedDelay = delayMillis / 1000.0
            if (pending != null && abs(pending - next) < 0.001) {
                // Repeated layout requests may shorten, but never rearm, a wait.
                delaySeconds = minOf(delaySeconds, requestedDelay)
            } else {
                queuedTarget = next
                delaySeconds = requestedDelay
            }
            return
        }
        queuedTarget = null
        delaySeconds = 0.0
        target = next
        resetSolver()
    }

    private fun resetSolver() {
        from = position
        initialVelocity = velocity
        elapsed = 0.0
        moving = true
    }

    fun update(deltaMillis: Double) {
        if (!moving && queuedTarget == null) return
        // Same 100ms suspension cap as AMLL's MAX_FRAME_DELTA.
        val dt = deltaMillis.coerceIn(0.0, 100.0) / 1000.0
        val pending = queuedTarget
        if (pending != null && delaySeconds <= dt) {
            // Retarget at the actual deadline, not the end of this frame.
            // The remaining frame time belongs to the new, velocity-continuous solver.
            val beforeTarget = delaySeconds.coerceAtLeast(0.0)
            advanceSolver(beforeTarget)
            setTargetPosition(pending.toFloat())
            advanceSolver(dt - beforeTarget)
        } else {
            advanceSolver(dt)
            if (pending != null) delaySeconds -= dt
        }
        val acceleration = -stiffness * (position - target) -
            2.0 * dampingRatio.coerceAtMost(1.0) * sqrt(stiffness) * velocity
        if (queuedTarget == null && abs(target - position) < 0.01 &&
            abs(velocity) < 0.01 && abs(acceleration) < 0.01
        ) snapTo(target.toFloat())
    }

    private fun advanceSolver(dt: Double) {
        if (!moving || dt <= 0.0) return
        elapsed += dt
        val omega = sqrt(stiffness)
        val delta = target - from
        // AMLL deliberately uses the critical solution for damping ratios >= 1.
        if (dampingRatio >= 1.0) {
            val leftover = omega * delta - initialVelocity
            val decay = exp(-omega * elapsed)
            val displacement = delta + elapsed * leftover
            position = target - displacement * decay
            velocity = (omega * displacement - leftover) * decay
        } else {
            val damping = dampingRatio * omega
            val frequency = omega * sqrt(1.0 - dampingRatio * dampingRatio)
            val leftover = (damping * delta - initialVelocity) / frequency
            val cosine = cos(frequency * elapsed)
            val sine = sin(frequency * elapsed)
            val displacement = delta * cosine + leftover * sine
            val derivative = frequency * (-delta * sine + leftover * cosine)
            val decay = exp(-damping * elapsed)
            position = target - displacement * decay
            velocity = (damping * displacement - derivative) * decay
        }
    }
}
