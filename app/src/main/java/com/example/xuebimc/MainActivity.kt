package com.example.xuebimc

import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var frameRateMonitor: FrameRateMonitor
    private lateinit var refreshRatePreferences: SharedPreferences
    private val refreshRatePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == AppPreferences.KEY_PREFER_HIGH_REFRESH) requestHighRefreshRate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshRatePreferences = getSharedPreferences(AppPreferences.PREF_NAME, MODE_PRIVATE)
        refreshRatePreferences.registerOnSharedPreferenceChangeListener(refreshRatePreferenceListener)
        requestHighRefreshRate()
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val playback = PlaybackStore.get(applicationContext)
        playback.trackResolver = OnlineMusicRepository(applicationContext)::resolvePlayableTrack
        frameRateMonitor = FrameRateMonitor(window)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides PressFadeIndication) {
                AppContextMenuHost {
                LocalMusicApp(
                    playback = playback,
                    frameRateMonitor = frameRateMonitor,
                    onBluetoothSettings = {
                        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    },
                    outputName = ::currentOutput,
                    onImmersiveChange = { immersive ->
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            if (immersive) hide(WindowInsetsCompat.Type.statusBars())
                            else show(WindowInsetsCompat.Type.statusBars())
                        }
                    },
                )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestHighRefreshRate()
        if (::frameRateMonitor.isInitialized) frameRateMonitor.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) requestHighRefreshRate()
    }

    @Suppress("DEPRECATION")
    private fun requestHighRefreshRate() {
        if (!::refreshRatePreferences.isInitialized || isDestroyed) return
        if (!refreshRatePreferences.getBoolean(AppPreferences.KEY_PREFER_HIGH_REFRESH, true)) {
            // Zero releases both window hints to the system, even when display is unavailable.
            val attributes = window.attributes
            if (attributes.preferredDisplayModeId != 0 || attributes.preferredRefreshRate != 0f) {
                attributes.preferredDisplayModeId = 0
                attributes.preferredRefreshRate = 0f
                window.attributes = attributes
            }
            return
        }
        val screen = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        screen ?: return
        val mode = screen.mode
        val candidates = screen.supportedModes.filter {
            it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight
        }
        val preferred = sequenceOf(120f, 90f, 60f).mapNotNull { target ->
            candidates.filter { abs(it.refreshRate - target) <= 2f }.minByOrNull { abs(it.refreshRate - target) }
        }.firstOrNull() ?: mode
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId != preferred.modeId || abs(attributes.preferredRefreshRate - preferred.refreshRate) > 0.1f) {
            attributes.preferredDisplayModeId = preferred.modeId
            attributes.preferredRefreshRate = preferred.refreshRate
            window.attributes = attributes
        }
    }

    override fun onStop() {
        if (::frameRateMonitor.isInitialized) frameRateMonitor.stop()
        // Playback belongs to the media service, not the visibility of this page.
        super.onStop()
    }

    override fun onDestroy() {
        if (::refreshRatePreferences.isInitialized) {
            refreshRatePreferences.unregisterOnSharedPreferenceChangeListener(refreshRatePreferenceListener)
        }
        if (::frameRateMonitor.isInitialized) frameRateMonitor.stop()
        super.onDestroy()
    }

    private fun currentOutput(): String {
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        val external = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return external?.productName?.toString() ?: "本机扬声器"
    }
}
