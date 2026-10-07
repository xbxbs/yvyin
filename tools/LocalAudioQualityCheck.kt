package com.example.xuebimc

import android.net.Uri

/** Offline, bounded regression check. Compile with Track.kt + AudioQuality.kt and android-all. */
fun main() {
    val source = Track(1L, Uri.parse("content://fixture/audio/1"), "Fixture", "Artist", "Album", 10_000L)
    fun quality(container: String?, codec: String? = null, bits: Int = 0, rate: Int = 0) =
        source.copy(mimeType = container, codecMimeType = codec, bits = bits, sampleRate = rate).audioQuality
    fun expect(level: AudioQualityLevel, actual: AudioQuality) = check(actual.level == level) {
        "Expected $level, got ${actual.level}: ${actual.description}"
    }

    expect(AudioQualityLevel.Unknown, quality("audio/flac", bits = 24, rate = 192_000))
    expect(AudioQualityLevel.Unknown, quality("audio/wav", bits = 24, rate = 192_000))
    expect(AudioQualityLevel.Unknown, quality("audio/flac", "audio/raw", 24, 192_000))
    expect(AudioQualityLevel.Unknown, quality("audio/mp4"))
    check(quality("audio/flac").badgeLabel == null)
    val aac = quality("audio/flac", "audio/mp4a-latm", 32, 96_000)
    expect(AudioQualityLevel.Lossy, aac)
    check(!aac.isLossless && aac.bitsPerSample == 0 && aac.description.contains("有损"))
    expect(AudioQualityLevel.Lossy, quality("audio/mpeg", "audio/raw", 24, 96_000))
    expect(AudioQualityLevel.Lossless, quality("audio/flac", "audio/flac", 16, 44_100))
    expect(AudioQualityLevel.HiResLossless, quality("audio/flac", "audio/flac", 16, 96_000))
    expect(AudioQualityLevel.HiResLossless, quality("audio/flac", "audio/flac", 0, 192_000))
    // User screenshot: 24-bit/44.1 kHz is in Apple's ordinary Lossless tier, not HR.
    expect(AudioQualityLevel.Lossless, quality("audio/flac", "audio/flac", 24, 44_100))
    expect(AudioQualityLevel.Lossless, quality("audio/flac", "audio/flac", 24, 48_000))
    expect(AudioQualityLevel.HiResLossless, quality("audio/flac", "audio/flac", 24, 96_000))
    expect(AudioQualityLevel.HiResLossless, quality("audio/mp4", "audio/alac", 24, 192_000))
    expect(AudioQualityLevel.Dsd, quality("application/octet-stream", "audio/dsf", 1, 2_822_400))
    check(quality("audio/flac", "audio/flac", 16, 44_100).badgeLabel == "无损")
    check(quality("audio/flac", "audio/flac", 24, 96_000).badgeLabel == "高解析度无损")

    val flac = ByteArray(42).apply {
        "fLaC".toByteArray().copyInto(this)
        this[4] = 0x80.toByte()
        this[7] = 34
        val rate = 96_000
        this[18] = (rate ushr 12).toByte()
        this[19] = (rate ushr 4).toByte()
        this[20] = (((rate and 15) shl 4) or (1 shl 1) or (23 ushr 4)).toByte()
        this[21] = ((23 and 15) shl 4).toByte()
    }
    val flacInfo = checkNotNull(parseAudioHeader(flac, flac.size))
    check(flacInfo == AudioStreamMetadata("audio/flac", 96_000, 24))
    check(readAudioHeader(flac.inputStream()) == flacInfo)
    val id3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 4) + byteArrayOf(0, 0, 0, 0)
    check(readAudioHeader((id3 + flac).inputStream()) == flacInfo)
    check(readAudioHeader((id3.copyOf(12)).inputStream()) == null)
    val invalidId3 = id3.copyOf().apply { this[6] = 0x80.toByte() }
    check(readAudioHeader((invalidId3 + flac).inputStream()) == null)
    val alac = ByteArray(24).apply {
        this[2] = 0x10
        this[5] = 24
        this[9] = 2
        val rate = 96_000
        repeat(4) { this[20 + it] = (rate ushr ((3 - it) * 8)).toByte() }
    }
    check(parseAlacCodecSpecificData(alac) == AudioStreamMetadata("audio/alac", 96_000, 24))
    val atom = ByteArray(12).apply { this[3] = 36; "alac".toByteArray().copyInto(this, 4) } + alac
    check(parseAlacCodecSpecificData(atom) == AudioStreamMetadata("audio/alac", 96_000, 24))
    check(parseAlacCodecSpecificData(ByteArray(24)) == null)
    check(parseAudioHeader(flac, 41) == null)
    val pcm = localQualityWave(format = 1, bits = 24, rate = 96_000)
    val pcmInfo = checkNotNull(parseAudioHeader(pcm, pcm.size))
    check(pcmInfo.codecMimeType == "audio/pcm" && pcmInfo.bits == 24)
    expect(AudioQualityLevel.HiResLossless, quality("audio/wav", pcmInfo.codecMimeType, pcmInfo.bits, pcmInfo.sampleRate))
    val lossyWave = localQualityWave(format = 7, bits = 8, rate = 8_000)
    val lossyInfo = checkNotNull(parseAudioHeader(lossyWave, lossyWave.size))
    expect(AudioQualityLevel.Lossy, quality("audio/wav", lossyInfo.codecMimeType, lossyInfo.bits, lossyInfo.sampleRate))
    check(lossyInfo.bits == 0)
    val unknownWave = localQualityWave(format = 17, bits = 4, rate = 44_100)
    val unknownInfo = checkNotNull(parseAudioHeader(unknownWave, unknownWave.size))
    expect(AudioQualityLevel.Unknown, quality("audio/wav", unknownInfo.codecMimeType, unknownInfo.bits, unknownInfo.sampleRate))

    // Truncated/malformed headers must stay bounded and must not read beyond the provided limit.
    for (bytes in listOf(flac, pcm, lossyWave, unknownWave)) {
        for (length in 0..bytes.size) parseAudioHeader(bytes, length)
        parseAudioHeader(bytes, -1)
        parseAudioHeader(bytes, Int.MAX_VALUE)
    }
    val random = java.util.Random(91L)
    repeat(250) { parseAudioHeader(ByteArray(128).also(random::nextBytes), 128) }
    println("PASS: Apple-style lossless/hi-res labels, DSD, lossy AAC/MP3, MIME-only unknown, decoder-PCM rejection")
    println("PASS: Apple 24/44.1 and 24/48 vs >48k boundary; unknown depth never demotes verified high rate")
    println("PASS: FLAC including ID3 prefix, ALAC cookies, PCM/compressed-WAV, bounded truncated inputs")
}

private fun localQualityWave(format: Int, bits: Int, rate: Int) = ByteArray(44).apply {
    fun little(offset: Int, value: Int, count: Int) {
        repeat(count) { this[offset + it] = (value ushr (it * 8)).toByte() }
    }
    "RIFF".toByteArray().copyInto(this)
    little(4, 36, 4)
    "WAVEfmt ".toByteArray().copyInto(this, 8)
    little(16, 16, 4)
    little(20, format, 2)
    little(22, 2, 2)
    little(24, rate, 4)
    little(28, rate * 2 * bits / 8, 4)
    little(32, 2 * bits / 8, 2)
    little(34, bits, 2)
    "data".toByteArray().copyInto(this, 36)
}
