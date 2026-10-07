package com.example.xuebimc

import java.io.InputStream

/**
 * Read source headers, not decoder output. ID3 may precede a FLAC/APE stream; skipping
 * it is bounded and cancellable, and never searches arbitrary audio bytes for a magic string.
 */
internal fun readAudioHeader(input: InputStream, checkActive: () -> Unit = {}): AudioStreamMetadata? {
    fun readFully(target: ByteArray, offset: Int, count: Int): Int {
        var total = 0
        while (total < count) {
            checkActive()
            val read = input.read(target, offset + total, count - total)
            if (read <= 0) break
            total += read
        }
        return total
    }
    val signature = ByteArray(10)
    if (readFully(signature, 0, signature.size) != signature.size) return null
    val isId3 = signature[0] == 'I'.code.toByte() && signature[1] == 'D'.code.toByte() && signature[2] == '3'.code.toByte()
    if (isId3) {
        val version = signature[3].toInt() and 255
        if (version !in 2..4 || (6..9).any { signature[it].toInt() and 128 != 0 }) return null
        val length = (6..9).fold(0L) { size, index -> (size shl 7) or (signature[index].toLong() and 127L) }
        val footer = if (version == 4 && signature[5].toInt() and 16 != 0) 10L else 0L
        if (length > 16L * 1024 * 1024) return null
        var remaining = length + footer
        val discard = ByteArray(8192)
        while (remaining > 0) {
            checkActive()
            val skipped = input.skip(minOf(remaining, 64L * 1024))
            if (skipped > 0) remaining -= skipped
            else {
                val read = input.read(discard, 0, minOf(remaining, discard.size.toLong()).toInt())
                if (read <= 0) return null
                remaining -= read
            }
        }
        if (readFully(signature, 0, signature.size) != signature.size) return null
    }
    val bytes = ByteArray(64 * 1024)
    signature.copyInto(bytes)
    var count = signature.size + readFully(bytes, signature.size, 16 - signature.size)
    if (count < 16) return null
    val magic = String(bytes, 0, 4, Charsets.US_ASCII)
    val limit = when {
        magic == "fLaC" -> 42
        magic == "DSD " -> 80
        magic == "MAC " -> bytes.size // APE descriptor length is not guaranteed to be 52.
        magic == "FRM8" && String(bytes, 12, 4, Charsets.US_ASCII) == "DSD " -> bytes.size
        (magic == "RIFF" || magic == "RF64") && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE" -> bytes.size
        else -> return null
    }
    count += readFully(bytes, count, limit - count)
    return parseAudioHeader(bytes, count)
}
