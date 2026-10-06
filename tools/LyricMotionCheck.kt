package com.example.xuebimc

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

private fun close(actual: Double, expected: Double, epsilon: Double = 0.00001) {
    check(abs(actual - expected) <= epsilon) { "$actual != $expected" }
}

fun main() {
    val policy = LyricMotionPolicy
    val parameters = policy.lineSpring(800.0)
    val spring = AmllSpring(0f, parameters)
    spring.setTargetPosition(200f)
    spring.update(100.0)
    val omega = sqrt(parameters.stiffness.toDouble())
    close(spring.position, 200.0 - (200.0 + 0.1 * omega * 200.0) * exp(-omega * 0.1))

    // Queuing another line must keep the old animation alive during the delay.
    val beforeQueue = spring.position
    val velocityBeforeQueue = spring.velocity
    spring.setTargetPosition(400f, 80.0)
    close(spring.position, beforeQueue)
    close(spring.velocity, velocityBeforeQueue)
    spring.update(40.0)
    check(spring.position > beforeQueue)
    spring.update(40.0)
    val atRetarget = spring.position
    val velocityAtRetarget = spring.velocity
    spring.update(0.0)
    close(spring.position, atRetarget)
    close(spring.velocity, velocityAtRetarget)
    repeat(250) { spring.update(16.0) }
    close(spring.position, 400.0)

    // Seeking replaces delayed targets; an old queue cannot fire afterwards.
    spring.setTargetPosition(900f, 100.0)
    spring.updateParameters(policy.lineSpring(isSeeking = true))
    spring.setTargetPosition(-100f)
    repeat(300) { spring.update(16.0) }
    close(spring.position, -100.0)
    spring.setTargetPosition(600f, 100.0)
    spring.snapTo(50f)
    repeat(30) { spring.update(16.0) }
    close(spring.position, 50.0)

    // Frame subdivision gives the same analytic trajectory (60 / 120 Hz).
    val sixty = AmllSpring(100f, policy.scaleSpring())
    val oneTwenty = AmllSpring(100f, policy.scaleSpring())
    sixty.setTargetPosition(97f)
    oneTwenty.setTargetPosition(97f)
    repeat(30) { sixty.update(1000.0 / 60.0) }
    repeat(60) { oneTwenty.update(1000.0 / 120.0) }
    close(sixty.position, oneTwenty.position)
    close(sixty.velocity, oneTwenty.velocity)

    val delays = policy.staggerDelays(listOf(-20.0, 30.0, 90.0, 180.0), 1).lineDelayMillis
    close(delays[0], 0.0)
    close(delays[1], 0.0)
    close(delays[2], 50.0)
    close(delays[3], 50.0 + 50.0 / 1.05)

    // Touch browsing must not turn the depth effect off or brighten all rows.
    val neighbour = policy.blurLevel(1, 0, false, true, true)
    close(policy.blurLevel(1, 0, false, true, true, isTouchScrolled = true).toDouble(), neighbour.toDouble())
    check(neighbour > 2f)
    check(policy.blurLevel(4, 0, false, true, true) > neighbour)
    close(policy.blurLevel(0, 0, true, true, true, isTouchScrolled = true).toDouble(), 0.0)

    // Both actual clip boundaries are transparent, with a monotonic soft ramp.
    close(policy.edgeOpacity(0f, 700f, 64f, 96f).toDouble(), 0.0)
    close(policy.edgeOpacity(700f, 700f, 64f, 96f).toDouble(), 0.0)
    close(policy.edgeOpacity(300f, 700f, 64f, 96f).toDouble(), 1.0)
    check((0..64).zipWithNext().all { (a, b) ->
        policy.edgeOpacity(a.toFloat(), 700f, 64f, 96f) <= policy.edgeOpacity(b.toFloat(), 700f, 64f, 96f)
    })
    check((604..700).zipWithNext().all { (a, b) ->
        policy.edgeOpacity(a.toFloat(), 700f, 64f, 96f) >= policy.edgeOpacity(b.toFloat(), 700f, 64f, 96f)
    })

    val clock = PlaybackTimeline()
    clock.reset(1000L, 0L)
    close(clock.positionAt(200L).toDouble(), 1200.0)
    clock.synchronize(1150L, 200L)
    close(clock.positionAt(200L).toDouble(), 1200.0)
    check(clock.positionAt(216L) > 1200L)
    var last = clock.positionAt(216L)
    for (now in 232L..10000L step 16L) {
        if (now % 200L < 16L) clock.synchronize(1000L + now - 35L, now)
        val current = clock.positionAt(now)
        check(current >= last) { "clock rewound at $now" }
        last = current
    }
    check(abs(last - (1000L + 9992L - 35L)) < 20L)
    clock.reset(200L, 10000L)
    close(clock.positionAt(10000L).toDouble(), 200.0)
    println("PASS: analytic spring, queued retarget/seek, 60/120Hz, stagger, BOTH edges, monotonic playback clock")
}
