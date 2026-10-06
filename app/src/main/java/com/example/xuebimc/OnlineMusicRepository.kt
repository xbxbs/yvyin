package com.example.xuebimc

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.text.Html
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OnlineSource(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val supportsPlayback: Boolean = false,
    val supportsDownload: Boolean = false,
    val description: String = "",
)

/** Resolver assertions, not independent proof of the audio or a replacement for search metadata. */
internal data class OnlinePlaybackEvidence(
    val reportedSource: String?,
    val reportedId: String?,
    val title: String?,
    val artist: String?,
    val audioHost: String,
)

internal data class OnlineSearchReceipt(val sourceId: String, val query: String, val tracks: List<Track>)

/** The remote JSON selects KNOWN adapters. It is never executable source code. */
class OnlineMusicRepository(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("online_library", Context.MODE_PRIVATE)
    private val artworkRequests = Semaphore(3)
    private val activeSearch = AtomicReference<Job?>()
    private val artworkCache = object : LinkedHashMap<String, Uri>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Uri>?): Boolean = size > 128
    }
    val builtInSources: List<OnlineSource> = listOf(
        OnlineSource("kw", "酷我", supportsPlayback = true, supportsDownload = true,
            description = "标准音质 · 以来源实际权限为准"),
        OnlineSource("kg", "酷狗", supportsPlayback = true, supportsDownload = true,
            description = "标准音质 · 以来源实际权限为准"),
        OnlineSource("wy", "网易云", supportsPlayback = true, supportsDownload = true,
            description = "标准音质 · 以来源实际权限为准"),
        OnlineSource("tx", "QQ 音乐", supportsPlayback = true, supportsDownload = true,
            description = "标准音质 · 以来源实际权限为准"),
        OnlineSource("bili", "哔哩哔哩", description = "仅搜索 · 公开接口可能受访问限制，未验证播放"),
        OnlineSource("mg", "咪咕", enabled = false, description = "需来源签名认证 · 未接入私有凭据"),
    )

    suspend fun loadSources(): List<OnlineSource> = withContext(Dispatchers.IO) {
        val text = try {
            OnlineHttp.get(CONFIG_URL, maxBytes = 128 * 1024).also {
                JSONObject(it).getJSONArray("lines")
                preferences.edit().putString("source_config", it).apply()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { preferences.getString("source_config", null) }
        if (text == null) return@withContext builtInSources
        val entries = runCatching { JSONObject(text).getJSONArray("lines") }.getOrNull() ?: return@withContext builtInSources
        builtInSources.map { source ->
            val entry = (0 until entries.length().coerceAtMost(50)).mapNotNull { entries.optJSONObject(it) }
                .firstOrNull { it.optString("id") == source.id }
            if (entry == null) source.copy(enabled = false) else source.copy(
                // A remote label never implies a new executable adapter or playback permission.
                enabled = source.enabled && entry.optBoolean("enabled", true) && entry.optString("searchApi") == SEARCH_ADAPTERS[source.id],
                supportsPlayback = source.supportsPlayback && entry.optString("detailApi") == DETAIL_ADAPTERS[source.id],
                supportsDownload = source.supportsDownload && entry.optString("detailApi") == DETAIL_ADAPTERS[source.id],
            )
        }
    }

    suspend fun search(sourceId: String, query: String): List<Track> = withContext(Dispatchers.IO) {
        // Own only this request's child job, never the caller's UI scope.
        val request = coroutineContext.job
        val previous = synchronized(activeSearch) {
            val old = activeSearch.getAndSet(request)
            latestSearchReceipt.value = null
            old
        }
        previous?.takeIf { it !== request }?.cancel()
        try {
            val keyword = query.trim().take(160)
            if (keyword.isBlank()) return@withContext emptyList()
            require(builtInSources.any { it.id == sourceId && it.enabled }) { "该音源未启用" }
            val tracks = when (sourceId) {
                "kw" -> searchKuwo(keyword)
                "kg" -> searchKugou(keyword)
                "wy" -> searchNetease(keyword)
                "tx" -> searchQq(keyword)
                "bili" -> searchBilibili(keyword)
                "mg" -> searchMigu(keyword)
                else -> emptyList()
            }
            val ranked = rankSearchResults(tracks.distinctBy { it.stableKey }, keyword)
            synchronized(activeSearch) {
                request.ensureActive()
                if (activeSearch.get() !== request) throw CancellationException("搜索请求已更新")
                latestSearchReceipt.value = OnlineSearchReceipt(sourceId, keyword, ranked)
            }
            ranked
        } finally {
            activeSearch.compareAndSet(request, null)
        }
    }

    private fun rankSearchResults(tracks: List<Track>, keyword: String): List<Track> {
        val wanted = keyword.lowercase(Locale.ROOT).trim()
        val versionSearch = VERSION_MARKERS.containsMatchIn(wanted)
        fun artistRank(track: Track): Int {
            val artist = track.artist.lowercase(Locale.ROOT).trim()
            return when {
                artist == wanted -> 0
                artist.split(ARTIST_SEPARATORS).any { it.trim() == wanted } -> 1
                else -> 2
            }
        }
        // These are ordering hints, NOT authenticity or copyright judgements. Keep all public results.
        // A request for a DJ/live/cover version retains the provider's ordering within each artist group.
        return tracks.sortedWith(compareBy<Track> { artistRank(it) }.thenBy {
            when {
                versionSearch -> 0
                it.durationMs in 1L until 60_000L -> 2
                VERSION_MARKERS.containsMatchIn(it.title) -> 1
                else -> 0
            }
        })
    }

    private suspend fun searchKuwo(keyword: String): List<Track> {
        val url = Uri.parse("https://search.kuwo.cn/r.s").buildUpon()
            .appendQueryParameter("all", keyword).appendQueryParameter("ft", "music")
            .appendQueryParameter("client", "kt").appendQueryParameter("pn", "0")
            .appendQueryParameter("rn", "30").appendQueryParameter("rformat", "json")
            .appendQueryParameter("encoding", "utf8").appendQueryParameter("newver", "1")
            .appendQueryParameter("mobi", "1").build().toString()
        // Android's JSONTokener accepts Kuwo's legacy single-quoted object syntax.
        // Parsing it as data avoids eval/JS execution entirely.
        val root = try { JSONObject(OnlineHttp.get(url)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: IOException) { throw failure }
        catch (_: Exception) { throw IOException("音源返回格式不兼容，请稍后再试") }
        val songs = root.optJSONArray("abslist") ?: throw IOException("音源未返回歌曲列表")
        return (0 until songs.length().coerceAtMost(30)).mapNotNull { index ->
            coroutineContext.ensureActive()
            val item = songs.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("MUSICRID").removePrefix("MUSIC_")
            if (!validId("kw", id)) return@mapNotNull null
            val title = cleanText(item.optString("SONGNAME").ifBlank { item.optString("NAME") })
            if (title.isBlank()) return@mapNotNull null
            Track(
                id = ("kw:$id").hashCode().toLong(), uri = Uri.EMPTY,
                title = title, artist = cleanText(item.optString("ARTIST")).ifBlank { "未知歌手" },
                album = cleanText(item.optString("ALBUM")),
                durationMs = ((item.optString("DURATION").toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceAtLeast(0L),
                displayName = title, isOnline = true, sourceId = "kw", sourceTrackId = id,
                artworkUri = artwork(item.optString("pic"))
                    ?: shortArtwork(item.optString("web_albumpic_short"), "https://img4.kuwo.cn/star/albumcover/")
                    ?: shortArtwork(item.optString("web_artistpic_short"), "https://img1.kuwo.cn/star/starheads/"),
            )
        }.distinctBy { it.stableKey }
    }

    private suspend fun searchKugou(keyword: String): List<Track> {
        val root = jsonGet("https://msearch.kugou.com/api/v3/search/song", "format" to "json",
            "keyword" to keyword, "page" to "1", "pagesize" to "30", "showtype" to "1")
        if (root.optInt("status") != 1) throw IOException("酷狗搜索暂不可用（${root.optInt("errcode")}）")
        return objects(root.optJSONObject("data")?.optJSONArray("info")).mapNotNull { item ->
            makeTrack("kg", item.optString("hash").lowercase(Locale.ROOT), item.optString("songname"),
                item.optString("singername"), item.optString("album_name"), item.optLong("duration") * 1000,
                item.optJSONObject("trans_param")?.optString("union_cover").orEmpty().ifBlank { item.optString("pic") })
        }
    }

    private suspend fun searchNetease(keyword: String): List<Track> {
        val root = jsonGet("https://music.163.com/api/search/get/web", "s" to keyword,
            "type" to "1", "offset" to "0", "limit" to "30")
        if (root.optInt("code") != 200) throw IOException("网易云搜索暂不可用（${root.optInt("code")}）")
        val result = root.optJSONObject("result") ?: throw IOException("网易云未返回搜索数据")
        if (result.optInt("songCount", -1) == 0) return emptyList()
        val tracks = objects(result.optJSONArray("songs")).mapNotNull { item ->
            val album = item.optJSONObject("album")
            makeTrack("wy", item.optString("id"), item.optString("name"), names(item.optJSONArray("artists")),
                album?.optString("name").orEmpty(), item.optLong("duration"), album?.optString("picUrl").orEmpty())
        }
        if (tracks.isEmpty()) return emptyList()
        // Search omits picUrl on this public endpoint. One batch request, matched by ID (never by order/title).
        val detail = optionalMetadata {
            jsonGet("https://music.163.com/api/song/detail", "ids" to
                JSONArray(tracks.map { it.sourceTrackId }).toString())
        }
        val byId = detail?.optJSONArray("songs")?.let { objects(it).associateBy { song -> song.optString("id") } }.orEmpty()
        return tracks.map { track ->
            track.copy(artworkUri = artwork(byId[track.sourceTrackId]?.optJSONObject("album")?.optString("picUrl").orEmpty())
                ?: track.artworkUri)
        }
    }

    private suspend fun searchQq(keyword: String): List<Track> {
        val root = jsonGet("https://c.y.qq.com/soso/fcgi-bin/client_search_cp", "format" to "json",
            "w" to keyword, "n" to "30", "p" to "1")
        if (root.optInt("code", -1) != 0) throw IOException("QQ 音乐搜索暂不可用（${root.optInt("code")}）")
        return objects(root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")).mapNotNull { item ->
            val album = item.optJSONObject("album")
            makeTrack("tx", item.optString("songmid").ifBlank { item.optString("mid") },
                item.optString("songname").ifBlank { item.optString("title").ifBlank { item.optString("name") } },
                names(item.optJSONArray("singer")),
                item.optString("albumname").ifBlank { album?.optString("name").orEmpty() },
                item.optLong("interval") * 1000, qqAlbumArtwork(item)?.toString().orEmpty())
        }
    }

    private fun qqAlbumArtwork(item: JSONObject): Uri? {
        val album = item.optJSONObject("album")
        // Song MID and album MID are different namespaces. Never synthesize an album from a song ID.
        val albumMid = listOf(item.optString("albummid"), item.optString("albumMid"), album?.optString("mid").orEmpty())
            .firstOrNull { it.matches(Regex("[A-Za-z0-9]{8,32}")) && it != "null" }
        if (albumMid != null) return artwork("https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg")
        return listOf("picUrl", "cover", "coverUrl").firstNotNullOfOrNull { key ->
            artwork(album?.optString(key).orEmpty())
        }
    }

    private suspend fun searchBilibili(keyword: String): List<Track> {
        val root = jsonGet("https://api.bilibili.com/x/web-interface/search/type", "search_type" to "video",
            "keyword" to keyword, "page" to "1", "page_size" to "30")
        if (root.optInt("code", -1) != 0) throw IOException("哔哩哔哩公开搜索受限（${root.optInt("code")}），未使用登录凭据")
        val data = root.optJSONObject("data") ?: throw IOException("哔哩哔哩未返回搜索数据")
        if (data.optInt("numResults", -1) == 0) return emptyList()
        return objects(data.optJSONArray("result")).mapNotNull { item ->
            val seconds = item.optString("duration").split(':').fold(0L) { n, part -> n * 60 + (part.trim().toLongOrNull() ?: 0L) }
            makeTrack("bili", item.optString("bvid"), item.optString("title"), item.optString("author"),
                "视频音源", seconds * 1000, item.optString("pic"))
        }
    }

    /** The observed public contract; kept disabled because the unsigned response is 411.
     * No deviceId, signature secret, login cookie or protected fallback is reproduced. */
    private suspend fun searchMigu(keyword: String): List<Track> {
        val switches = JSONObject().put("song", 1).put("album", 0).put("singer", 0).put("tagSong", 1)
            .put("mvSong", 0).put("bestShow", 1).put("songlist", 0).put("lyricSong", 0)
        val root = jsonGet("https://jadeite.migu.cn/music_search/v3/search/searchAll", "isCorrect" to "1",
            "isCopyright" to "1", "searchSwitch" to switches.toString(), "pageSize" to "30",
            "text" to keyword, "pageNo" to "1", "sort" to "0", "sid" to "USS")
        if (root.optString("code") != "000000") throw IOException("咪咕公开搜索需要来源签名认证，暂未启用")
        return objects(root.optJSONObject("data")?.optJSONObject("songResultData")?.optJSONArray("resultList")).mapNotNull { item ->
            val cover = listOf("img2", "img1", "img3").map { item.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            val duration = listOf("length", "timeLength", "duration").map { item.optString(it) }
                .firstOrNull { it.isNotBlank() }.orEmpty().split(':').fold(0L) { n, part -> n * 60 + (part.toLongOrNull() ?: 0) }
            val artists = item.optJSONArray("singerList")?.let { singers -> objects(singers).joinToString(" / ") {
                it.optString("name").ifBlank { it.optString("singerName") }
            } }.orEmpty()
            makeTrack("mg", item.optString("copyrightId").ifBlank { item.optString("songId") },
                item.optString("name"), artists, item.optString("album"), duration * 1000,
                if (cover.startsWith('/')) "https://d.musicapp.migu.cn$cover" else cover)
        }
    }

    private suspend fun makeTrack(source: String, id: String, title: String, artist: String, album: String,
                                  duration: Long, cover: String): Track? {
        coroutineContext.ensureActive()
        if (!validId(source, id) || title.isBlank()) return null
        return Track(id = "$source:$id".hashCode().toLong(), uri = Uri.EMPTY,
            title = cleanText(title), artist = cleanText(artist).ifBlank { "未知歌手" }, album = cleanText(album),
            durationMs = duration.coerceAtLeast(0), displayName = cleanText(title), isOnline = true,
            sourceId = source, sourceTrackId = id, artworkUri = artwork(cover))
    }

    private fun objects(array: JSONArray?): List<JSONObject> {
        if (array == null) throw IOException("音源未返回歌曲列表，请稍后重试")
        return (0 until array.length().coerceAtMost(60)).mapNotNull(array::optJSONObject)
    }

    private fun names(array: JSONArray?): String = array?.let { objects(it).joinToString(" / ") { item -> item.optString("name") } }.orEmpty()

    private fun validId(source: String, id: String): Boolean = when (source) {
        "kw", "wy", "mg" -> id.matches(Regex("[0-9]{1,24}"))
        "kg" -> id.matches(Regex("[a-fA-F0-9]{32}"))
        "tx" -> id.matches(Regex("[a-zA-Z0-9]{8,32}"))
        "bili" -> id.matches(Regex("BV[a-zA-Z0-9]{10}"))
        else -> false
    }

    private fun artwork(value: String): Uri? {
        val raw = value.trim().replace("{size}", "300").let { if (it.startsWith("//")) "https:$it" else it }
        if (raw.isEmpty() || raw.length > 4096) return null
        return runCatching {
            val uri = Uri.parse(raw).let { if (it.scheme == "http") it.buildUpon().scheme("https").build() else it }
            OnlineHttp.validateUrl(uri.toString())
            uri
        }.getOrNull()
    }

    private fun shortArtwork(value: String, base: String): Uri? =
        value.takeIf { it.isNotBlank() && it != "null" }?.let { artwork(if (it.startsWith("http")) it else base + it.trimStart('/')) }

    private suspend fun jsonGet(base: String, vararg parameters: Pair<String, String>): JSONObject {
        val url = Uri.parse(base).buildUpon().apply { parameters.forEach { (key, value) -> appendQueryParameter(key, value) } }.build()
        return try { JSONObject(OnlineHttp.get(url.toString())) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IOException) { throw failure }
        catch (_: Exception) { throw IOException("音源返回格式不兼容，请稍后再试") }
    }

    private suspend fun <T> optionalMetadata(block: suspend () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    internal fun playbackEvidence(track: Track): OnlinePlaybackEvidence? =
        synchronized(playbackEvidenceCache) { playbackEvidenceCache[track.stableKey] }

    private fun checkPlaybackIdentity(response: JSONObject, data: JSONObject, source: String, id: String): OnlinePlaybackEvidence {
        val metadata = listOfNotNull(data, data.optJSONObject("song"), data.optJSONObject("track"),
            data.optJSONObject("songInfo"), data.optJSONObject("musicInfo"), response)
        fun scalar(item: JSONObject, key: String): String? {
            val value = item.opt(key)
            if (value == null || value == JSONObject.NULL) return null
            if (value !is String && value !is Number) throw IOException("解析返回的来源或曲目身份格式无效，已停止播放")
            return value.toString().trim().takeUnless { it.isEmpty() || it.equals("null", true) }
        }
        var reportedSource: String? = null
        for (item in metadata) for (key in listOf("source", "provider", "platform", "sourceId")) {
            val value = scalar(item, key) ?: continue
            val canonical = when (value.lowercase(Locale.ROOT)) {
                "kw", "kuwo", "酷我", "酷我音乐" -> "kw"
                "kg", "kugou", "酷狗", "酷狗音乐" -> "kg"
                "wy", "netease", "163", "网易云", "网易云音乐" -> "wy"
                "tx", "qq", "qqmusic", "qq音乐", "qq 音乐" -> "tx"
                "bili", "bilibili", "哔哩哔哩" -> "bili"
                "mg", "migu", "咪咕", "咪咕音乐" -> "mg"
                else -> throw IOException("解析返回未知来源，无法核对音频身份，已停止播放")
            }
            if (canonical != source) throw IOException("解析返回来源与搜索来源冲突，已拒绝跨源音频")
            reportedSource = canonical
        }
        val idKeys = listOf("rid", "sourceTrackId") + when (source) {
            "kw" -> listOf("id", "musicId", "musicid", "MUSICRID")
            "kg" -> listOf("hash", "songhash")
            "wy" -> listOf("id", "songId", "songid")
            // QQ numeric songid is not the songmid used by this adapter.
            "tx" -> listOf("mid", "songmid", "songMid")
            else -> emptyList()
        }
        fun canonicalId(value: String): String = when (source) {
            "kw" -> value.replaceFirst(Regex("^MUSIC_", RegexOption.IGNORE_CASE), "")
            "kg" -> value.lowercase(Locale.ROOT)
            else -> value
        }
        var reportedId: String? = null
        // A response-envelope id can be a request identifier. QQ's numeric id and Kugou's
        // numeric catalogue id are also different namespaces from songmid/hash, respectively.
        for (item in metadata.filter { it !== response }) for (key in idKeys) {
            val value = scalar(item, key) ?: continue
            if (canonicalId(value) != canonicalId(id)) throw IOException("解析返回曲目 ID 与请求冲突，已停止播放")
            reportedId = canonicalId(value)
        }
        fun publicText(vararg keys: String): String? = metadata.firstNotNullOfOrNull { item ->
            keys.firstNotNullOfOrNull { key ->
                (item.opt(key) as? String)?.let(::cleanText)?.takeIf { it.isNotBlank() && !it.equals("null", true) && !it.contains("://") }
            }
        }
        // Matching assertions still do not prove what an audio file contains; URL-only responses are unverified.
        // Titles/artist aliases and CDN hostnames alone are never grounds for acceptance or rejection.
        return OnlinePlaybackEvidence(reportedSource, reportedId, publicText("title", "songname", "songName", "name"),
            publicText("artist", "singer", "artistName"), audioHost = "")
    }

    suspend fun resolvePlayableTrack(track: Track): Track = withContext(Dispatchers.IO) {
        if (!track.isOnline) return@withContext track
        val source = track.sourceId?.trim()?.lowercase(Locale.ROOT)
        val id = track.sourceTrackId?.trim().orEmpty()
        require(builtInSources.any { it.id == source && it.supportsPlayback } && validId(source.orEmpty(), id)) {
            "此来源的播放适配尚未启用"
        }
        synchronized(playbackEvidenceCache) { playbackEvidenceCache.remove(track.stableKey) }
        val payload = JSONObject().put("source", source).put("rid", id).put("level", "standard").toString()
        var lastFailure: Exception? = null
        for (gateway in GATEWAYS) {
            coroutineContext.ensureActive()
            val response = try {
                JSONObject(OnlineHttp.post(gateway, payload, mapOf("X-Request-Id" to UUID.randomUUID().toString())))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: SourceHttpException) {
                // Don't route around authentication, access denial or throttling.
                if (failure.status in ACCESS_DENIED) throw failure
                lastFailure = failure
                continue
            } catch (failure: Exception) {
                lastFailure = failure
                continue
            }
            val code = response.opt("code")
            if (!(code == "0" || (code is Number && code.toDouble() == 0.0))) {
                // Never surface signed URLs, provider diagnostics or private headers in UI errors.
                throw IOException("来源未提供标准音质播放权限或地址，请稍后重试")
            }
            val data = response.optJSONObject("data") ?: throw IOException("音源缺少播放数据")
            // Outside the gateway connection-fallback catch: an identity conflict must never trigger another provider.
            val evidence = checkPlaybackIdentity(response, data, source.orEmpty(), id)
            val rawUrl = data.optString("url").trim()
            if (rawUrl.isBlank()) throw IOException("音源未提供播放地址")
            // Some legacy CDNs advertise HTTP; use their HTTPS equivalent. No
            // cleartext exception or globally disabled TLS checking is added.
            val uri = Uri.parse(rawUrl).let { if (it.scheme == "http") it.buildUpon().scheme("https").build() else it }
            OnlineHttp.validateUrl(uri.toString())
            val rawHeaders = data.optJSONObject("playbackHeaders") ?: data.optJSONObject("headers")
            val allowed = setOf("user-agent", "referer", "origin", "accept", "range")
            val headers = mutableMapOf<String, String>()
            rawHeaders?.keys()?.forEach { key ->
                val value = rawHeaders.optString(key)
                if (key.lowercase(Locale.ROOT) in allowed && value.length <= 4096 && '\r' !in value && '\n' !in value) headers[key] = value
            }
            val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.').lowercase(Locale.ROOT)
            val mime = when (extension) {
                "flac" -> "audio/flac"
                "m4a", "mp4" -> "audio/mp4"
                "aac" -> "audio/aac"
                "ogg", "opus" -> "audio/ogg"
                "mp3" -> "audio/mpeg"
                else -> track.mimeType
            }
            coroutineContext.ensureActive()
            synchronized(playbackEvidenceCache) {
                playbackEvidenceCache[track.stableKey] = evidence.copy(audioHost = uri.host.orEmpty())
            }
            // Lyrics/artwork are enriched independently by the screen, never on the path
            // that starts the decoder. A slow metadata host must not postpone playback.
            return@withContext track.copy(uri = uri, requestHeaders = headers, mimeType = mime)
        }
        throw IOException(if (lastFailure is SourceHttpException) "解析服务暂不可用（HTTP ${(lastFailure as SourceHttpException).status}）"
            else "解析服务连接失败，请稍后重试")
    }

    /** Public metadata only. The caller owns the original stableKey; no title-based cross-source matching. */
    suspend fun resolveMetadata(
        track: Track,
        onArtworkReady: suspend (Uri) -> Unit = {},
    ): Track = withContext(Dispatchers.IO) {
        if (!track.isOnline || !validId(track.sourceId.orEmpty(), track.sourceTrackId.orEmpty())) return@withContext track
        coroutineScope {
            val cover = async { resolveArtwork(track).artworkUri }
            val lyrics = async { optionalMetadata { resolveLyrics(track) } }
            val artworkUri = cover.await() ?: track.artworkUri
            coroutineContext.ensureActive()
            // Runs on IO; callers merge only this field into the current stableKey on Main.
            if (artworkUri != null) onArtworkReady(artworkUri)
            val resolved = track.copy(artworkUri = artworkUri,
                lines = lyrics.await()?.takeIf { it.isNotEmpty() } ?: track.lines)
            coroutineContext.ensureActive()
            resolved
        }
    }

    /** Also used lazily by visible legacy saved rows, which predate artworkUri persistence. */
    suspend fun resolveArtwork(track: Track): Track = withContext(Dispatchers.IO) {
        if (track.artworkUri != null || !track.isOnline || !validId(track.sourceId.orEmpty(), track.sourceTrackId.orEmpty())) return@withContext track
        val cover = synchronized(artworkCache) { artworkCache[track.stableKey] } ?: artworkRequests.withPermit {
            // A visible row may have finished while this request waited for its permit.
            synchronized(artworkCache) { artworkCache[track.stableKey] } ?: optionalMetadata { resolveCover(track) }.also { resolved ->
                coroutineContext.ensureActive()
                if (resolved != null) synchronized(artworkCache) { artworkCache[track.stableKey] = resolved }
            }
        }
        coroutineContext.ensureActive()
        // A concurrent failure must not discard another request's successful cover.
        track.copy(artworkUri = synchronized(artworkCache) { artworkCache[track.stableKey] } ?: cover)
    }

    private suspend fun resolveCover(track: Track): Uri? {
        if (track.artworkUri != null) return track.artworkUri
        val id = track.sourceTrackId.orEmpty()
        return when (track.sourceId) {
            "kw" -> artwork(OnlineHttp.get("https://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=url&content=list&size=300&rid=$id",
                maxBytes = 16 * 1024).lineSequence().firstOrNull().orEmpty())
            "kg" -> {
                val info = jsonGet("https://m.kugou.com/app/i/getSongInfo.php", "cmd" to "playInfo", "hash" to id)
                if (!info.optString("hash").equals(id, true) && !info.optString("req_hash").equals(id, true)) null
                else artwork(info.optString("album_img").ifBlank { info.optString("imgUrl") })
            }
            "wy" -> {
                val songs = jsonGet("https://music.163.com/api/song/detail", "ids" to JSONArray(listOf(id)).toString()).optJSONArray("songs")
                val item = songs?.let { objects(it).firstOrNull { song -> song.optString("id") == id } }
                artwork(item?.optJSONObject("album")?.optString("picUrl").orEmpty())
            }
            "tx" -> {
                val request = JSONObject().put("comm", JSONObject().put("ct", 24).put("cv", 0))
                    .put("songinfo", JSONObject().put("module", "music.pf_song_detail_svr")
                        .put("method", "get_song_detail_yqq").put("param", JSONObject().put("song_mid", id)))
                val root = jsonGet("https://u.y.qq.com/cgi-bin/musicu.fcg", "data" to request.toString())
                val section = root.optJSONObject("songinfo")
                if (root.optInt("code", -1) != 0 || section == null || section.optInt("code", -1) != 0) null
                else {
                    val info = section.optJSONObject("data")?.optJSONObject("track_info")
                    // Only enrich the requested song; neither playback nor the search identity is replaced.
                    if (info == null || info.optString("mid") != id) null else qqAlbumArtwork(info)
                }
            }
            else -> null
        }
    }

    private suspend fun resolveLyrics(track: Track): List<LyricLine> {
        val id = track.sourceTrackId.orEmpty()
        return when (track.sourceId) {
            "kw" -> {
                val root = jsonGet("https://wapi.kuwo.cn/openapi/v1/www/lyric/getlyric", "musicId" to id, "httpsStatus" to "1")
                if (root.optInt("code") != 200) return emptyList()
                val data = root.optJSONObject("data") ?: return emptyList()
                val supplied = lyricPayload(data, track.durationMs)
                if (supplied.isNotEmpty()) return supplied
                val rows = data.optJSONArray("lrclist") ?: return emptyList()
                val lrc = buildString {
                    for (index in 0 until rows.length().coerceAtMost(10_000)) {
                        coroutineContext.ensureActive()
                        val row = rows.optJSONObject(index) ?: continue
                        val time = row.optString("time").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: continue
                        val millis = (time * 1000).toLong()
                        append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", millis / 60_000, millis / 1000 % 60, millis % 1000))
                        append(row.optString("lineLyric").replace('\n', ' ')); append('\n')
                    }
                }
                parseLyrics(lrc, track.durationMs)
            }
            "kg" -> parseLyrics(OnlineHttp.get("https://m.kugou.com/app/i/krc.php?cmd=100&hash=$id&timelength=1",
                maxBytes = LrcParser.MAX_BYTES), track.durationMs)
            "wy" -> lyricPayload(jsonGet("https://interface3.music.163.com/api/song/lyric", "id" to id,
                "os" to "Linux", "lv" to "-1", "kv" to "-1", "tv" to "-1", "rv" to "-1"), track.durationMs)
            "tx" -> {
                val text = OnlineHttp.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid=$id",
                    mapOf("Referer" to "https://y.qq.com/"), maxBytes = LrcParser.MAX_BYTES)
                lyricPayload(JSONObject(text), track.durationMs)
            }
            // Bilibili captions are not song lyrics; Migu's private signing scheme is deliberately not reproduced.
            else -> emptyList()
        }
    }

    private suspend fun lyricPayload(data: JSONObject, duration: Long): List<LyricLine> {
        fun text(key: String): String = when (val value = data.opt(key)) {
            is JSONObject -> value.optString("lyric").ifBlank { value.optString("text") }
            is String -> value
            else -> ""
        }
        // Prefer real TTML if supplied. No synthesized per-word timing or conversion of video subtitles.
        val raw = listOf("ttml", "ttmlText", "lrc", "lyric", "lyrics").map(::text).firstOrNull { it.isNotBlank() }.orEmpty()
        val lines = parseLyrics(raw, duration)
        val translated = parseLyrics(text("tlyric").ifBlank { text("trans") }, duration).associateBy { it.startMs }
        val romanized = parseLyrics(text("romalrc"), duration).associateBy { it.startMs }
        return lines.map { line ->
            coroutineContext.ensureActive()
            line.copy(translation = line.translation ?: translated[line.startMs]?.text,
                romanization = line.romanization ?: romanized[line.startMs]?.text)
        }
    }

    private suspend fun parseLyrics(raw: String, duration: Long): List<LyricLine> {
        if (raw.isBlank() || raw.length > LrcParser.MAX_BYTES) return emptyList()
        var text = raw.trim().removePrefix("\uFEFF")
        // Some public QQ responses still base64-encode the lyric despite nobase64=1.
        if (!text.startsWith('[') && !text.startsWith('<') && text.matches(Regex("[A-Za-z0-9+/=\\r\\n]+"))) {
            text = runCatching { Base64.decode(text, Base64.DEFAULT).toString(Charsets.UTF_8) }.getOrDefault(text)
        }
        val jobContext = coroutineContext
        // Keep XML entities intact for the XML parser (decoding &amp; first would corrupt valid TTML).
        if (TtmlParser.looksLikeTtml(text)) return TtmlParser.parse(text) { jobContext.ensureActive() }
        text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { match ->
                val number = match.groupValues[1]
                val code = if (number.startsWith('x')) number.drop(1).toIntOrNull(16) else number.toIntOrNull()
                if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
            }.replace("&amp;", "&")
        return if (TtmlParser.looksLikeTtml(text)) TtmlParser.parse(text) { jobContext.ensureActive() }
        else LrcParser.parseChecked(text, duration) { jobContext.ensureActive() }
    }

    fun savedTracks(): List<Track> {
        val source = preferences.getString("saved_tracks", "[]") ?: "[]"
        val array = runCatching { JSONArray(source) }.getOrNull() ?: return emptyList()
        return (0 until array.length().coerceAtMost(1000)).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val sourceId = item.getString("sourceId")
                val id = item.getString("sourceTrackId")
                Track(
                    id = ("$sourceId:$id").hashCode().toLong(), uri = Uri.EMPTY,
                    title = item.getString("title"), artist = item.optString("artist"), album = item.optString("album"),
                    durationMs = item.optLong("durationMs"), isOnline = true, sourceId = sourceId, sourceTrackId = id,
                    artworkUri = item.optString("artworkUri").takeIf { it.isNotBlank() }?.let(Uri::parse),
                )
            }.getOrNull()
        }.distinctBy { it.stableKey }
    }

    fun saveTrack(track: Track): List<Track> {
        require(track.isOnline) { "本地歌曲不写入在线歌单" }
        val next = (savedTracks().filterNot { it.stableKey == track.stableKey } + track).takeLast(1000)
        writeSaved(next)
        return next
    }

    fun removeTrack(track: Track): List<Track> = savedTracks().filterNot { it.stableKey == track.stableKey }.also(::writeSaved)

    private fun writeSaved(tracks: List<Track>) {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(JSONObject().put("sourceId", track.sourceId).put("sourceTrackId", track.sourceTrackId)
                .put("title", track.title).put("artist", track.artist).put("album", track.album)
                .put("durationMs", track.durationMs).put("artworkUri", track.artworkUri?.toString().orEmpty()))
        }
        // Do not persist expiring playback URLs, private headers, or a false local-file flag.
        preferences.edit().putString("saved_tracks", array.toString()).apply()
    }

    suspend fun download(track: Track): Long = withContext(Dispatchers.IO) {
        val source = builtInSources.firstOrNull { it.id == track.sourceId }
        require(source?.supportsDownload == true) { "该来源尚未提供可用的下载地址" }
        val playable = resolvePlayableTrack(track)
        OnlineHttp.validateUrl(playable.uri.toString())
        val safeName = "${track.artist} - ${track.title}".replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").take(100)
        val extension = when (playable.mimeType?.lowercase(Locale.ROOT)) {
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/aac", "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            else -> "mp3"
        }
        val request = DownloadManager.Request(playable.uri).setTitle(track.title).setDescription("余音 · 在线下载")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "余音/$safeName-${System.currentTimeMillis()}.$extension")
        playable.mimeType?.let(request::setMimeType)
        playable.requestHeaders.forEach { (key, value) -> request.addRequestHeader(key, value) }
        @Suppress("DEPRECATION")
        request.allowScanningByMediaScanner()
        coroutineContext.ensureActive()
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    private fun cleanText(text: String): String = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
        .replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim().take(250)

    companion object {
        const val CONFIG_URL = "https://13413.kstore.vip/QingMusic/music.json"
        // The host's List<Track> callback has no query/source token. Publish an immutable receipt as well,
        // so leaving the screen, editing a query or receiving equal lists cannot mislabel an older result.
        private val latestSearchReceipt = MutableStateFlow<OnlineSearchReceipt?>(null)
        internal val searchReceipt = latestSearchReceipt.asStateFlow()
        private val ARTIST_SEPARATORS = Regex("\\s*[/&、,，;；]\\s*")
        private val VERSION_MARKERS = Regex("(?<![a-z])(?:dj|remix|cover|live)(?![a-z])|翻唱|伴奏|纯音乐|片段|试听|串烧|演唱会|现场|混音|加速|慢速",
            RegexOption.IGNORE_CASE)
        // Memory-only, bounded and shared with the screen's metadata repository. No signed URL or headers retained.
        private val playbackEvidenceCache = object : LinkedHashMap<String, OnlinePlaybackEvidence>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OnlinePlaybackEvidence>?): Boolean = size > 128
        }
        private val SEARCH_ADAPTERS = mapOf(
            "kw" to "fetchSearchMusic", "kg" to "kgSearchMusic", "wy" to "wySearchMusic",
            "tx" to "txSearchMusic", "bili" to "biliSearchMusic", "mg" to "mgSearchMusic",
        )
        private val DETAIL_ADAPTERS = mapOf("kw" to "fetchMusicDetail", "kg" to "kgMusicDetail",
            "wy" to "wyMusicDetail", "tx" to "txMusicDetail", "bili" to "biliMusicDetail", "mg" to "mgMusicDetail")
        private val ACCESS_DENIED = setOf(401, 403, 412, 418, 429)
        private val GATEWAYS = listOf(
            "https://musicserver.haitangw.cc/v1/music/resolve-url",
            "https://qt.haitangw.net/v1/music/resolve-url",
        )
    }
}

internal class SourceHttpException(val status: Int) : IOException(
    if (status in setOf(401, 403, 412, 418, 429)) "来源限制访问（HTTP $status），未尝试绕过，请稍后重试"
    else "音源返回 HTTP $status",
)

internal object OnlineHttp {
    fun validateUrl(value: String): URL {
        val url = URL(value)
        require(url.protocol == "https" && url.userInfo == null) { "只接受 HTTPS 公开音源地址" }
        require(url.host.isNotBlank() && url.host.lowercase() !in setOf("localhost", "127.0.0.1", "::1")) { "无效音源地址" }
        return url
    }

    suspend fun get(value: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 2 * 1024 * 1024): String =
        request(value, headers, maxBytes, null)

    suspend fun post(value: String, body: String, headers: Map<String, String> = emptyMap()): String =
        request(value, headers, 1024 * 1024, body)

    private suspend fun request(value: String, headers: Map<String, String>, maxBytes: Int, body: String?): String =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpURLConnection?>()
            val task = CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                try {
                    val result = performRequest(value, headers, maxBytes, body, active)
                    if (continuation.isActive) continuation.resume(result)
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (failure: Exception) {
                    val safe = when (failure) {
                        is SourceHttpException -> failure
                        else -> IOException("音源连接失败或响应无效，请稍后重试")
                    }
                    if (continuation.isActive) continuation.resumeWithException(safe)
                }
            }
            continuation.invokeOnCancellation {
                task.cancel()
                runCatching { active.getAndSet(null)?.disconnect() }
            }
        }

    private suspend fun performRequest(value: String, headers: Map<String, String>, maxBytes: Int, body: String?,
                                       active: AtomicReference<HttpURLConnection?>): String {
        var url = validateUrl(value)
        repeat(4) {
            coroutineContext.ensureActive()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RushiMusic")
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (key, header) -> setRequestProperty(key, header) }
                if (body != null) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            active.set(connection)
            try {
                coroutineContext.ensureActive()
                if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (body != null) throw IOException("解析网关要求跳转，未向其他地址转发请求")
                    val location = connection.getHeaderField("Location") ?: throw IOException("音源重定向缺少地址")
                    url = validateUrl(URL(url, location).toString())
                    return@repeat
                }
                if (status !in 200..299) throw SourceHttpException(status)
                if (connection.contentLengthLong > maxBytes) throw IOException("音源响应过大")
                val raw = connection.inputStream
                val stream = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
                val bytes = stream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > maxBytes) throw IOException("音源响应过大")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                coroutineContext.ensureActive()
                return bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
            } finally {
                active.compareAndSet(connection, null)
                connection.disconnect()
            }
        }
        throw IOException("音源重定向次数过多")
    }
}
