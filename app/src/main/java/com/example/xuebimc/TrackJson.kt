package com.example.xuebimc

import android.net.Uri
import org.json.JSONObject

/** Playlist-safe track persistence: lyrics, request headers and temporary online URLs are omitted. */
object TrackJson {
    fun trackToJson(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id)
        // Online audio URLs are normally short lived. Keep provider IDs and let the resolver obtain one.
        put("uri", if (track.isOnline) JSONObject.NULL else track.uri.toString())
        put("title", track.title)
        put("artist", track.artist)
        put("album", track.album)
        put("durationMs", track.durationMs)
        put("albumId", track.albumId)
        put("displayName", track.displayName)
        putNullable("mimeType", track.mimeType)
        put("sizeBytes", track.sizeBytes)
        putNullable("relativePath", track.relativePath)
        put("sampleRate", track.sampleRate)
        put("bits", track.bits)
        put("isOnline", track.isOnline)
        putNullable("sourceId", track.sourceId)
        putNullable("sourceTrackId", track.sourceTrackId)
        putNullable("artworkUri", track.artworkUri?.toString())
        putNullable("codecMimeType", track.codecMimeType)
        putNullable("genre", track.genre)
        put("bitrate", track.bitrate)
    }

    fun fromJson(json: JSONObject): Track {
        val online = json.optBoolean("isOnline", false)
        val storedUri = json.nullableString("uri")
        return Track(
            id = json.optLong("id", 0L),
            // Uri.EMPTY deliberately marks an online item whose URL must be re-resolved.
            uri = if (online) Uri.EMPTY else storedUri?.let(Uri::parse) ?: Uri.EMPTY,
            title = json.optString("title", ""),
            artist = json.optString("artist", ""),
            album = json.optString("album", ""),
            durationMs = json.optLong("durationMs", 0L),
            albumId = json.optLong("albumId", 0L),
            displayName = json.optString("displayName", ""),
            mimeType = json.nullableString("mimeType"),
            sizeBytes = json.optLong("sizeBytes", 0L),
            relativePath = json.nullableString("relativePath"),
            sampleRate = json.optInt("sampleRate", 0),
            bits = json.optInt("bits", 0),
            isOnline = online,
            sourceId = json.nullableString("sourceId"),
            sourceTrackId = json.nullableString("sourceTrackId"),
            artworkUri = json.nullableString("artworkUri")?.let(Uri::parse),
            codecMimeType = json.nullableString("codecMimeType"),
            genre = json.nullableString("genre"),
            bitrate = json.optLong("bitrate", 0L).coerceAtLeast(0L),
        )
    }
}

fun trackToJson(track: Track): JSONObject = TrackJson.trackToJson(track)

fun trackFromJson(json: JSONObject): Track = TrackJson.fromJson(json)

internal fun JSONObject.putNullable(name: String, value: String?) {
    put(name, value ?: JSONObject.NULL)
}

internal fun JSONObject.nullableString(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null
