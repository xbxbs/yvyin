package com.example.xuebimc

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// Compiled with LocalMusicRepository.kt: exercises the actual production image downloader.
// All traffic stays on a local fixture; only this test temporarily changes the global cookie jar.
fun main() = runBlocking {
    val original = CookieHandler.getDefault()
    val requests = ConcurrentLinkedQueue<Pair<String?, String?>>()
    val slowStarted = CountDownLatch(1)
    val slowRelease = CountDownLatch(1)
    val png = ByteArrayOutputStream().also {
        check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it))
    }.toByteArray()
    val executor = Executors.newCachedThreadPool { Thread(it, "artwork-cookie-fixture").apply { isDaemon = true } }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = executor
        createContext("/") { exchange ->
            requests.add(exchange.requestHeaders.getFirst("Cookie") to exchange.requestHeaders.getFirst("Authorization"))
            try {
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.responseHeaders.add("Set-Cookie", "cover_response=fixture; Path=/")
                when (exchange.requestURI.path) {
                    "/redirect", "/loop", "/scheme" -> {
                        exchange.responseHeaders.add("Location", when (exchange.requestURI.path) {
                            "/redirect" -> "/cover"
                            "/loop" -> "/loop"
                            else -> "file:///not-a-network-image"
                        })
                        exchange.sendResponseHeaders(302, -1)
                    }
                    "/oversize" -> exchange.sendResponseHeaders(200, 2L * 1024 * 1024 + 1)
                    "/chunked" -> {
                        exchange.sendResponseHeaders(200, 0)
                        val chunk = ByteArray(8192)
                        repeat(257) { exchange.responseBody.write(chunk) }
                    }
                    "/cancel" -> {
                        exchange.sendResponseHeaders(200, png.size.toLong())
                        exchange.responseBody.write(png, 0, 1)
                        exchange.responseBody.flush()
                        slowStarted.countDown()
                        slowRelease.await(5, TimeUnit.SECONDS)
                    }
                    else -> {
                        exchange.sendResponseHeaders(200, png.size.toLong())
                        exchange.responseBody.write(png)
                    }
                }
            } catch (_: IOException) {
                // Expected when a bounded/cancelled client closes its body early.
            } finally {
                exchange.close()
            }
        }
        start()
    }
    val base = "http://127.0.0.1:${server.address.port}"
    suspend fun loaded(path: String = "/cover") {
        val bytes = RemoteArtworkHttp.download(base + path) ?: error("Unexpected download timeout")
        check(bytes.contentEquals(png))
        check(ImageIO.read(bytes.inputStream()).width == 2)
    }
    suspend fun rejected(path: String) {
        check(runCatching { RemoteArtworkHttp.download(base + path) }.exceptionOrNull() is IOException) {
            "Expected HTTP safety rejection for $path"
        }
    }
    try {
        withTimeout(20_000L) {
            CookieHandler.setDefault(null)
            loaded()
            val manager = CountingArtworkCookies()
            val cookie = HttpCookie("preexisting_global", "fixture-only").apply { path = "/"; version = 0 }
            manager.cookieStore.add(URI(base), cookie)
            CookieHandler.setDefault(manager)
            check(manager.cookieStore.get(URI(base)).single().name == cookie.name)
            repeat(2) { loaded() }
            loaded("/redirect")
            check(CookieHandler.getDefault() === manager)
            check(manager.reads.get() == 0 && manager.writes.get() == 0) { "Downloader consulted global cookies" }
            check(manager.cookieStore.cookies.map { it.name } == listOf("preexisting_global")) { "Response cookie leaked into global jar" }
            check(requests.all { (cookieHeader, authorization) -> cookieHeader == null && authorization == null })
            println("PASS: image bytes + decode before/after CookieManager; global cookie reads=0 writes=0")
            println("PASS: seeded global Cookie never sent; Set-Cookie never retained across repeat/redirect requests")

            rejected("/loop")
            rejected("/scheme")
            rejected("/oversize")
            rejected("/chunked")
            val cancelled = async { RemoteArtworkHttp.download("$base/cancel") }
            check(withContext(Dispatchers.IO) { slowStarted.await(2, TimeUnit.SECONDS) })
            val start = System.nanoTime()
            cancelled.cancelAndJoin()
            check(cancelled.isCancelled && (System.nanoTime() - start) / 1_000_000 < 1_500L)
            loaded()
            check(manager.reads.get() == 0 && manager.writes.get() == 0)
            check(requests.all { it.first == null && it.second == null })
            println("PASS: redirect/scheme/declared+streamed size limits; active body cancellation; subsequent image loads")
        }
    } finally {
        slowRelease.countDown()
        server.stop(0)
        executor.shutdownNow()
        CookieHandler.setDefault(original)
    }
    check(CookieHandler.getDefault() === original)
    println("PASS: test restored original global CookieHandler")
}

private class CountingArtworkCookies : CookieManager(null, CookiePolicy.ACCEPT_ALL) {
    val reads = AtomicInteger()
    val writes = AtomicInteger()
    override fun get(uri: URI, requestHeaders: MutableMap<String, MutableList<String>>): MutableMap<String, MutableList<String>> {
        reads.incrementAndGet()
        return super.get(uri, requestHeaders)
    }
    override fun put(uri: URI, responseHeaders: MutableMap<String, MutableList<String>>) {
        writes.incrementAndGet()
        super.put(uri, responseHeaders)
    }
}
