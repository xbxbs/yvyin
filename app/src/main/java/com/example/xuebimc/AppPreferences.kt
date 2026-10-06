package com.example.xuebimc

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppPreferenceValues(
    val animatedBackground: Boolean = true,
    val preferHighRefresh: Boolean = true,
    val defaultOnlineSource: String = "kw",
)

/** SharedPreferences is shared across instances; use that same monitor for each read/modify/publish. */
class AppPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val mutableValues = MutableStateFlow(synchronized(preferences) { readValues() })
    val values: StateFlow<AppPreferenceValues> = mutableValues.asStateFlow()

    // Keep a strong reference: SharedPreferences only retains listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in KEYS) synchronized(preferences) {
            mutableValues.value = readValues()
        }
    }

    init {
        synchronized(preferences) {
            preferences.registerOnSharedPreferenceChangeListener(listener)
            mutableValues.value = readValues()
        }
    }

    fun setAnimatedBackground(value: Boolean) = update {
        putBoolean(KEY_ANIMATED_BACKGROUND, value)
    }

    fun setPreferHighRefresh(value: Boolean) = update {
        putBoolean(KEY_PREFER_HIGH_REFRESH, value)
    }

    /** Availability is checked against the live source list by the caller, not a hard-coded catalog. */
    fun setDefaultOnlineSource(value: String) {
        val sourceId = value.trim().takeIf { it.isNotEmpty() } ?: return
        update { putString(KEY_DEFAULT_ONLINE_SOURCE, sourceId) }
    }

    private fun update(change: SharedPreferences.Editor.() -> Unit) {
        synchronized(preferences) {
            preferences.edit().apply(change).apply()
            // apply() publishes to memory synchronously and persists off the UI thread.
            mutableValues.value = readValues()
        }
    }

    private fun readValues() = AppPreferenceValues(
        animatedBackground = preferences.getBoolean(KEY_ANIMATED_BACKGROUND, true),
        preferHighRefresh = preferences.getBoolean(KEY_PREFER_HIGH_REFRESH, true),
        defaultOnlineSource = preferences.getString(KEY_DEFAULT_ONLINE_SOURCE, "kw")
            ?.takeIf { it.isNotBlank() } ?: "kw",
    )

    companion object {
        const val PREF_NAME = "app_preferences"
        const val KEY_ANIMATED_BACKGROUND = "animated_background"
        const val KEY_PREFER_HIGH_REFRESH = "prefer_high_refresh"
        const val KEY_DEFAULT_ONLINE_SOURCE = "default_online_source"
        val KEYS: Set<String> = setOf(KEY_ANIMATED_BACKGROUND, KEY_PREFER_HIGH_REFRESH, KEY_DEFAULT_ONLINE_SOURCE)
    }
}
