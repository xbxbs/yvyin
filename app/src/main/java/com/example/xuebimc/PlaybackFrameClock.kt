package com.example.xuebimc

/** Chooses a single position publisher while the UI frame clock is alive. Main-thread only. */
internal class PlaybackFrameClock(private val leaseMs: Long = 250L) {
    private var owner: Any? = null
    private var lastFrameReceiptMs = 0L

    fun onFrame(token: Any, receivedAtMs: Long) {
        owner = token
        lastFrameReceiptMs = receivedAtMs
    }

    fun serviceMayPublish(receivedAtMs: Long): Boolean {
        if (owner == null) return true
        val age = receivedAtMs - lastFrameReceiptMs
        return age < 0L || age >= leaseMs
    }

    fun release(token: Any) {
        // Activity recreation can overlap: an old activity cannot release its successor's clock.
        if (owner === token) owner = null
    }
}
