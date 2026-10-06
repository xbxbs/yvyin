package com.example.xuebimc

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Ordinary LRC supplies line timing only; never interpolate character/word timings. */
object LrcParser {
    const val MAX_BYTES: Int = 1024 * 1024
    private const val MAX_LINES = 20_000
    private const val MAX_WORDS = 100_000
    private const val TIME = "(\\d{1,7}):([0-5]\\d)(?:[.:](\\d{1,3}))?"
    private val lineTag = Regex("\\[$TIME]")
    private val wordTag = Regex("<$TIME>")
    // Word marks: enhanced-LRC <mm:ss.xx> and the inline [mm:ss.xx]word[mm:ss.xx]word style.
    private val innerTag = Regex("[<\\[]$TIME[>\\]]")
    private val offsetTag = Regex("^\\s*\\[offset\\s*:\\s*([+-]?\\d{1,10})\\s*]\\s*$", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "歌词文件超过 1 MiB" }
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
                String(bytes, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
                String(bytes, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, Charset.forName("GB18030"))
            }
        }
        return text.removePrefix("\uFEFF")
    }

    fun parse(bytes: ByteArray, durationMs: Long = 0): List<LyricLine> =
        parse(decode(bytes), durationMs)

    fun parse(text: String, durationMs: Long = 0): List<LyricLine> =
        parseChecked(text, durationMs) {}

    // Repository supplies a cancellation check without making the public text parser suspend.
    internal fun parseChecked(
        text: String,
        durationMs: Long,
        checkActive: () -> Unit,
    ): List<LyricLine> {
        require(text.length <= MAX_BYTES) { "歌词文本过大" }
        val source = text.removePrefix("\uFEFF")
        var offset = 0L
        source.lineSequence().forEach { line ->
            checkActive()
            offsetTag.matchEntire(line)?.groupValues?.get(1)?.toLongOrNull()?.let { offset = it }
        }
        val rows = ArrayList<Row>()
        var totalWords = 0
        source.lineSequence().forEach { original ->
            checkActive()
            val line = original.trim()
            val stamps = ArrayList<Long>()
            var position = 0
            while (position < line.length) {
                val tag = lineTag.find(line, position)?.takeIf { it.range.first == position } ?: break
                require(stamps.size < 256) { "单行时间戳过多" }
                stamps += time(tag)
                position = tag.range.last + 1
                while (position < line.length && line[position].isWhitespace()) position++
            }
            if (stamps.isEmpty()) return@forEach
            val rest = line.substring(position)
            // "[00:01.00]你[00:01.50]好": the leading stamp is also the first word's start.
            val body = if (stamps.size == 1 && lineTag.containsMatchIn(rest)) line else rest
            val marks = innerTag.findAll(body).take(4097).toList()
            require(marks.size <= 4096) { "单行逐字标签过多" }
            val plain = innerTag.replace(body, "").trim()
            val timed = marks.isNotEmpty() && body.substring(0, marks.first().range.first).isBlank() &&
                marks.zipWithNext().all { (a, b) -> time(a) <= time(b) }
            val cues = if (timed) marks.mapIndexedNotNull { index, mark ->
                val next = marks.getOrNull(index + 1)
                val word = body.substring(mark.range.last + 1, next?.range?.first ?: body.length)
                if (word.isEmpty()) null else Cue(word, time(mark), next?.let(::time))
            } else emptyList()
            val terminal = marks.lastOrNull()?.takeIf {
                timed && body.substring(it.range.last + 1).isBlank()
            }?.let(::time)
            stamps.forEach { stamp ->
                checkActive()
                require(rows.size < MAX_LINES) { "歌词行数过多" }
                totalWords += cues.size.coerceAtLeast(1)
                require(totalWords <= MAX_WORDS) { "逐字标签过多" }
                rows += Row((stamp + offset).coerceAtLeast(0), plain, cues, stamp - stamps.first() + offset, terminal)
            }
        }
        rows.sortBy { it.startMs }
        val result = ArrayList<LyricLine>(rows.size)
        var groupStart = 0
        while (groupStart < rows.size) {
            checkActive()
            var groupEnd = groupStart + 1
            while (groupEnd < rows.size && rows[groupEnd].startMs == rows[groupStart].startMs) groupEnd++
            val nextStart = rows.getOrNull(groupEnd)?.startMs
            // Same timestamp = original, then translation, then romanization (NetEase/QQ exports).
            // A group with no text is an instrumental marker: it only ends the previous line.
            val voiced = (groupStart until groupEnd).map { rows[it] }.filter { it.text.isNotBlank() }
            val row = voiced.firstOrNull()
            if (row != null) {
                checkActive()
                val words = row.cues.map { cue ->
                    val start = (cue.startMs + row.shift).coerceAtLeast(0)
                    // No closing tag means an instantaneous final cue, not an invented duration.
                    WordCue(cue.text, start, ((cue.endMs ?: cue.startMs) + row.shift).coerceAtLeast(start))
                }
                val end = maxOf(
                    row.startMs,
                    row.terminal?.let { it + row.shift } ?: nextStart ?: durationMs,
                    words.lastOrNull()?.endMs ?: row.startMs,
                )
                result += LyricLine(
                    row.startMs,
                    end,
                    words.ifEmpty { listOf(WordCue(row.text, row.startMs, end)) },
                    wordTimed = words.isNotEmpty(),
                    translation = voiced.getOrNull(1)?.text,
                    romanization = voiced.getOrNull(2)?.text,
                )
            }
            groupStart = groupEnd
        }
        return result
    }

    private fun time(match: MatchResult): Long =
        match.groupValues[1].toLong() * 60_000 + match.groupValues[2].toLong() * 1000 +
            match.groupValues[3].padEnd(3, '0').toLong()

    private data class Cue(val text: String, val startMs: Long, val endMs: Long?)
    private data class Row(
        val startMs: Long,
        val text: String,
        val cues: List<Cue>,
        val shift: Long,
        val terminal: Long?,
    )

    /** Merge a separately supplied translated LRC into [main] by start time (±80 ms). */
    fun mergeTranslation(main: List<LyricLine>, translated: List<LyricLine>): List<LyricLine> {
        if (translated.isEmpty()) return main
        var cursor = 0
        return main.map { line ->
            while (cursor < translated.size && translated[cursor].startMs < line.startMs - 80) cursor++
            val match = translated.getOrNull(cursor)?.takeIf { kotlin.math.abs(it.startMs - line.startMs) <= 80 }
            if (match == null || line.translation != null) line else line.copy(translation = match.text)
        }
    }
}
