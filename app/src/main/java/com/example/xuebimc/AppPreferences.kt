package com.example.xuebimc

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Requested resolver levels, not a claim about the quality of the returned audio. */
enum class OnlinePlaybackQuality(val level: String, val label: String) {
    Standard("standard", "标准"),
    High("exhigh", "高品质"),
    Lossless("lossless", "无损"),
    HiRes("hires", "Hi-Res"),
    ;

    companion object {
        fun fromLevel(level: String?): OnlinePlaybackQuality? =
            entries.firstOrNull { it.level == level?.trim()?.lowercase(Locale.ROOT) }
    }
}

data class AppPreferenceValues(
    val animatedBackground: Boolean = true,
    val preferHighRefresh: Boolean = true,
    val defaultOnlineSource: String = "kw",
    val onlineQuality: String = "standard",
    val downloadTreeUri: String? = null,
    val downloadFolderName: String = "音乐/余音",
    val autoOpenPlayer: Boolean = true,
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

    fun setAutoOpenPlayer(value: Boolean) = update {
        putBoolean(KEY_AUTO_OPEN_PLAYER, value)
    }

    /** The source's published levels must also be checked by the UI and resolver. */
    fun setOnlineQuality(value: String) {
        val quality = requireNotNull(OnlinePlaybackQuality.fromLevel(value)) { "不支持的在线播放音质" }
        update { putString(KEY_ONLINE_QUALITY, quality.level) }
    }

    /** URI permission is acquired by the folder picker, not by this preferences wrapper. */
    fun setDownloadFolder(uri: String?, name: String) {
        val treeUri = uri?.trim()?.takeIf { it.isNotEmpty() }
        require(treeUri == null || treeUri.startsWith("content://")) { "请选择有效的下载文件夹" }
        update {
            if (treeUri == null) remove(KEY_DOWNLOAD_TREE_URI) else putString(KEY_DOWNLOAD_TREE_URI, treeUri)
            putString(KEY_DOWNLOAD_FOLDER_NAME,
                if (treeUri == null) DEFAULT_DOWNLOAD_FOLDER else name.trim().ifBlank { DEFAULT_DOWNLOAD_FOLDER })
        }
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
        onlineQuality = preferences.getString(KEY_ONLINE_QUALITY, "standard") ?: "standard",
        downloadTreeUri = preferences.getString(KEY_DOWNLOAD_TREE_URI, null)?.takeIf { it.isNotBlank() },
        downloadFolderName = preferences.getString(KEY_DOWNLOAD_FOLDER_NAME, DEFAULT_DOWNLOAD_FOLDER)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_DOWNLOAD_FOLDER,
        autoOpenPlayer = preferences.getBoolean(KEY_AUTO_OPEN_PLAYER, true),
    )

    companion object {
        const val PREF_NAME = "app_preferences"
        const val KEY_ANIMATED_BACKGROUND = "animated_background"
        const val KEY_PREFER_HIGH_REFRESH = "prefer_high_refresh"
        const val KEY_DEFAULT_ONLINE_SOURCE = "default_online_source"
        const val KEY_ONLINE_QUALITY = "online_quality"
        const val KEY_DOWNLOAD_TREE_URI = "download_tree_uri"
        const val KEY_DOWNLOAD_FOLDER_NAME = "download_folder_name"
        const val KEY_AUTO_OPEN_PLAYER = "auto_open_player"
        const val DEFAULT_DOWNLOAD_FOLDER = "音乐/余音"
        val KEYS: Set<String> = setOf(
            KEY_ANIMATED_BACKGROUND, KEY_PREFER_HIGH_REFRESH, KEY_DEFAULT_ONLINE_SOURCE,
            KEY_ONLINE_QUALITY, KEY_DOWNLOAD_TREE_URI, KEY_DOWNLOAD_FOLDER_NAME, KEY_AUTO_OPEN_PLAYER,
        )
    }
}
