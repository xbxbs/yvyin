package com.example.xuebimc

import android.annotation.TargetApi
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Counts completed hardware frames of the player window, not vsync callbacks. */
internal class FrameRateMonitor(private val window: Window) {
    enum class Status { WAITING, MEASURED, IDLE, INCOMPLETE, UNSUPPORTED, UNAVAILABLE }
    enum class Scene { PLAYER, LYRICS, PANEL }

    data class Snapshot(
        val refreshRateHz: Float? = null,
        val framesPerSecond: Double? = null,
        val status: Status = Status.WAITING,
        val missedSamples: Long = 0L,
        val lastFramesPerSecond: Double? = null,
        val lastSampleAgeSeconds: Long? = null,
        val scene: Scene = Scene.PLAYER,
    )

    var snapshot by mutableStateOf(Snapshot())
        private set
    var beforeInfoSnapshot by mutableStateOf<Snapshot?>(null)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var scene = Scene.PLAYER
    private var hardwareFrames: HardwareFrames? = null
    private var lastFps: Double? = null
    private var lastSampleNs: Long? = null
    private val publishTask = object : Runnable {
        override fun run() {
            if (!running) return
            publish()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    // Lifecycle methods and snapshot publication run on the main thread.
    fun start() {
        if (running) return
        running = true
        hardwareFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            HardwareFrames(window).takeIf { it.start() }
        } else null
        snapshot = snapshot.copy(
            refreshRateHz = reportedHz(),
            framesPerSecond = null,
            status = unavailableStatus() ?: Status.WAITING,
            missedSamples = 0L,
            lastSampleAgeSeconds = sampleAgeSeconds(System.nanoTime()),
        )
        mainHandler.postDelayed(publishTask, 1_000L)
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(publishTask)
        hardwareFrames?.close()
        hardwareFrames = null
    }

    fun setScene(next: Scene) {
        if (scene == next) return
        scene = next
        hardwareFrames?.resetWindow()
        lastFps = null
        lastSampleNs = null
        snapshot = Snapshot(
            refreshRateHz = reportedHz(),
            status = unavailableStatus() ?: Status.WAITING,
            scene = scene,
        )
        // A reported window must belong entirely to one page / panel state.
        if (running) {
            mainHandler.removeCallbacks(publishTask)
            mainHandler.postDelayed(publishTask, 1_000L)
        }
    }

    fun captureBeforeInfo(lyricsVisible: Boolean) {
        val expected = if (lyricsVisible) Scene.LYRICS else Scene.PLAYER
        beforeInfoSnapshot = if (snapshot.scene == expected) snapshot else Snapshot(
            refreshRateHz = reportedHz(),
            status = Status.WAITING,
            scene = expected,
        )
        // Freeze the pre-open reading before opening/dimming the dialog window.
        setScene(Scene.PANEL)
    }

    private fun reportedHz(): Float? = window.decorView.display?.refreshRate
        ?.takeIf { it.isFinite() && it > 0f }

    private fun unavailableStatus(): Status? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N -> Status.UNSUPPORTED
        hardwareFrames == null -> Status.UNAVAILABLE
        else -> null
    }

    private fun sampleAgeSeconds(nowNs: Long): Long? = lastSampleNs?.let {
        ((nowNs - it) / 1_000_000_000L).coerceAtLeast(0L)
    }

    private fun publish() {
        val sample = hardwareFrames?.sample()
        val nowNs = System.nanoTime()
        sample?.framesPerSecond?.let {
            lastFps = it
            lastSampleNs = nowNs
        }
        snapshot = Snapshot(
            refreshRateHz = reportedHz(),
            framesPerSecond = sample?.framesPerSecond,
            status = sample?.status ?: unavailableStatus() ?: Status.WAITING,
            missedSamples = sample?.missedSamples ?: 0L,
            lastFramesPerSecond = lastFps,
            lastSampleAgeSeconds = sampleAgeSeconds(nowNs),
            scene = scene,
        )
    }

    private data class Sample(
        val framesPerSecond: Double?,
        val status: Status,
        val missedSamples: Long,
    )

    @TargetApi(Build.VERSION_CODES.N)
    private class HardwareFrames(private val window: Window) {
        private val lock = Any()
        private val thread = HandlerThread("PlayerFrameMetrics").apply { start() }
        private val handler = Handler(thread.looper)
        private var listening = false
        private var closed = false
        private var windowStartNs = System.nanoTime()
        private var frameCount = 0L
        private var missedSamples = 0L
        private var hasSeenFrame = false

        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            val receivedNs = System.nanoTime()
            // TOTAL_DURATION starts at intended vsync, not at callback delivery.
            // API 24/25 expose no frame timestamps, so use completed-callback time.
            val completedNs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intendedNs = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                val durationNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (intendedNs > 0L && durationNs >= 0L && intendedNs <= receivedNs &&
                    durationNs <= receivedNs - intendedNs
                ) intendedNs + durationNs else null
            } else receivedNs
            synchronized(lock) {
                if (!closed) {
                    hasSeenFrame = true
                    // Dropped reports are missing samples, not known rendered frames.
                    missedSamples += dropped.coerceAtLeast(0).toLong()
                    if (completedNs == null || completedNs < windowStartNs) {
                        missedSamples++
                    } else {
                        frameCount++
                    }
                }
            }
        }

        fun start(): Boolean = try {
            window.addOnFrameMetricsAvailableListener(listener, handler)
            listening = true
            true
        } catch (_: RuntimeException) {
            close()
            false
        }

        fun sample(): Sample = synchronized(lock) {
            val nowNs = System.nanoTime()
            val elapsedNs = nowNs - windowStartNs
            if (elapsedNs < 1_000_000_000L) {
                return@synchronized Sample(null, Status.WAITING, 0L)
            }
            val status = when {
                missedSamples > 0L -> Status.INCOMPLETE
                elapsedNs <= 0L || !hasSeenFrame -> Status.WAITING
                frameCount == 0L -> Status.IDLE
                else -> Status.MEASURED
            }
            // Count all frames in the real elapsed window, including quiet time.
            // A short burst (e.g. opening the menu) cannot masquerade as 120 FPS.
            val fps = if (status == Status.MEASURED) {
                frameCount * 1_000_000_000.0 / elapsedNs
            } else null
            val result = Sample(fps, status, missedSamples)
            windowStartNs = nowNs
            frameCount = 0L
            missedSamples = 0L
            result
        }

        fun resetWindow() = synchronized(lock) {
            windowStartNs = System.nanoTime()
            frameCount = 0L
            missedSamples = 0L
            hasSeenFrame = false
        }

        fun close() {
            synchronized(lock) { closed = true }
            try {
                if (listening) window.removeOnFrameMetricsAvailableListener(listener)
            } finally {
                listening = false
                handler.removeCallbacksAndMessages(null)
                thread.quitSafely()
            }
        }
    }
}
