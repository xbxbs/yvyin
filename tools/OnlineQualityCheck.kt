package com.example.xuebimc

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Offline fixture: compile with the two production files, last app classes, android-all and JSON. */
fun main() = runBlocking {
    val stores = mutableMapOf<String, QualityPreferences>()
    val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            stores.getOrPut(name) { QualityPreferences() }.value
    }
    val preferences = AppPreferences(context)
    val mirror = AppPreferences(context)
    check(preferences.values.value == AppPreferenceValues())
    preferences.setAutoOpenPlayer(false)
    check(!mirror.values.value.autoOpenPlayer)
    check(mirror.values.value.animatedBackground && mirror.values.value.preferHighRefresh)
    preferences.setDownloadFolder("content://fixture/tree/music", "我的音乐")
    check(mirror.values.value.downloadTreeUri == "content://fixture/tree/music")
    check(mirror.values.value.downloadFolderName == "我的音乐")
    preferences.setDownloadFolder(null, "ignored")
    check(mirror.values.value.downloadTreeUri == null && mirror.values.value.downloadFolderName == "音乐/余音")
    check(runCatching { preferences.setOnlineQuality("master") }.isFailure)

    val wire = QualityWire()
    URL.setURLStreamHandlerFactory { protocol ->
        check(protocol == "https") { "Only HTTPS fixture traffic is allowed" }
        object : URLStreamHandler() {
            override fun openConnection(url: URL) = wire.connection(url)
        }
    }
    val repository = OnlineMusicRepository(context)
    // Preserve the existing single-argument function reference used by MainActivity/download.
    val resolve: suspend (Track) -> Track = repository::resolvePlayableTrack
    val sources = repository.loadSources().associateBy { it.id }
    check(sources.getValue("kw").supportedQualities.map { it.level } == listOf("standard", "exhigh", "lossless"))
    check(sources.getValue("tx").supportedQualities == sources.getValue("kw").supportedQualities)
    check(sources.getValue("kg").supportedQualities == OnlinePlaybackQuality.entries)
    check(sources.getValue("wy").supportedQualities == OnlinePlaybackQuality.entries)
    check(sources.getValue("bili").supportedQualities.isEmpty() && !sources.getValue("mg").enabled)
    check(publishedOnlineQualities(JSONArray("[\"master\",\"atmos\",\"bili192\",\"hires\",\"hires\"]")) == listOf(OnlinePlaybackQuality.HiRes))

    val track = Track(1L, Uri.parse("online://kg/fixture"), "Fixture", "Artist", "Album", 10_000L,
        isOnline = true, sourceId = "kg", sourceTrackId = "0123456789abcdef0123456789abcdef",
        codecMimeType = "audio/flac", bits = 24, sampleRate = 96_000, bitrate = 3_000_000)
    for (quality in OnlinePlaybackQuality.entries) {
        preferences.setOnlineQuality(quality.level)
        check(mirror.values.value.onlineQuality == quality.level)
        val ext = if (quality in listOf(OnlinePlaybackQuality.Lossless, OnlinePlaybackQuality.HiRes)) "flac" else "mp3"
        wire.reply(0, quality.level, ext)
        val result = resolve(track)
        val request = wire.requests.last()
        check(request.optString("level") == quality.level && request.optString("source") == "kg")
        check(request.optString("rid") == track.sourceTrackId)
        check(result.requestHeaders == mapOf("User-Agent" to "FixtureAudio", "Referer" to "https://audio.example/"))
        check(result.codecMimeType == null && result.sampleRate == 0 && result.bits == 0 && result.bitrate == 0L)
        check(repository.playbackEvidence(track)?.requestedLevel == quality.level)
        check(repository.playbackEvidence(track)?.reportedLevel == quality.level)
    }
    suspend fun rejected(label: String, expectedRequests: Int = 1, block: suspend () -> Unit) {
        val before = wire.requests.size
        val error = runCatching { block() }.exceptionOrNull() ?: error("Expected a quality failure")
        check(error is IOException && error.message.orEmpty().contains(label)) { "Missing target quality in error" }
        check(wire.requests.size - before == expectedRequests) { "Unexpected retry or silent fallback" }
    }
    preferences.setOnlineQuality("hires")
    rejected("Hi-Res", 0) { resolve(track.copy(sourceId = "tx", sourceTrackId = "validSongMid")) }
    wire.reply(0, "standard", "mp3")
    rejected("Hi-Res") { resolve(track) }
    wire.reply(0, "hires", "mp3")
    rejected("Hi-Res") { resolve(track) }
    wire.reply(403)
    rejected("Hi-Res") { resolve(track) }
    wire.reply(0, "hires", "flac", code = 1)
    rejected("Hi-Res") { resolve(track) }
    wire.reply(0, "hires", "flac", source = "wy")
    rejected("Hi-Res") { resolve(track) }
    check(repository.playbackEvidence(track) == null)
    // Connection fallback retries the SAME level on the second configured gateway.
    wire.reply(503)
    wire.reply(0, "hires", "flac")
    resolve(track)
    check(wire.requests.takeLast(2).all { it.optString("level") == "hires" })
    // URL-only success does not make up a reported level or measured Hi-Res metadata.
    wire.reply(0, null, "flac")
    resolve(track)
    check(repository.playbackEvidence(track)?.requestedLevel == "hires")
    check(repository.playbackEvidence(track)?.reportedLevel == null)
    val local = track.copy(isOnline = false)
    val before = wire.requests.size
    check(resolve(local) === local && wire.requests.size == before)
    println("PASS: preferences, source levels, all four real payloads, evidence/headers, no stale badge")
    println("PASS: unsupported level, downgrade, denial and identity conflict stop; connection retry keeps quality")
    println("OFFLINE: no provider/audio requests, no download function call, no full app build")
}

private class QualityWire {
    val requests = mutableListOf<JSONObject>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    fun reply(status: Int, level: String? = null, extension: String = "flac", code: Int = 0, source: String = "kg") {
        val data = JSONObject().put("url", "https://audio.example/fixture.$extension").put("source", source)
            .put("hash", "0123456789abcdef0123456789abcdef").put("level", level)
            .put("headers", JSONObject().put("User-Agent", "FixtureAudio").put("Referer", "https://audio.example/")
                .put("Cookie", "blocked-fixture").put("Authorization", "blocked-fixture"))
        replies.addLast((if (status == 0) 200 else status) to JSONObject().put("code", code).put("data", data).toString())
    }
    fun connection(url: URL): HttpURLConnection {
        check(url.host in setOf("13413.kstore.vip", "musicserver.haitangw.cc", "qt.haitangw.net"))
        return object : HttpURLConnection(url) {
            val sent = ByteArrayOutputStream()
            val response by lazy {
                if (url.host == "13413.kstore.vip") 200 to config else {
                    check(getRequestProperty("X-Request-Id").isNotBlank())
                    requests += JSONObject(sent.toString("UTF-8"))
                    replies.removeFirst()
                }
            }
            override fun getOutputStream() = sent
            override fun getResponseCode() = response.first
            override fun getInputStream() = ByteArrayInputStream(response.second.toByteArray())
            override fun getContentLengthLong() = response.second.toByteArray().size.toLong()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
        }
    }
    private val config = JSONObject().put("lines", JSONArray().apply {
        listOf("kw", "kg", "wy", "tx", "bili", "mg").forEach { source ->
            val prefix = if (source == "kw") "fetch" else source
            val levels = when (source) {
                "kg", "wy" -> listOf("standard", "exhigh", "lossless", "hires", "atmos")
                "kw", "tx" -> listOf("standard", "exhigh", "lossless", "master")
                "bili" -> listOf("bili192")
                else -> listOf("standard")
            }
            put(JSONObject().put("id", source).put("enabled", true)
                .put("searchApi", "${prefix}SearchMusic").put("detailApi", "${prefix}MusicDetail")
                .put("levels", JSONArray(levels)))
        }
    }).toString()
}

/** Minimal in-memory SharedPreferences with synchronous listener delivery, no Android disk/UI. */
private class QualityPreferences {
    private val data = mutableMapOf<String, Any>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    val value: SharedPreferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString", "getBoolean" -> data[args[0]] ?: args[1]
            "registerOnSharedPreferenceChangeListener" -> { listeners += args[0] as SharedPreferences.OnSharedPreferenceChangeListener; null }
            "edit" -> editor()
            else -> error("Unexpected preferences method ${method.name}")
        }
    } as SharedPreferences
    private fun editor(): SharedPreferences.Editor {
        val pending = linkedMapOf<String, Any?>()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString", "putBoolean" -> { pending[args[0] as String] = args[1]; proxy }
                "remove" -> { pending[args[0] as String] = null; proxy }
                "apply" -> {
                    pending.forEach { (key, stored) -> if (stored == null) data.remove(key) else data[key] = stored }
                    pending.keys.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(value, key) } }
                    null
                }
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}
