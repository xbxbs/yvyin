package com.example.xuebimc

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Offline response-header/cancellation check; no remote music or audio body is requested. */
fun main() = runBlocking {
    val rules = DownloadPreviewRules
    check(rules.totalBytes(200, 5_000_000, null, null) == 5_000_000L)
    check(rules.totalBytes(206, 1, "bytes 0-0/58499137", "identity") == 58_499_137L)
    check(rules.totalBytes(206, 1, "bytes 0-0/*", null) == null)
    check(rules.totalBytes(206, 1, "bytes 0-0/0", null) == null)
    check(rules.totalBytes(206, 1, "bytes 5-1/42", null) == null)
    check(rules.totalBytes(206, 1, "bytes 0-0/99999999999999999999999", null) == null)
    check(rules.totalBytes(200, 200, null, "gzip") == null)
    check(rules.totalBytes(200, -1, null, null) == null)
    check(rules.totalBytes(200, 0, null, null) == null)
    check(rules.declaredMime("audio/FLAC; charset=utf-8") == "audio/flac")
    check(rules.declaredMime("application/octet-stream") == null)

    val wire = PreviewWire()
    URL.setURLStreamHandlerFactory { protocol ->
        check(protocol == "https")
        object : URLStreamHandler() {
            override fun openConnection(url: URL) = wire.connection(url)
        }
    }
    val url = "https://preview.example/file.flac"
    val requestHeaders = mapOf("User-Agent" to "Fixture", "Referer" to "https://preview.example/",
        "Authorization" to "must-not-forward", "Cookie" to "must-not-forward", "Range" to "bytes=10-")
    wire.reply(200, "audio/flac", 58_499_137)
    val complete = OnlineHttp.downloadHeaders(url, requestHeaders)
    check(complete == DownloadResponseHeaders("audio/flac", 58_499_137L))
    check(wire.methods == listOf("HEAD"))
    check(wire.connections.last().getRequestProperty("User-Agent") == "Fixture")
    check(wire.connections.last().getRequestProperty("Authorization") == null)
    check(wire.connections.last().getRequestProperty("Cookie") == null)
    check(wire.connections.last().getRequestProperty("Range") == null)
    check(wire.connections.last().getRequestProperty("Accept-Encoding") == "identity")

    wire.reply(200, "audio/mpeg", -1)
    check(OnlineHttp.downloadHeaders(url, emptyMap()).totalBytes == null)
    wire.reply(405)
    wire.reply(206, "audio/flac", 1, "bytes 0-0/58499137")
    check(OnlineHttp.downloadHeaders(url, emptyMap()).totalBytes == 58_499_137L)
    check(wire.methods.takeLast(2) == listOf("HEAD", "GET"))
    check(wire.connections.last().getRequestProperty("Range") == "bytes=0-0")

    // Some CDNs ignore Range: only inspect its headers and still never read the body.
    wire.reply(501)
    wire.reply(200, "audio/mpeg", 7_000_000)
    check(OnlineHttp.downloadHeaders(url, emptyMap()).totalBytes == 7_000_000L)
    for (status in listOf(401, 403, 412, 418, 429)) {
        val before = wire.methods.size
        wire.reply(status)
        val failure = runCatching { OnlineHttp.downloadHeaders(url, emptyMap()) }.exceptionOrNull()
        check(failure is SourceHttpException && failure.status == status)
        check(wire.methods.size == before + 1) { "Access denial retried with another method" }
    }
    wire.reply(302, location = "https://preview.example/redirected.flac")
    wire.reply(200, "audio/flac", 42)
    check(OnlineHttp.downloadHeaders(url, emptyMap()).totalBytes == 42L)
    check(wire.methods.takeLast(2) == listOf("HEAD", "HEAD"))

    val started = CompletableDeferred<Unit>()
    val unblock = CountDownLatch(1)
    wire.blocking = started to unblock
    wire.reply(200, "audio/flac", 42)
    val work = launch { OnlineHttp.downloadHeaders(url, emptyMap()) }
    withTimeout(3_000) { started.await() }
    withTimeout(3_000) { work.cancelAndJoin() }
    check(unblock.count == 0L) { "Cancellation did not disconnect the active connection" }
    check(wire.disconnected >= wire.connections.size)
    check(wire.bodyReads == 0)
    println("PASS: HEAD/Range sizes, unknown size, no audio-body read, denied links never retried, redirect validation, cancellation disconnect")
}

private class PreviewWire {
    data class Reply(val status: Int, val mime: String?, val length: Long, val range: String?, val location: String?)
    private val replies = ArrayDeque<Reply>()
    val methods = mutableListOf<String>()
    val connections = mutableListOf<HttpURLConnection>()
    var disconnected = 0
    var bodyReads = 0
    var blocking: Pair<CompletableDeferred<Unit>, CountDownLatch>? = null

    fun reply(status: Int, mime: String? = null, length: Long = -1, range: String? = null, location: String? = null) {
        replies.addLast(Reply(status, mime, length, range, location))
    }

    fun connection(url: URL): HttpURLConnection {
        check(url.host == "preview.example") { "Unexpected host; fixture never uses the network" }
        val response = replies.removeFirst()
        val blocker = blocking.also { blocking = null }
        return object : HttpURLConnection(url) {
            override fun getResponseCode(): Int {
                methods += requestMethod
                blocker?.let { (started, unblock) ->
                    started.complete(Unit)
                    check(unblock.await(5, TimeUnit.SECONDS)) { "Header check was not cancelled" }
                }
                return response.status
            }
            override fun getContentType() = response.mime
            override fun getContentLengthLong() = response.length
            override fun getContentEncoding(): String? = null
            override fun getHeaderField(name: String?): String? = when (name) {
                "Content-Range" -> response.range
                "Location" -> response.location
                else -> null
            }
            override fun getInputStream(): java.io.InputStream {
                bodyReads++
                error("A quality preflight must not read an audio body")
            }
            override fun connect() = Unit
            override fun disconnect() { disconnected++; blocker?.second?.countDown() }
            override fun usingProxy() = false
        }.also { connections += it }
    }
}
