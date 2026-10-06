package com.example.xuebimc

enum class AudioQualityLevel { Unknown, Lossy, Lossless, HiResLossless, Dsd }

/** File encoding quality, not proof of the recording's source or the output device's signal. */
data class AudioQuality(
    val formatLabel: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val level: AudioQualityLevel,
    val bitrate: Long = 0L,
) {
    val isLossless: Boolean
        get() = level == AudioQualityLevel.Lossless || level == AudioQualityLevel.HiResLossless || level == AudioQualityLevel.Dsd

    // Only special, verified properties earn a badge. Ordinary formats leave the player quiet.
    val badgeLabel: String?
        get() = when (level) {
            AudioQualityLevel.HiResLossless -> "高解析度无损"
            AudioQualityLevel.Lossless -> "无损音频"
            AudioQualityLevel.Dsd -> "DSD"
            AudioQualityLevel.Lossy, AudioQualityLevel.Unknown -> null
        }

    val description: String
        get() = buildList {
            add(formatLabel)
            if (bitsPerSample > 0) add("$bitsPerSample bit")
            if (sampleRate > 0) {
                val fraction = (sampleRate % 1000).toString().padStart(3, '0').trimEnd('0')
                add("${sampleRate / 1000}${if (fraction.isEmpty()) "" else ".$fraction"} kHz")
            }
            when (level) {
                AudioQualityLevel.HiResLossless -> add("高解析度无损")
                AudioQualityLevel.Lossless -> add("无损音频")
                AudioQualityLevel.Dsd -> add("DSD")
                AudioQualityLevel.Lossy, AudioQualityLevel.Unknown -> Unit
            }
            if (bitrate > 0) add("${bitrate / 1000} kbps")
        }.joinToString(" · ")
}

/** Pure metadata mapping: safe for UI callers, with no file access or decoding. */
val Track.audioQuality: AudioQuality
    get() {
        // Positive badges need a probed codec/header. A scan MIME or a CDN .flac suffix alone
        // is not confirmation; ordinary formats can still be described without a badge.
        val codec = normalizedAudioMime(codecMimeType) ?: normalizedAudioMime(mimeType)?.takeIf {
            isLossyAudioCodec(it)
        }
        val lossless = isLosslessAudioCodec(codec)
        val rate = sampleRate.coerceAtLeast(0)
        val level = when {
            isDsdAudioCodec(codec) -> AudioQualityLevel.Dsd
            // Apple-style boundary: 24-bit/48 kHz is lossless, not Hi-Res.
            lossless && rate > 48_000 -> AudioQualityLevel.HiResLossless
            lossless -> AudioQualityLevel.Lossless
            isLossyAudioCodec(codec) -> AudioQualityLevel.Lossy
            else -> AudioQualityLevel.Unknown
        }
        return AudioQuality(
            formatLabel = audioCodecLabel(codec),
            sampleRate = rate,
            // PCM decoder output precision is not the bit depth of a lossy source.
            bitsPerSample = if (lossless) bits.coerceAtLeast(0) else 0,
            level = level,
            bitrate = bitrate.coerceAtLeast(0L),
        )
    }

internal fun normalizedAudioMime(mime: String?): String? =
    mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

internal fun isPcmAudioCodec(mime: String?): Boolean = when (normalizedAudioMime(mime)) {
    "audio/raw", "audio/pcm", "audio/x-pcm", "audio/l8", "audio/l16", "audio/l24" -> true
    else -> false
}

internal fun isLosslessAudioCodec(mime: String?): Boolean = when (normalizedAudioMime(mime)) {
    "audio/flac", "audio/x-flac", "audio/alac", "audio/x-alac", "audio/ape", "audio/x-ape", "audio/monkeys-audio" -> true
    else -> isPcmAudioCodec(mime) || isDsdAudioCodec(mime)
}

private fun isDsdAudioCodec(mime: String?): Boolean = normalizedAudioMime(mime) in
    setOf("audio/dsd", "audio/dsf", "audio/x-dsf", "audio/dff", "audio/x-dff", "audio/dsdiff")

private fun isLossyAudioCodec(mime: String?): Boolean = when (mime) {
    "audio/mp4a-latm", "audio/aac", "audio/aac-adts", "audio/x-aac",
    "audio/mpeg", "audio/mp3", "audio/mpeg-l2", "audio/vorbis", "audio/opus",
    "audio/g711-alaw", "audio/g711-mlaw", "audio/3gpp", "audio/amr-wb",
    "audio/ac3", "audio/eac3", "audio/eac3-joc" -> true
    else -> false
}

private fun audioCodecLabel(mime: String?): String = when (mime) {
    "audio/flac", "audio/x-flac" -> "FLAC"
    "audio/alac", "audio/x-alac" -> "ALAC"
    "audio/ape", "audio/x-ape", "audio/monkeys-audio" -> "APE"
    "audio/dsf", "audio/x-dsf" -> "DSF"
    "audio/dff", "audio/x-dff", "audio/dsdiff" -> "DFF"
    "audio/dsd" -> "DSD"
    "audio/mp4a-latm", "audio/aac", "audio/aac-adts", "audio/x-aac" -> "AAC"
    "audio/mpeg", "audio/mp3" -> "MP3"
    "audio/mpeg-l2" -> "MP2"
    "audio/vorbis" -> "Vorbis"
    "audio/opus" -> "Opus"
    "audio/g711-alaw" -> "G.711 A-law"
    "audio/g711-mlaw" -> "G.711 μ-law"
    "audio/3gpp" -> "AMR"
    "audio/amr-wb" -> "AMR-WB"
    "audio/ac3" -> "AC-3"
    "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
    null -> "编码未知"
    else -> if (isPcmAudioCodec(mime)) "PCM" else mime
}

internal data class AudioStreamMetadata(
    val codecMimeType: String? = null,
    val sampleRate: Int = 0,
    val bits: Int = 0,
    val bitrate: Long = 0L,
)

/** Bounded, pure header parsing. Extensions, decoded PCM and guessed defaults are not evidence. */
internal fun parseAudioHeader(bytes: ByteArray, length: Int): AudioStreamMetadata? {
    val limit = length.coerceIn(0, bytes.size)
    fun byteAt(offset: Int) = bytes[offset].toInt() and 0xff
    fun matches(offset: Int, text: String) = offset >= 0 && offset + text.length <= limit &&
        text.indices.all { byteAt(offset + it) == text[it].code }
    fun little16(offset: Int) = byteAt(offset) or (byteAt(offset + 1) shl 8)
    fun little32(offset: Int): Long = (0..3).fold(0L) { value, index ->
        value or (byteAt(offset + index).toLong() shl (index * 8))
    }

    // A recognized container header is evidence; a filename extension is not.
    if (matches(0, "DSD ")) {
        if (limit < 80 || little32(4) != 28L || little32(8) != 0L || !matches(28, "fmt ") ||
            little32(32) < 52L || little32(36) != 0L || little32(40) != 1L || little32(44) != 0L ||
            little32(52) !in 1L..32L) return null
        val rate = little32(56).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
        if (little32(60) !in setOf(1L, 8L)) return null
        return AudioStreamMetadata("audio/dsf", rate, 1)
    }
    if (matches(0, "FRM8") && matches(12, "DSD ")) {
        // DSDIFF is positively identified; do not guess sample rate from an arbitrary byte scan.
        return AudioStreamMetadata("audio/dff")
    }
    if (matches(0, "MAC ")) {
        if (limit < 16 || little16(4) !in 3800..6000) return null
        if (little16(4) >= 3980) {
            val descriptor = little32(8)
            if (descriptor < 52 || little32(12) < 24L) return null
            if (descriptor + 24 > limit) return AudioStreamMetadata("audio/ape")
            val start = descriptor.toInt()
            val bits = little16(start + 16)
            val rate = little32(start + 20).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: 0
            return AudioStreamMetadata("audio/ape", rate, bits.takeIf { it in 8..32 } ?: 0)
        }
        return AudioStreamMetadata("audio/ape")
    }

    if (matches(0, "fLaC")) {
        // STREAMINFO must be the first metadata block and is exactly 34 bytes.
        if (limit < 42 || byteAt(4) and 0x7f != 0 || byteAt(5) != 0 ||
            byteAt(6) != 0 || byteAt(7) != 34) return null
        val rate = (byteAt(18) shl 12) or (byteAt(19) shl 4) or (byteAt(20) ushr 4)
        val bits = (((byteAt(20) and 1) shl 4) or (byteAt(21) ushr 4)) + 1
        if (rate <= 0 || bits !in 4..32) return null
        return AudioStreamMetadata("audio/flac", rate, bits)
    }

    if ((!matches(0, "RIFF") && !matches(0, "RF64")) || !matches(8, "WAVE")) return null
    var offset = 12
    while (offset + 8 <= limit) {
        val chunkSize = little32(offset + 4)
        val payload = offset + 8
        val end = payload.toLong() + chunkSize
        if (end > limit) return null
        if (matches(offset, "fmt ")) {
            if (chunkSize < 16) return null
            val rate = little32(payload + 4).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                ?: return null
            if (little16(payload + 2) == 0) return null
            val storageBits = little16(payload + 14)
            var bits = storageBits
            var format = little16(payload)
            if (format == 0xfffe) {
                // WAVE_FORMAT_EXTENSIBLE: validate the entire subtype GUID, not just its prefix.
                if (chunkSize < 40 || little16(payload + 16) < 22) return null
                val guidTail = intArrayOf(0, 0, 0x10, 0, 0x80, 0, 0, 0xaa, 0, 0x38, 0x9b, 0x71)
                if (!guidTail.indices.all { byteAt(payload + 28 + it) == guidTail[it] }) return null
                format = little32(payload + 24).takeIf { it <= 0xffff }?.toInt() ?: return null
                val validBits = little16(payload + 18)
                if (validBits > storageBits) return null
                if (validBits > 0) bits = validBits
            }
            val codec = when (format) {
                1, 3 -> "audio/raw" // Integer PCM or uncompressed IEEE float, not arbitrary WAV.
                6 -> "audio/g711-alaw"
                7 -> "audio/g711-mlaw"
                0x55 -> "audio/mpeg"
                else -> null
            }
            if (codec == "audio/raw" && (storageBits !in 1..64 ||
                    (format == 3 && storageBits != 32 && storageBits != 64))) return null
            return AudioStreamMetadata(codec, rate, if (codec == "audio/raw") bits else 0)
        }
        if (matches(offset, "data")) return null
        val next = end + (chunkSize and 1L)
        if (next > limit) return null
        offset = next.toInt()
    }
    return null
}
