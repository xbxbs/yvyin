package com.example.xuebimc

/** A short-lived selection, never persisted: confirmation must use this exact resolved URL. */
internal data class PreparedMusicDownload(
    val track: Track,
    val playable: Track,
    val quality: OnlinePlaybackQuality,
    val formatLabel: String?,
    val totalBytes: Long?,
)

internal sealed interface DownloadQualityPreview {
    data object Pending : DownloadQualityPreview
    data class Available(val prepared: PreparedMusicDownload) : DownloadQualityPreview
    data class Unavailable(val reason: String) : DownloadQualityPreview
}

internal data class DownloadResponseHeaders(val mimeType: String?, val totalBytes: Long?)

/** Response headers describe the file, not the actual codec/Hi-Res quality of its audio. */
internal object DownloadPreviewRules {
    fun totalBytes(status: Int, length: Long, contentRange: String?, encoding: String?): Long? {
        if (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true)) return null
        if (status == 206) {
            // Content-Length is the requested single byte, never the full file size.
            val range = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
                .matchEntire(contentRange?.trim().orEmpty()) ?: return null
            val first = range.groupValues[1].toLongOrNull() ?: return null
            val last = range.groupValues[2].toLongOrNull() ?: return null
            val total = range.groupValues[3].toLongOrNull() ?: return null
            return total.takeIf { first >= 0L && last >= first && total > last }
        }
        return length.takeIf { status in 200..299 && it > 0L }
    }

    fun declaredMime(value: String?): String? = value?.substringBefore(';')?.trim()?.lowercase()
        ?.takeUnless { it.isEmpty() || it in setOf("application/octet-stream", "binary/octet-stream") }
}
