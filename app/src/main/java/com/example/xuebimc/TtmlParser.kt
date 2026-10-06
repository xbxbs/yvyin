package com.example.xuebimc

import android.util.Xml
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/**
 * Apple Music / AMLL TTML lyrics.
 * - `<p begin end>` is a line; `<span begin end>` children make it word-timed, plain text keeps it line-timed.
 * - `ttm:role="x-translation"` / `"x-roman"` spans → translation / romanization.
 * - `ttm:role="x-bg"` spans → background vocals (small sub line).
 * - `ttm:agent` on `<p>`: the first agent sings on the left, any other agent (duet partner) on the right.
 * - `<iTunesMetadata><translations><translation><text for="L1">` → translation keyed by `itunes:key`.
 */
object TtmlParser {
    fun looksLikeTtml(text: String): Boolean {
        val head = text.trimStart().take(512)
        return head.startsWith("<?xml") || head.startsWith("<tt") || head.contains("<tt ") || head.contains("<tt>")
    }

    fun parse(text: String, checkActive: () -> Unit = {}): List<LyricLine> {
        require(text.length <= LrcParser.MAX_BYTES) { "歌词文本过大" }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(text))

        val rows = ArrayList<Row>()
        val keyedTranslations = HashMap<String, String>()
        var primaryAgent: String? = null

        var row: Row? = null
        // Stack of span roles for nested spans (x-bg spans contain word spans).
        val roles = ArrayList<String?>()
        var span: Span? = null
        var translationKey: String? = null
        val translationText = StringBuilder()
        var inTranslations = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            checkActive()
            when (event) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "translations" -> inTranslations = true
                    "text" -> if (inTranslations) {
                        translationKey = parser.attr("for")
                        translationText.setLength(0)
                    }
                    "p" -> if (!inTranslations) {
                        val agent = parser.attr("agent")
                        if (primaryAgent == null && agent != null) primaryAgent = agent
                        row = Row(
                            begin = parser.attr("begin")?.let(::time),
                            end = parser.attr("end")?.let(::time),
                            key = parser.attr("key"),
                            alignEnd = agent != null && primaryAgent != null && agent != primaryAgent,
                        )
                        roles.clear()
                    }
                    "span" -> if (row != null) {
                        val role = parser.attr("role")
                        roles += role
                        val inherited = roles.lastOrNull { it != null }
                        if (role == null) {
                            span = Span(parser.attr("begin")?.let(::time), parser.attr("end")?.let(::time), inherited)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val value = parser.text.orEmpty()
                    val current = row
                    when {
                        inTranslations && translationKey != null -> translationText.append(value)
                        current == null -> Unit
                        else -> {
                            val role = roles.lastOrNull { it != null }
                            val open = span
                            when (role) {
                                "x-translation" -> current.translation.append(value)
                                "x-roman" -> current.roman.append(value)
                                "x-bg" -> current.background.append(value)
                                else -> if (open != null && open.begin != null) {
                                    open.text.append(value)
                                } else if (value.isNotEmpty()) {
                                    // Whitespace between spans separates words; keep it on the previous word.
                                    val last = current.words.lastOrNull()
                                    if (last != null && value.isBlank()) last.text.append(value)
                                    else if (value.isNotBlank()) current.plain.append(value)
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "translations" -> inTranslations = false
                    "text" -> if (inTranslations) {
                        translationKey?.let { keyedTranslations[it] = translationText.toString().trim() }
                        translationKey = null
                    }
                    "span" -> if (row != null) {
                        val closing = span
                        if (closing != null && roles.lastOrNull() == null) {
                            if (closing.role == null && closing.begin != null) row!!.words += closing
                            else if (closing.role == "x-bg") row!!.background.append(closing.text)
                            span = null
                        }
                        if (roles.isNotEmpty()) roles.removeAt(roles.lastIndex)
                    }
                    "p" -> {
                        row?.let { rows += it }
                        row = null
                        span = null
                    }
                }
            }
            event = parser.next()
        }

        val result = ArrayList<LyricLine>(rows.size)
        for (r in rows) {
            val words = r.words.mapNotNull { w ->
                val start = w.begin ?: return@mapNotNull null
                val text = w.text.toString()
                if (text.isEmpty()) null else WordCue(text, start, maxOf(start, w.end ?: start))
            }
            val start = r.begin ?: words.firstOrNull()?.startMs ?: continue
            val end = maxOf(start, r.end ?: words.lastOrNull()?.endMs ?: start)
            val plain = r.plain.toString().trim()
            if (words.isEmpty() && plain.isEmpty()) continue
            val translation = r.translation.toString().trim().ifEmpty { null }
                ?: r.key?.let { keyedTranslations[it] }?.ifEmpty { null }
            result += LyricLine(
                startMs = start,
                endMs = end,
                words = words.ifEmpty { listOf(WordCue(plain, start, end)) },
                wordTimed = words.isNotEmpty(),
                translation = translation,
                romanization = r.roman.toString().trim().ifEmpty { null },
                alignEnd = r.alignEnd,
                background = r.background.toString().trim().ifEmpty { null },
            )
        }
        result.sortBy { it.startMs }
        return result
    }

    private fun local(name: String?): String = name.orEmpty().substringAfter(':')

    /** Attribute by local name, ignoring the namespace prefix (ttm:agent, itunes:key, xml:id …). */
    private fun XmlPullParser.attr(localName: String): String? {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i).substringAfter(':') == localName) return getAttributeValue(i)
        }
        return null
    }

    /** `hh:mm:ss.fff`, `mm:ss.fff`, `ss.fff`, or `12.3s` → milliseconds. */
    internal fun time(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.endsWith("ms")) return value.dropLast(2).toDoubleOrNull()?.toLong()
        if (value.endsWith("s")) return value.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
        val parts = value.split(':')
        var seconds = 0.0
        for (part in parts) seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        return (seconds * 1000 + 0.5).toLong()
    }

    private class Span(val begin: Long?, val end: Long?, val role: String?) {
        val text = StringBuilder()
    }

    private class Row(val begin: Long?, val end: Long?, val key: String?, val alignEnd: Boolean) {
        val words = ArrayList<Span>()
        val plain = StringBuilder()
        val translation = StringBuilder()
        val roman = StringBuilder()
        val background = StringBuilder()
    }
}
