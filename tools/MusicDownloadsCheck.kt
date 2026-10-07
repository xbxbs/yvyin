package com.example.xuebimc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64

/** JVM check: compile with MusicDownloads.kt, MusicMetadataEmbedder.kt, jaudiotagger and Android stubs.
 * No emulator, network, DownloadManager or Android runtime; the tag fixture uses temporary WAV/PNG files.
 * Device-only: persisted grants/revocation, provider errors, broadcasts, reboot/job scheduling.
 */
fun main() {
    val rules = MusicDownloadRules
    val aac = rules.fileSpec("Artist - Song.m4a", "audio/aac; codecs=mp4a.40.2", "stream")
    check(aac.extension == "aac" && aac.mimeType == "audio/aac")
    check(aac.stem == "Artist - Song") // raw AAC must not be labelled as an MP4 container
    check(rules.fileSpec("song.mp3", "audio/x-flac", "song.mp3").extension == "flac")
    check(rules.fileSpec("song.mp3", null, "actual.FLAC").mimeType == "audio/flac")
    check(rules.fileSpec("song.ogg", "application/ogg", "").mimeType == "audio/ogg")
    check(rules.fileSpec("song.m4a", "audio/mp4", "").extension == "m4a")
    check(runCatching { rules.fileSpec("song.mp3", "text/html", "") }.isFailure)
    check(runCatching { rules.fileSpec("song", null, "stream") }.isFailure)
    val bad = rules.fileSpec("../\\\u202e恶意:\n?*\"<>| .mp3", "audio/mpeg", "")
    check(bad.stem.none { it in "/\\:*?\"<>|\n\u202e" } && !bad.stem.startsWith('.'))
    val longName = rules.fileSpec("音🎵".repeat(300) + ".flac", "audio/flac", "")
        .uniqueName("12345678-1234-1234-1234-123456789abc")
    check(longName.toByteArray(Charsets.UTF_8).size < 255)
    check(longName == String(longName.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    check(rules.fileSpec("...", "audio/mpeg", "").stem == "音乐")

    val staging = "file:///app/external/downloads/unique.mp3"
    check(rules.owns(42, staging, 42, staging))
    check(rules.owns(-1, staging, 42, staging)) // crash between enqueue and ID commit
    check(!rules.owns(42, staging, 43, staging))
    check(!rules.owns(42, staging, 42, "file:///someone-elses/file.mp3"))
    check(!rules.owns(-1, staging, 42, null))
    check(!rules.owns(-1, staging, -1, staging))

    val bytes = ByteArray(150_123) { (it % 251).toByte() }
    val source = { ByteArrayInputStream(bytes) as InputStream }
    fun compare(target: ByteArray) = rules.compare(source, { ByteArrayInputStream(target) }, bytes.size.toLong()) {}
    check(compare(byteArrayOf()) == MusicDownloadRules.CopyMatch.PREFIX)
    check(compare(bytes.copyOf(70_007)) == MusicDownloadRules.CopyMatch.PREFIX)
    check(compare(bytes) == MusicDownloadRules.CopyMatch.COMPLETE) // kill after close, before commit
    check(compare(bytes + 1.toByte()) == MusicDownloadRules.CopyMatch.DIFFERENT)
    check(compare(bytes.copyOf().apply { this[70_000] = -1 }) == MusicDownloadRules.CopyMatch.DIFFERENT)
    val out = ByteArrayOutputStream()
    rules.copy(source, { out }, bytes.size.toLong()) {}
    check(out.toByteArray().contentEquals(bytes))
    fun fails(block: () -> Unit) { check(runCatching(block).exceptionOrNull() is IOException) }
    fails { rules.copy(source, { ByteArrayOutputStream() }, bytes.size + 1L) {} }
    fails { rules.copy(source, { ByteArrayOutputStream() }, bytes.size - 1L) {} }
    fails { rules.copy(source, { object : ByteArrayOutputStream() {
        override fun close() { throw IOException("Provider close failed") }
    } }, bytes.size.toLong()) {} }
    fails { rules.copy(source, { object : ByteArrayOutputStream() {
        override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("Provider full") }
    } }, bytes.size.toLong()) {} }
    var checks = 0
    val interrupted = ByteArrayOutputStream()
    fails { rules.copy(source, { interrupted }, bytes.size.toLong()) {
        if (++checks == 2) throw InterruptedIOException("Job stopped")
    } }
    check(interrupted.size() in 1 until bytes.size)
    check(compare(interrupted.toByteArray()) == MusicDownloadRules.CopyMatch.PREFIX)
    check(bytes.contentEquals(ByteArray(150_123) { (it % 251).toByte() })) // source never mutated

    check(MusicDownloads.Options().embedCover && MusicDownloads.Options().embedLyrics)
    check(MusicDownloads.State.COMPLETE_WITH_WARNINGS != MusicDownloads.State.COMPLETE)
    val fixture = Files.createTempDirectory("music-embedded-tags-").toFile()
    // One second of valid, silent PCM: actual tag writing/read-back, no emulator or network.
    val pcm = ByteArray(44 + 44_100 * 2)
    ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(pcm.size - 8); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(44_100); putInt(88_200)
        putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size - 44)
    }
    val original = fixture.resolve("source.wav").apply { writeBytes(pcm) }
    val cover = fixture.resolve("cover.png").apply { writeBytes(Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ1kAAAAASUVORK5CYII=")) }
    val metadata = MusicMetadataEmbedder.Metadata("测试歌曲", "测试歌手", "测试专辑", "[00:00.000]中文歌词", cover, true, true)
    val result = MusicMetadataEmbedder.prepare(original, "fixture", metadata) {}
    check(result.warning == null) { "Real WAV embed failed: ${result.warning}" }
    check(result.file != original && result.file.length() > original.length())
    check(original.readBytes().contentEquals(pcm)) // source is never rewritten
    val reread = org.jaudiotagger.audio.AudioFileIO.read(result.file)
    check(reread.tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS) == metadata.lyrics)
    check(reread.tag.firstArtwork.binaryData.contentEquals(cover.readBytes()))
    val omitted = MusicMetadataEmbedder.prepare(original, "without-metadata", metadata.copy(coverFile = null, lyrics = "")) {}
    check(omitted.warning?.contains("未取得歌词") == true && omitted.warning.contains("未取得封面"))
    val disabled = MusicMetadataEmbedder.prepare(original, "disabled", metadata.copy(embedCover = false, embedLyrics = false)) {}
    check(disabled.file == original && disabled.warning == null)
    val wrongType = fixture.resolve("wrong.mp3").apply { writeText("<html>not audio</html>") }
    check(runCatching { MusicMetadataEmbedder.prepare(wrongType, "wrong", metadata) {} }
        .exceptionOrNull() is InvalidMusicDownloadException)
    println("MusicDownloadsCheck OK: formats/names, ownership, verified recovery, cancellation, real embedded WAV cover+lyrics round-trip; fixtures=$fixture")
}
