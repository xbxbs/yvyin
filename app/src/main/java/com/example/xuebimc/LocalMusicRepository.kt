package com.example.xuebimc

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Permissions and persistable SAF grants belong to the activity, never to this repository. */
class LocalMusicRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val lyricLinks by lazy { appContext.getSharedPreferences("local_music_lyrics", Context.MODE_PRIVATE) }

    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) projection += MediaStore.MediaColumns.RELATIVE_PATH
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) projection += MediaStore.Audio.Media.GENRE
        val active = currentCoroutineContext()
        query(
            collection,
            projection.toTypedArray(),
            "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 0)",
            order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE, ${MediaStore.Audio.Media._ID}",
        ) { cursor ->
            val columns = projection.map { cursor.getColumnIndex(it) }
            buildList {
                while (cursor.moveToNext()) {
                    active.ensureActive()
                    val id = cursor.numberAt(columns[0])
                    val name = cursor.stringAt(columns[6]).orEmpty()
                    add(
                        Track(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            title = cursor.stringAt(columns[1]).known() ?: titleFrom(name),
                            artist = cursor.stringAt(columns[2]).known() ?: UNKNOWN,
                            album = cursor.stringAt(columns[3]).known() ?: UNKNOWN,
                            durationMs = cursor.numberAt(columns[4]).coerceAtLeast(0),
                            albumId = cursor.numberAt(columns[5]).coerceAtLeast(0),
                            displayName = name,
                            mimeType = cursor.stringAt(columns[7]).known(),
                            sizeBytes = cursor.numberAt(columns[8]).coerceAtLeast(0),
                            relativePath = columns.getOrNull(9)?.let { cursor.stringAt(it) },
                            genre = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cursor.text(MediaStore.Audio.Media.GENRE).known() else null,
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    suspend fun loadDetails(track: Track): Track = withContext(Dispatchers.IO) {
        val detailed = if (track.isOnline) track else metadata(track)
        val attached = lyricLinks.getString(track.stableKey, null)?.let { saved ->
            optional { lyricsAt(Uri.parse(saved), detailed.durationMs) }
        }
        // Lyrics inside the audio file first, then a same-named .ttml / .lrc beside it.
        val lyrics = attached ?: if (track.isOnline) null else embeddedLyrics(detailed) ?: siblingLyrics(detailed)
        detailed.copy(lines = lyrics ?: track.lines)
    }

    /** Lyrics stored in the audio tags (USLT / Vorbis LYRICS / MP4 ©lyr). */
    private suspend fun embeddedLyrics(track: Track): List<LyricLine>? = optional {
        val descriptor = resolver.openFileDescriptor(track.uri, "r") ?: return@optional null
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { EmbeddedLyrics.read(it.channel) }
            ?: return@optional null
        parseLyricText(text, track.durationMs) { }.takeIf { it.isNotEmpty() }
    }

    suspend fun loadArtwork(track: Track, maxSize: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        if (maxSize <= 0) return@withContext null
        val size = maxSize.coerceAtMost(MAX_ARTWORK_EDGE)
        val artwork = track.artworkUri
        val remote = artwork?.scheme.equals("https", true) || artwork?.scheme.equals("http", true)
        val supplied = artwork?.let {
            when {
                remote -> optional { downloadArtwork(it) }
                it.scheme == "content" || it.scheme == "file" -> optional { readBytes(it, MAX_ARTWORK_BYTES) }
                else -> null
            }
        }
        if (remote && supplied == null) return@withContext null
        // Even a mislabelled online track must never download audio to obtain a cover.
        val localAudio = !track.isOnline && (track.uri.scheme == "content" || track.uri.scheme == "file")
        val embedded = supplied ?: if (localAudio) retrieve(track.uri) { it.embeddedPicture } else null
        val bytes = embedded ?: if (localAudio && track.albumId > 0) optional {
            val volume = mediaVolume(track.uri)
            readBytes(Uri.parse("content://media/$volume/audio/albumart/${track.albumId}"), MAX_ARTWORK_BYTES)
        } else null
        currentCoroutineContext().ensureActive()
        if (bytes == null || bytes.size > MAX_ARTWORK_BYTES) return@withContext null
        val bitmap = optional { decodeArtwork(bytes, size) } ?: return@withContext null
        try {
            currentCoroutineContext().ensureActive()
            bitmap
        } catch (cancelled: CancellationException) {
            bitmap.recycle()
            throw cancelled
        }
    }

    /** Only artwork bytes, in memory; no playback headers, cookies, disk cache or automatic redirects. */
    private suspend fun downloadArtwork(uri: Uri): ByteArray? = RemoteArtworkHttp.download(uri.toString())

    suspend fun importAudio(uri: Uri): Track = withContext(Dispatchers.IO) {
        val info = optional {
            query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)) { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.text(OpenableColumns.DISPLAY_NAME).orEmpty() to
                        cursor.numberAt(cursor.getColumnIndex(OpenableColumns.SIZE)).coerceAtLeast(0)
                } else null
            }
        }
        val name = info?.first.orEmpty()
        val mediaId = if (uri.authority == MediaStore.AUTHORITY) optional { ContentUris.parseId(uri) } else null
        metadata(
            Track(
                id = mediaId?.takeIf { it >= 0 } ?: importedId(uri),
                uri = uri,
                title = titleFrom(name),
                artist = UNKNOWN,
                album = UNKNOWN,
                durationMs = 0,
                displayName = name,
                mimeType = optional { resolver.getType(uri) }?.known(),
                sizeBytes = info?.second ?: 0,
            ),
        )
    }

    suspend fun attachLyrics(track: Track, uri: Uri): Track = withContext(Dispatchers.IO) {
        val lines = lyricsAt(uri, track.durationMs)
        currentCoroutineContext().ensureActive()
        lyricLinks.edit().putString(track.stableKey, uri.toString()).apply()
        track.copy(lines = lines)
    }

    private suspend fun metadata(track: Track): Track = withContext(Dispatchers.IO) {
        if (track.isOnline || track.uri.scheme !in listOf("content", "file")) return@withContext track
        val detailed = retrieve(track.uri) { retriever ->
            fun value(key: Int) = retriever.extractMetadata(key).known()
            track.copy(
                title = value(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: track.title,
                artist = value(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: track.artist,
                album = value(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: track.album,
                genre = value(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: track.genre,
                durationMs = value(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: track.durationMs,
                // Some vendor retrievers report the decoded PCM output (audio/raw) here.
                // Keep the MediaStore/container MIME so a lossy file cannot become “lossless”.
                mimeType = value(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.takeUnless { isPcmAudioCodec(it) } ?: track.mimeType,
                bitrate = value(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    ?.takeIf { it > 0L } ?: track.bitrate,
                sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    value(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                        ?.takeIf { it > 0 } ?: track.sampleRate
                } else track.sampleRate,
                bits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    value(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
                        ?.takeIf { it > 0 } ?: track.bits
                } else track.bits,
            )
        } ?: track
        // Either probe can succeed independently when a vendor retriever/extractor cannot.
        val stream = audioStreamMetadata(track.uri)
        val header = audioHeaderMetadata(track.uri)
        val result = detailed.copy(
            codecMimeType = header?.codecMimeType ?: stream?.codecMimeType ?: detailed.codecMimeType,
            sampleRate = header?.sampleRate?.takeIf { it > 0 }
                ?: stream?.sampleRate?.takeIf { it > 0 } ?: detailed.sampleRate,
            bits = header?.bits?.takeIf { it > 0 }
                ?: stream?.bits?.takeIf { it > 0 } ?: detailed.bits,
            bitrate = stream?.bitrate?.takeIf { it > 0 } ?: detailed.bitrate,
        )
        result.copy(bits = if (result.audioQuality.isLossless) result.bits else 0)
    }

    private suspend fun audioStreamMetadata(uri: Uri): AudioStreamMetadata? = optional {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(appContext, uri, null)
            for (index in 0 until extractor.trackCount) {
                currentCoroutineContext().ensureActive()
                val format = extractor.getTrackFormat(index)
                val codec = normalizedAudioMime(format.getString(MediaFormat.KEY_MIME))
                if (codec?.startsWith("audio/") != true) continue
                fun positiveInt(key: String): Int = try {
                    if (format.containsKey(key)) format.getInteger(key).coerceAtLeast(0) else 0
                } catch (_: Exception) { 0 }
                // pcm-encoding on AAC/MP3 can describe decoder output; only trust it for PCM files.
                val pcmBits = if (isPcmAudioCodec(codec)) when (positiveInt(MediaFormat.KEY_PCM_ENCODING)) {
                    AudioFormat.ENCODING_PCM_8BIT -> 8
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> 0
                } else 0
                return@optional AudioStreamMetadata(
                    codecMimeType = codec,
                    sampleRate = positiveInt(MediaFormat.KEY_SAMPLE_RATE),
                    bits = if (isLosslessAudioCodec(codec)) {
                        positiveInt("bits-per-sample").takeIf { it > 0 } ?: pcmBits
                    } else 0,
                    bitrate = positiveInt(MediaFormat.KEY_BIT_RATE).toLong(),
                )
            }
            null
        } finally {
            try { extractor.release() } catch (_: Exception) { /* Preserve the probe result/cancellation. */ }
        }
    }

    private suspend fun audioHeaderMetadata(uri: Uri): AudioStreamMetadata? = optional {
        resolver.openInputStream(uri)?.use { input ->
            // Known headers are bounded; ordinary formats stop after a 16-byte signature.
            val bytes = ByteArray(64 * 1024)
            var count = 0
            var limit = 16
            while (count < limit) {
                currentCoroutineContext().ensureActive()
                val read = input.read(bytes, count, limit - count)
                if (read <= 0) break
                count += read
                if (count == 16 && limit == 16) {
                    val magic = String(bytes, 0, 4, Charsets.US_ASCII)
                    limit = when {
                        magic == "fLaC" -> 42
                        magic == "DSD " || magic == "MAC " -> 80
                        magic == "FRM8" && String(bytes, 12, 4, Charsets.US_ASCII) == "DSD " -> 16
                        (magic == "RIFF" || magic == "RF64") &&
                            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE" -> bytes.size
                        else -> return@use null
                    }
                }
            }
            parseAudioHeader(bytes, count)
        }
    }

    private suspend fun <T> retrieve(uri: Uri, read: (MediaMetadataRetriever) -> T): T? = optional {
        val retriever = MediaMetadataRetriever()
        try {
            currentCoroutineContext().ensureActive()
            retriever.setDataSource(appContext, uri)
            val result = read(retriever)
            currentCoroutineContext().ensureActive()
            result
        } finally {
            try { retriever.release() } catch (_: Exception) { /* Some vendor implementations throw on release. */ }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun siblingLyrics(track: Track): List<LyricLine>? {
        val projection = mutableListOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) projection += MediaStore.MediaColumns.RELATIVE_PATH
        val location = optional {
            query(track.uri, projection.toTypedArray()) { cursor ->
                if (cursor.moveToFirst()) Location(
                    cursor.text(MediaStore.MediaColumns.DISPLAY_NAME) ?: track.displayName,
                    cursor.text(MediaStore.MediaColumns.DATA),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.text(MediaStore.MediaColumns.RELATIVE_PATH) ?: track.relativePath
                    } else null,
                ) else null
            }
        } ?: Location(track.displayName, null, track.relativePath)

        // A raw path is only a best-effort sidecar lookup. Playback always keeps the content URI.
        location.path?.let { path ->
            val audio = File(path)
            val parent = audio.parentFile
            if (audio.isAbsolute && parent != null) {
                for (extension in listOf("ttml", "TTML", "lrc", "LRC")) {
                    val file = File(parent, audio.nameWithoutExtension + "." + extension)
                    val lyrics = optional {
                        file.inputStream().use { parseLyrics(readLimited(it, LrcParser.MAX_BYTES), track.durationMs) }
                    }
                    if (lyrics != null) return lyrics
                }
            }
        }

        val name = location.name
        if (name.isBlank() || name.contains('/') || name.contains('\\')) return null
        val stem = name.substringBeforeLast('.', name)
        // TTML carries word timing, duets and translations, so it wins over a same-named LRC.
        for (wanted in listOf("$stem.ttml", "$stem.lrc")) {
            siblingNamed(track, location, wanted)?.let { return it }
        }
        return null
    }

    private suspend fun siblingNamed(track: Track, location: Location, wanted: String): List<LyricLine>? {
        val relativePath = location.relativePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
            val files = MediaStore.Files.getContentUri(mediaVolume(track.uri))
            val active = currentCoroutineContext()
            val candidates = optional {
                query(
                    files,
                    arrayOf(MediaStore.Files.FileColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? COLLATE NOCASE",
                    arrayOf(relativePath, wanted),
                ) { cursor ->
                    buildList {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                        while (size < 8 && cursor.moveToNext()) {
                            active.ensureActive()
                            add(ContentUris.withAppendedId(files, cursor.getLong(idColumn)))
                        }
                    }
                }
            }.orEmpty()
            for (candidate in candidates) {
                optional { lyricsAt(candidate, track.durationMs) }?.let { return it }
            }
        }
        return documentSibling(track, wanted)
    }

    private suspend fun documentSibling(track: Track, wanted: String): List<LyricLine>? {
        // Only ExternalStorageProvider defines path-shaped IDs; opaque cloud IDs cannot be guessed.
        if (track.uri.authority != "com.android.externalstorage.documents") return null
        val documentId = optional { DocumentsContract.getDocumentId(track.uri) } ?: return null
        val boundary = documentId.lastIndexOf('/').takeIf { it >= 0 } ?: documentId.indexOf(':')
        if (boundary < 0) return null
        val parentId = documentId.substring(0, if (documentId[boundary] == ':') boundary + 1 else boundary)
        val prefix = documentId.substring(0, boundary + 1)
        for (name in listOf(wanted, wanted.substringBeforeLast('.') + "." + wanted.substringAfterLast('.').uppercase())) {
            val sibling = DocumentsContract.buildDocumentUri(track.uri.authority!!, prefix + name)
            optional { lyricsAt(sibling, track.durationMs) }?.let { return it }
        }
        val trees = optional {
            resolver.persistedUriPermissions.filter {
                it.isReadPermission && it.uri.authority == track.uri.authority && DocumentsContract.isTreeUri(it.uri)
            }.map { it.uri }
        }.orEmpty().toMutableList()
        if (DocumentsContract.isTreeUri(track.uri)) trees += track.uri
        val active = currentCoroutineContext()
        for (tree in trees.distinct()) {
            val sibling = optional {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME)) { cursor ->
                    var found: Uri? = null
                    while (cursor.moveToNext()) {
                        active.ensureActive()
                        if (cursor.text(OpenableColumns.DISPLAY_NAME).equals(wanted, ignoreCase = true)) {
                            val id = cursor.text(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            if (id != null) found = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            break
                        }
                    }
                    found
                }
            }
            if (sibling != null) optional { lyricsAt(sibling, track.durationMs) }?.let { return it }
        }
        return null
    }

    private suspend fun lyricsAt(uri: Uri, durationMs: Long): List<LyricLine> =
        parseLyrics(readBytes(uri, LrcParser.MAX_BYTES), durationMs)

    private fun parseLyricText(text: String, durationMs: Long, checkActive: () -> Unit): List<LyricLine> =
        if (TtmlParser.looksLikeTtml(text)) TtmlParser.parse(text, checkActive)
        else LrcParser.parseChecked(text, durationMs, checkActive)

    private suspend fun parseLyrics(bytes: ByteArray, durationMs: Long): List<LyricLine> {
        val active = currentCoroutineContext()
        active.ensureActive()
        val lines = parseLyricText(LrcParser.decode(bytes), durationMs) { active.ensureActive() }
        if (lines.isEmpty()) throw IOException("未找到带时间戳的歌词（LRC / TTML）")
        return lines
    }

    private suspend fun readBytes(uri: Uri, limit: Int): ByteArray {
        currentCoroutineContext().ensureActive()
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        return input.use { readLimited(it, limit) }
    }

    private suspend fun readLimited(input: InputStream, limit: Int): ByteArray {
        val active = currentCoroutineContext()
        val output = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        while (true) {
            active.ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("文件超过读取大小上限")
            output.write(buffer, 0, count)
        }
        active.ensureActive()
        return output.toByteArray()
    }

    private suspend fun <T> query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        args: Array<String>? = null,
        order: String? = null,
        read: (Cursor) -> T,
    ): T? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        try {
            val result = resolver.query(uri, projection, selection, args, order, signal)?.use(read)
            continuation.resume(result)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        currentCoroutineContext().ensureActive()
        val result = block()
        currentCoroutineContext().ensureActive()
        result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        null
    }

    private fun decodeArtwork(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..32_768 || bounds.outHeight !in 1..32_768 ||
            bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) return null
        var sample = 1
        while ((bounds.outWidth + sample - 1) / sample > size || (bounds.outHeight + sample - 1) / sample > size) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (bitmap.width <= size && bitmap.height <= size) return bitmap
        val ratio = size.toDouble() / maxOf(bitmap.width, bitmap.height)
        return try {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        } finally {
            bitmap.recycle()
        }
    }

    private fun mediaVolume(uri: Uri): String =
        if (uri.authority == MediaStore.AUTHORITY) uri.pathSegments.firstOrNull() ?: "external" else "external"

    private fun importedId(uri: Uri): Long {
        var hash = -3750763034362895579L
        for (character in uri.toString()) hash = (hash xor character.code.toLong()) * 1099511628211L
        return hash or Long.MIN_VALUE
    }

    private fun String?.known(): String? = this?.trim()?.takeUnless {
        it.isEmpty() || it.equals("<unknown>", true) || it.equals("unknown", true)
    }
    private fun titleFrom(name: String): String = name.substringBeforeLast('.', name).known() ?: UNKNOWN
    private fun Cursor.stringAt(index: Int): String? = if (index < 0 || isNull(index)) null else getString(index)
    private fun Cursor.numberAt(index: Int): Long = if (index < 0 || isNull(index)) 0 else getLong(index)
    private fun Cursor.text(column: String): String? = stringAt(getColumnIndex(column))
    private data class Location(val name: String, val path: String?, val relativePath: String?)

    private companion object {
        const val UNKNOWN = "未知"
        const val MAX_ARTWORK_EDGE = 2048
        const val MAX_ARTWORK_BYTES = 16 * 1024 * 1024
    }
}

/** Dedicated image transport: never reads, installs or changes the process CookieHandler. */
internal object RemoteArtworkHttp {
    private const val MAX_REMOTE_ARTWORK_BYTES = 2 * 1024 * 1024
    private const val MAX_ARTWORK_REDIRECTS = 3
    private const val MAX_ARTWORK_URL_LENGTH = 4096
    private const val REMOTE_TOTAL_TIMEOUT_MS = 15_000L

    private val client by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5_000, TimeUnit.MILLISECONDS)
            .readTimeout(5_000, TimeUnit.MILLISECONDS)
            .callTimeout(REMOTE_TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    // Android-independent entry point lets a JVM fixture test this exact production HTTP path.
    suspend fun download(value: String): ByteArray? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(REMOTE_TOTAL_TIMEOUT_MS) {
            suspendCancellableCoroutine<ByteArray> { continuation ->
                val inFlight = AtomicReference<Call?>()
                continuation.invokeOnCancellation { inFlight.getAndSet(null)?.cancel() }
                try {
                    var url = checkedUrl(value)
                    for (hop in 0..MAX_ARTWORK_REDIRECTS) {
                        continuation.context.ensureActive()
                        // Each hop is fresh. Playback headers and authentication are never copied.
                        val call = client.newCall(Request.Builder().url(url)
                            .header("Accept", "image/*")
                            .header("Accept-Encoding", "identity")
                            .get().build())
                        inFlight.set(call)
                        try {
                            continuation.context.ensureActive()
                            call.execute().use { response ->
                                continuation.context.ensureActive()
                                val status = response.code
                                if (status in listOf(301, 302, 303, 307, 308)) {
                                    if (hop >= MAX_ARTWORK_REDIRECTS) throw IOException("封面重定向过多")
                                    val location = response.header("Location")?.trim()
                                        ?.takeIf { it.isNotEmpty() && it.length <= MAX_ARTWORK_URL_LENGTH }
                                        ?: throw IOException("封面重定向缺少地址")
                                    val next = checkedUrl(URL(url.toUrl(), location).toString())
                                    if (url.isHttps && !next.isHttps) throw IOException("封面不允许 HTTPS 降级")
                                    url = next
                                } else {
                                    if (status != 200) throw IOException("封面 HTTP $status")
                                    val body = response.body ?: throw IOException("封面响应为空")
                                    if (body.contentLength() > MAX_REMOTE_ARTWORK_BYTES) throw IOException("封面超过 2 MiB")
                                    val encoding = response.header("Content-Encoding")
                                    if (!encoding.isNullOrBlank() && !encoding.trim().equals("identity", true)) {
                                        throw IOException("不支持的封面传输编码")
                                    }
                                    val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                                    if (!type.isNullOrBlank() && !type.startsWith("image/") && type != "application/octet-stream") {
                                        throw IOException("封面响应不是图片")
                                    }
                                    val output = ByteArrayOutputStream(8192)
                                    val buffer = ByteArray(8192)
                                    body.byteStream().use { input ->
                                        while (true) {
                                            continuation.context.ensureActive()
                                            val count = input.read(buffer, 0, minOf(buffer.size, MAX_REMOTE_ARTWORK_BYTES - output.size() + 1))
                                            continuation.context.ensureActive()
                                            if (count < 0) break
                                            if (output.size() + count > MAX_REMOTE_ARTWORK_BYTES) throw IOException("封面超过 2 MiB")
                                            output.write(buffer, 0, count)
                                        }
                                    }
                                    continuation.context.ensureActive()
                                    continuation.resume(output.toByteArray())
                                    return@suspendCancellableCoroutine
                                }
                            }
                        } finally {
                            inFlight.compareAndSet(call, null)
                        }
                    }
                    throw IOException("封面重定向过多")
                } catch (failure: Exception) {
                    // Cancellation owns the continuation and closes the socket even during reads.
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }
    }

    private fun checkedUrl(value: String): HttpUrl {
        if (value.length > MAX_ARTWORK_URL_LENGTH) throw IOException("无效的封面地址")
        val parsed = URL(value)
        if (parsed.protocol != "https" && parsed.protocol != "http") throw IOException("不支持的封面协议")
        if (parsed.host.isBlank() || parsed.userInfo != null) throw IOException("无效的封面地址")
        return value.toHttpUrlOrNull() ?: throw IOException("无效的封面地址")
    }
}
