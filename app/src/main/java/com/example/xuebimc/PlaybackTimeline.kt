package com.example.xuebimc

/**
 * Audio position samples are coarse. Correct clock speed, not clock position,
 * so a sample cannot rewind a lyric across a word/line boundary. Only an
 * explicit seek or playback restart resets the timeline.
 */
internal class PlaybackTimeline {
    private var anchorPosition = 0.0
    private var anchorRealtime = 0L
    private var speed = 1.0

    fun reset(positionMs: Long, realtimeMs: Long) {
        anchorPosition = positionMs.toDouble()
        anchorRealtime = realtimeMs
        speed = 1.0
    }

    private fun precisePosition(realtimeMs: Long): Double =
        anchorPosition + (realtimeMs - anchorRealtime).coerceAtLeast(0L) * speed

    fun positionAt(realtimeMs: Long): Long = precisePosition(realtimeMs).toLong()

    fun synchronize(sampleMs: Long, realtimeMs: Long) {
        val predicted = precisePosition(realtimeMs)
        anchorPosition = predicted
        anchorRealtime = realtimeMs
        speed = (1.0 + (sampleMs - predicted) / 500.0).coerceIn(0.9, 1.1)
    }
}
