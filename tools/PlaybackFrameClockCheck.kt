package com.example.xuebimc

// Standalone JVM check: compile with PlaybackFrameClock.kt and PlaybackTimeline.kt only.
fun main() {
    val a = Any()
    val b = Any()
    val gate = PlaybackFrameClock()
    check(gate.serviceMayPublish(0))
    gate.onFrame(a, 0)
    check(!gate.serviceMayPublish(12))
    check(gate.serviceMayPublish(250))
    gate.onFrame(b, 260)
    gate.release(a)
    check(!gate.serviceMayPublish(261))
    gate.release(b)
    check(gate.serviceMayPublish(262))

    var checkedFrames = 0
    for (hz in listOf(60, 90, 120)) {
        val clock = PlaybackFrameClock()
        val timeline = PlaybackTimeline().apply { reset(5_000, 0) }
        val token = Any()
        clock.onFrame(token, 0)
        var published = 5_000L
        var lastServiceBucket = 0L
        for (frame in 1..(hz * 10)) {
            val vsyncMs = frame * 1_000L / hz
            val receivedAt = vsyncMs + 4L
            val serviceBucket = receivedAt / 1_000L
            if (serviceBucket != lastServiceBucket) {
                // Service message reaches the main looper before a slightly late frame callback.
                if (clock.serviceMayPublish(receivedAt)) published = timeline.positionAt(receivedAt)
                lastServiceBucket = serviceBucket
            }
            clock.onFrame(token, receivedAt)
            published = maxOf(published, timeline.positionAt(vsyncMs))
            check(published == 5_000L + vsyncMs) { "Uneven frame at $hz Hz / $frame: $published" }
            checkedFrames++
        }
        clock.release(token)
        check(clock.serviceMayPublish(10_010L))
        published = timeline.positionAt(10_010L)
        check(published == 15_010L)
        // Explicit seek/restart reset the public position as well as the timeline.
        timeline.reset(100L, 10_020L)
        published = 100L
        clock.onFrame(token, 10_024L)
        published = maxOf(published, timeline.positionAt(10_028L))
        check(published == 108L)
        timeline.reset(0L, 10_040L)
        published = 0L
        published = maxOf(published, timeline.positionAt(10_048L))
        check(published == 8L)
    }
    println("PASS: $checkedFrames foreground frames, service handoff, owner replacement, seek/restart")
}
