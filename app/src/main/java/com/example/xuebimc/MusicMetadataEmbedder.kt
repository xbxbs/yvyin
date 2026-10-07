package com.example.xuebimc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

internal class InvalidMusicDownloadException : IOException("下载内容不是可识别的音频文件")

/** Only app-owned staging files are edited; a verified result is published by MusicDownloads. */
internal object MusicMetadataEmbedder {
    private const val MAX_COVER_BYTES = 2 * 1024 * 1024
    private val writers = setOf("mp3", "flac", "m4a", "ogg", "wav", "aiff")

    data class Metadata(
        val title: String,
        val artist: String,
        val album: String,
        val lyrics: String,
        val coverFile: File?,
        val embedCover: Boolean,
        val embedLyrics: Boolean,
    )

    data class Format(val extension: String, val mimeType: String)
    data class Result(val file: File, val format: Format, val warning: String?)

    /** Reuses the artwork-only HTTP client: playback headers/cookies are never sent to a cover URL. */
    fun cacheCover(context: Context, uri: Uri?, destination: File): Boolean = runCatching {
        if (uri == null) return false
        val raw = when (uri.scheme) {
            "http", "https" -> runBlocking { RemoteArtworkHttp.download(uri.toString()) }
            "content", "file", "android.resource" -> context.contentResolver.openInputStream(uri)?.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer, 0, minOf(buffer.size, MAX_COVER_BYTES - output.size() + 1))
                    if (count < 0) break
                    if (output.size() + count > MAX_COVER_BYTES) throw IOException("Cover too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            else -> null
        } ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val bytes = if (bounds.outMimeType in setOf("image/jpeg", "image/png")) raw else {
            // MP4 cover atoms only support JPEG/PNG. Normalize WebP/etc. with bounded pixel memory.
            val decode = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (bounds.outWidth / inSampleSize > 1600 || bounds.outHeight / inSampleSize > 1600) {
                    inSampleSize *= 2
                }
            }
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, decode) ?: return false
            try {
                ByteArrayOutputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) return false
                    output.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        if (bytes.size > MAX_COVER_BYTES) return false
        destination.outputStream().use { it.write(bytes); it.fd.sync() }
        true
    }.getOrDefault(false)

    fun prepare(source: File, token: String, metadata: Metadata, checkRunning: () -> Unit): Result {
        checkRunning()
        val format = detectFormat(source) ?: throw InvalidMusicDownloadException()
        if (!metadata.embedCover && !metadata.embedLyrics) return Result(source, format, null)
        if (format.extension !in writers) {
            return Result(source, format, "${format.extension.uppercase()} 暂不支持内嵌标签；音频已保存，封面/歌词未写入（未生成伴生文件）")
        }
        val prepared = File(source.parentFile, "$token.prepared.${format.extension}")
        val warnings = mutableListOf<String>()
        try {
            MusicDownloadRules.copy({ source.inputStream() }, { prepared.outputStream() }, source.length(), checkRunning)
            // This switches the library's read-back artwork factory away from desktop java.awt.
            TagOptionSingleton.getInstance().isAndroid = true
            val audio = AudioFileIO.readAs(prepared, format.extension)
            val oldDuration = audio.audioHeader.trackLength
            val tag = audio.tagOrCreateAndSetDefault
            listOf(FieldKey.TITLE to metadata.title, FieldKey.ARTIST to metadata.artist, FieldKey.ALBUM to metadata.album)
                .filter { it.second.isNotBlank() }.forEach { (key, value) -> tag.setField(key, value) }
            var expectedLyrics: String? = null
            var expectedCover: ByteArray? = null
            if (metadata.embedLyrics) {
                if (metadata.lyrics.isNotBlank()) {
                    expectedLyrics = metadata.lyrics
                    tag.setField(FieldKey.LYRICS, expectedLyrics)
                } else if (tag.getFirst(FieldKey.LYRICS).isNullOrBlank()) warnings += "未取得歌词，未内嵌歌词"
            }
            if (metadata.embedCover) {
                val cover = metadata.coverFile?.takeIf { it.isFile && it.length() in 1..MAX_COVER_BYTES.toLong() }
                if (cover != null) {
                    val bytes = cover.readBytes()
                    val dimensions = imageDimensions(bytes)
                    if (dimensions != null) {
                        val artwork = object : AndroidArtwork() {
                            // AndroidArtwork's default throws here. Dimensions were checked by our decoder.
                            override fun setImageFromData(): Boolean = width > 0 && height > 0
                        }.apply {
                            binaryData = bytes
                            mimeType = dimensions.third
                            width = dimensions.first
                            height = dimensions.second
                            pictureType = 3 // front cover
                            description = "Cover"
                        }
                        tag.setField(artwork)
                        expectedCover = bytes
                    } else warnings += "封面格式不可用，未内嵌封面"
                } else if (tag.artworkList.none { it.binaryData?.isNotEmpty() == true }) warnings += "未取得封面，未内嵌封面"
            }
            checkRunning()
            audio.commit()
            checkRunning()
            val verified = AudioFileIO.readAs(prepared, format.extension)
            val written = verified.tag ?: throw IOException("Missing written tags")
            if (expectedLyrics != null && written.getFirst(FieldKey.LYRICS) != expectedLyrics) {
                throw IOException("Lyrics did not round-trip")
            }
            if (expectedCover != null && written.artworkList.none { it.binaryData?.contentEquals(expectedCover) == true }) {
                throw IOException("Cover did not round-trip")
            }
            if (oldDuration > 0 && kotlin.math.abs(verified.audioHeader.trackLength - oldDuration) > 1) {
                throw IOException("Audio duration changed")
            }
            RandomAccessFile(prepared, "rw").use { it.fd.sync() }
            return Result(prepared, format, warnings.takeIf { it.isNotEmpty() }?.joinToString("；"))
        } catch (error: Exception) {
            checkRunning()
            if (error is InterruptedIOException) throw error
            // Never publish a partly rewritten file. The original DM download remains unchanged.
            return Result(source, format, "标签写入或回读校验失败；已保留原音频，所选封面/歌词未确认内嵌（未生成伴生文件）")
        }
    }

    /** Check bytes, not the resolver's filename/MIME: an AAC stream is not an M4A file. */
    internal fun detectFormat(source: File): Format? = RandomAccessFile(source, "r").use { file ->
        var offset = 0L
        var header = ByteArray(minOf(512L, file.length()).toInt()).also { file.readFully(it) }
        fun text(start: Int, count: Int) = if (header.size >= start + count)
            String(header, start, count, Charsets.ISO_8859_1) else ""
        if (text(0, 3) == "ID3" && header.size >= 10) {
            val tagSize = (6..9).fold(0L) { size, index -> (size shl 7) or (header[index].toLong() and 127) }
            offset = 10L + tagSize + if (header[5].toInt() and 0x10 != 0) 10L else 0L
            if (offset >= file.length()) return null
            file.seek(offset)
            header = ByteArray(minOf(512L, file.length() - offset).toInt()).also { file.readFully(it) }
        }
        when {
            text(0, 4) == "fLaC" -> Format("flac", "audio/flac")
            text(4, 4) == "ftyp" -> Format("m4a", "audio/mp4")
            text(0, 4) == "RIFF" && text(8, 4) == "WAVE" -> Format("wav", "audio/wav")
            text(0, 4) == "FORM" && text(8, 4) in setOf("AIFF", "AIFC") -> Format("aiff", "audio/aiff")
            text(0, 4) == "OggS" && text(0, header.size).contains("OpusHead") -> Format("opus", "audio/opus")
            text(0, 4) == "OggS" -> Format("ogg", "audio/ogg")
            text(0, 5) == "#!AMR" -> Format("amr", "audio/amr")
            header.size >= 4 && header[0] == 0x1a.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xdf.toByte() && header[3] == 0xa3.toByte() -> Format("webm", "audio/webm")
            header.size >= 2 && header[0].toInt() and 255 == 255 && header[1].toInt() and 0xf6 == 0xf0 -> Format("aac", "audio/aac")
            header.size >= 2 && header[0].toInt() and 255 == 255 && header[1].toInt() and 0xe0 == 0xe0 &&
                header[1].toInt() and 6 != 0 -> Format("mp3", "audio/mpeg")
            else -> null
        }
    }

    /** PNG/JPEG dimensions without desktop java.awt; also usable by the offline JVM fixture. */
    private fun imageDimensions(bytes: ByteArray): Triple<Int, Int, String>? {
        if (bytes.size > 24 && bytes[0] == 0x89.toByte() && String(bytes, 1, 3, Charsets.US_ASCII) == "PNG") {
            fun intAt(index: Int) = (0..3).fold(0) { value, byte -> (value shl 8) or (bytes[index + byte].toInt() and 255) }
            val width = intAt(16)
            val height = intAt(20)
            return if (width > 0 && height > 0) Triple(width, height, "image/png") else null
        }
        if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return null
        var cursor = 2
        while (cursor + 4 <= bytes.size) {
            if (bytes[cursor++].toInt() and 255 != 255) return null
            while (cursor < bytes.size && bytes[cursor].toInt() and 255 == 255) cursor++
            if (cursor >= bytes.size) return null
            val marker = bytes[cursor++].toInt() and 255
            if (marker in 0xd0..0xd9 || marker == 1) continue
            if (cursor + 2 > bytes.size) return null
            val length = ((bytes[cursor].toInt() and 255) shl 8) or (bytes[cursor + 1].toInt() and 255)
            if (length < 2 || cursor + length > bytes.size) return null
            if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf) && length >= 7) {
                val height = ((bytes[cursor + 3].toInt() and 255) shl 8) or (bytes[cursor + 4].toInt() and 255)
                val width = ((bytes[cursor + 5].toInt() and 255) shl 8) or (bytes[cursor + 6].toInt() and 255)
                return if (width > 0 && height > 0) Triple(width, height, "image/jpeg") else null
            }
            cursor += length
        }
        return null
    }
}
