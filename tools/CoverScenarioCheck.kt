package com.example.xuebimc

import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Bounded, read-only integration probe against public Kugou metadata/artwork.
 * Compile with OnlineMusicRepository.kt, the last app classes, android.jar and coroutines.
 * Exercises the real OnlineHttp cancellation path, not Android bitmap decoding or Compose.
 * Never logs audio URLs, headers, cookies, private media or full provider responses.
 */
fun main() = runBlocking {
    val oldCookies = CookieHandler.getDefault()
    try {
        withTimeout(35_000L) {
            CookieHandler.setDefault(null)
            val searchUrl = "https://msearch.kugou.com/api/v3/search/song?format=json&page=1&pagesize=30&showtype=1&keyword=" +
                URLEncoder.encode("周杰伦", "UTF-8")
            val first = coverProbe(OnlineHttp.get(searchUrl))
            checkImage(first.second)

            // Mirrors MediaHTTPService.makeHTTPConnection(): merely starting online
            // playback installs a CookieManager, even with a completely empty cookie jar.
            val playbackCookies = CookieManager()
            CookieHandler.setDefault(playbackCookies)
            check(playbackCookies.cookieStore.cookies.isEmpty())
            val legacyGuardWouldReject = CookieHandler.getDefault() != null
            check(legacyGuardWouldReject)
            println("REPRO: legacy non-null CookieHandler guard rejects an empty playback cookie jar")

            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { OnlineHttp.get(searchUrl) }
            cancelled.cancelAndJoin()
            check(cancelled.isCancelled)
            val second = coverProbe(OnlineHttp.get(searchUrl))
            check(first.first == second.first) { "Provider changed the first track; repeat-search comparison is inconclusive" }
            checkImage(second.second)
            println("PASS: two real searches return same-provider artwork; both image requests return JPEG")
            println("PASS: cancelling OnlineHttp does not cancel or poison the subsequent request")
        }
    } finally {
        CookieHandler.setDefault(oldCookies)
    }
}

private fun coverProbe(json: String): Pair<String, String> {
    val hash = Regex("\"hash\"\\s*:\\s*\"([a-fA-F0-9]{32})\"").find(json)?.groupValues?.get(1)
        ?: error("Provider did not return a track hash")
    val raw = Regex("\"union_cover\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        ?: error("Provider did not return a public cover")
    val url = raw.replace("\\/", "/").replace("{size}", "300").replace("http://", "https://")
    check(URL(url).host.endsWith(".kugou.com")) { "Unexpected artwork provider" }
    return hash.lowercase() to url
}

private fun checkImage(value: String) {
    val connection = (URL(value).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 5_000
        instanceFollowRedirects = false
        setRequestProperty("Accept", "image/*")
        setRequestProperty("Accept-Encoding", "identity")
    }
    try {
        check(connection.responseCode == 200) { "Image response HTTP ${connection.responseCode}" }
        check(connection.contentType?.startsWith("image/") == true) { "Image response has wrong content type" }
        connection.inputStream.use { check(it.read() == 0xff && it.read() == 0xd8) { "Not a JPEG image" } }
    } finally {
        connection.disconnect()
    }
}
