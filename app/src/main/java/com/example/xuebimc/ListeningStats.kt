package com.example.xuebimc

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

data class ListeningStatsSnapshot(
    val playsByTrack: Map<String, Int> = emptyMap(),
    val secondsByDay: Map<Long, Long> = emptyMap(),
)

/** Small, throttled local listening history. It never writes on the playback frame path. */
class ListeningStats(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("listening_stats", Context.MODE_PRIVATE)
    private val lock = Any()
    private val plays = linkedMapOf<String, Int>()
    private val seconds = linkedMapOf<Long, Long>()
    private val mutable = MutableStateFlow(ListeningStatsSnapshot())
    val snapshot: StateFlow<ListeningStatsSnapshot> = mutable.asStateFlow()
    private var sessionKey: String? = null
    private var sessionMs = 0L
    private var sessionCounted = false
    private var pendingMs = 0L
    private var lastPersistMs = 0L

    init {
        synchronized(lock) {
            readObject(prefs.getString(KEY_PLAYS, null)).forEach { (k, v) -> plays[k] = v.toIntOrNull() ?: 0 }
            readObject(prefs.getString(KEY_SECONDS, null)).forEach { (k, v) -> seconds[k.toLongOrNull() ?: return@forEach] = v.toLong() }
            publish()
        }
    }

    fun onTrackStarted(track: Track?) = synchronized(lock) {
        val key = track?.stableKey
        if (key != sessionKey) {
            sessionKey = key
            sessionMs = 0L
            sessionCounted = false
        }
    }

    fun recordProgress(track: Track?, deltaMs: Long, playing: Boolean) {
        if (!playing || track == null || deltaMs <= 0L) return
        synchronized(lock) {
            onTrackStarted(track)
            val delta = deltaMs.coerceAtMost(2_000L)
            sessionMs += delta
            val day = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
            seconds[day] = (seconds[day] ?: 0L) + delta / 1000L
            pendingMs += delta
            if (!sessionCounted && sessionMs >= PLAY_COUNT_THRESHOLD_MS) {
                plays[track.stableKey] = (plays[track.stableKey] ?: 0) + 1
                sessionCounted = true
            }
            publish()
            if (pendingMs >= PERSIST_INTERVAL_MS) persistLocked()
        }
    }

    fun flush() = synchronized(lock) { persistLocked() }

    fun topTracks(limit: Int = 5): List<Pair<String, Int>> = synchronized(lock) {
        plays.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    fun minutesForDay(epochDay: Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()): Long =
        synchronized(lock) { (seconds[epochDay] ?: 0L) / 60L }

    private fun publish() {
        mutable.value = ListeningStatsSnapshot(plays.toMap(), seconds.toMap())
    }

    private fun persistLocked() {
        prefs.edit().putString(KEY_PLAYS, JSONObject(plays as Map<*, *>).toString())
            .putString(KEY_SECONDS, JSONObject(seconds.mapKeys { it.key.toString() } as Map<*, *>).toString())
            .apply()
        pendingMs = 0L
        lastPersistMs = android.os.SystemClock.elapsedRealtime()
    }

    private fun readObject(raw: String?): Map<String, String> = runCatching {
        val json = JSONObject(raw ?: "{}")
        json.keys().asSequence().associateWith { json.optString(it, "0") }
    }.getOrDefault(emptyMap())

    companion object {
        private const val KEY_PLAYS = "plays"
        private const val KEY_SECONDS = "seconds"
        private const val PLAY_COUNT_THRESHOLD_MS = 30_000L
        private const val PERSIST_INTERVAL_MS = 30_000L
    }
}
