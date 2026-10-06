package com.example.xuebimc

import android.net.Uri

data class WordCue(val text: String, val startMs: Long, val endMs: Long)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<WordCue>,
    val wordTimed: Boolean = true,
    /** Translated text shown under the line; never word-highlighted. */
    val translation: String? = null,
    /** Romanization (e.g. romaji / pinyin) shown under the line. */
    val romanization: String? = null,
    /** Duet: lines sung by a non-primary TTML agent are right-aligned (AMLL / Apple Music). */
    val alignEnd: Boolean = false,
    /** Background vocals (TTML x-bg), shown small under the main line. */
    val background: String? = null,
) {
    val text: String get() = words.joinToString("") { it.text }
}

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumId: Long = 0,
    val displayName: String = "",
    val mimeType: String? = null,
    val sizeBytes: Long = 0,
    val relativePath: String? = null,
    val sampleRate: Int = 0,
    val bits: Int = 0,
    val lines: List<LyricLine> = emptyList(),
    val isOnline: Boolean = false,
    val sourceId: String? = null,
    val sourceTrackId: String? = null,
    val artworkUri: Uri? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    /** Encoded audio-track MIME, not the file/container MIME in mimeType. */
    val codecMimeType: String? = null,
    val genre: String? = null,
    /** Reported average bit rate in bits/second; zero means unavailable. */
    val bitrate: Long = 0L,
) {
    val stableKey: String
        get() = if (isOnline && !sourceId.isNullOrBlank() && !sourceTrackId.isNullOrBlank()) {
            "$sourceId:$sourceTrackId"
        } else uri.toString()

    val performanceLines: List<LyricLine> by lazy {
        val creditPattern = Regex(
            "^(?:Lyricist|Composer|Arranger|Producer|Guitars|Strings|Recording|Mixing|Mastering|" +
                "(?:Lyrics|Written|Composed|Produced|Arranged)\\s+by|" +
                "作词|作曲|词曲|编曲|制作人|演唱|录音|混音|母带)\\s*[:：]",
            RegexOption.IGNORE_CASE,
        )
        lines.dropWhile { line ->
            line.text.trim() == "$title - $artist" || creditPattern.containsMatchIn(line.text)
        }
    }

    val credits: List<LyricLine> get() = lines.take(lines.size - performanceLines.size)
}

fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
