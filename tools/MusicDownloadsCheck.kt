package com.example.xuebimc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** Pure JVM check: compile with MusicDownloads.kt, Android compile stubs and the app's Track/Prefs.
 * No emulator, network, DownloadManager, real files or Android runtime is used by these assertions.
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
    println("MusicDownloadsCheck OK: formats/names, task ownership, verified copy/recovery, errors/cancellation")
}
