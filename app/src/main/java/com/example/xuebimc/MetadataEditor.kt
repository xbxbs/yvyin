package com.example.xuebimc

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.MediaScannerConnection
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Package-scoped intents only. Never fall through to an unrelated editor or a store page. */
internal object MetadataEditor {
    private const val PACKAGE = "com.lonx.lyrico"
    var editedUri: Uri? = null
        private set

    fun open(context: Context, track: Track): String? {
        if (track.isOnline) return "请先下载这首歌曲，再编辑元数据。"
        val pm = context.packageManager
        if (runCatching { pm.getPackageInfo(PACKAGE, 0) }.isFailure) {
            return "未安装 Lyrico，无法编辑元数据。"
        }
        val edit = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(track.uri, track.mimeType ?: "audio/*")
            setPackage(PACKAGE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri(track.title, track.uri)
        }
        val target = edit.takeIf { it.resolveActivity(pm) != null } ?: pm.getLaunchIntentForPackage(PACKAGE)
            ?: return "Lyrico 没有可打开的页面。"
        return try {
            context.startActivity(target)
            editedUri = track.uri
            null
        } catch (_: SecurityException) {
            // A media URI may be read-only to this app. Let Lyrico request its own write access.
            val launch = pm.getLaunchIntentForPackage(PACKAGE) ?: return "无法打开 Lyrico。"
            runCatching { context.startActivity(launch) }.fold(
                onSuccess = { editedUri = track.uri; null },
                onFailure = { "无法打开 Lyrico，请检查应用是否已停用。" },
            )
        } catch (_: Exception) {
            "无法打开 Lyrico，请检查应用是否已停用。"
        }
    }

    fun consumeEditedUri(): Uri? = editedUri.also { editedUri = null }

    /** Ask MediaStore to reread the edited file, then refresh the app's repository. */
    suspend fun rescan(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val path = if (uri.scheme == "file") uri.path else runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        if (!path.isNullOrBlank()) withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                MediaScannerConnection.scanFile(context.applicationContext, arrayOf(path), null) { _, _ ->
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        Unit
    }
}
