package com.example.xuebimc

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val PendingDeletionSaver = Saver<Track?, String>(
    save = { track -> track?.let { TrackJson.trackToJson(it).toString() } },
    restore = { encoded -> runCatching { TrackJson.fromJson(JSONObject(encoded)) }.getOrNull() },
)

internal fun shareLibraryTrack(context: Context, track: Track): String? = try {
    val intent = Intent(Intent.ACTION_SEND).apply {
        if (track.isOnline) {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${track.title} — ${track.artist}")
        } else {
            type = track.mimeType ?: "audio/*"
            putExtra(Intent.EXTRA_STREAM, track.uri)
            clipData = ClipData.newRawUri(track.title, track.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(Intent.createChooser(intent, "分享歌曲"))
    null
} catch (_: Exception) {
    "无法分享这个文件，请确认歌曲仍可访问。"
}

/** Called only after a separate, explicit app confirmation. No directory/file-name deletion. */
private fun deleteExactAudio(context: Context, uri: Uri): Boolean {
    require(uri.scheme == "content") { "请使用系统文件管理器删除此文件。" }
    return if (DocumentsContract.isDocumentUri(context, uri)) {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } else {
        require(uri.authority == MediaStore.AUTHORITY && ContentUris.parseId(uri) >= 0L) {
            "此来源不支持应用内删除，请使用文件管理器。"
        }
        context.contentResolver.delete(uri, null, null) > 0
    }
}

@Composable
internal fun TrackDeletionDialog(
    target: Track?,
    onDismiss: () -> Unit,
    onDeleted: (Track, Set<String>) -> Unit,
    resolveAliases: suspend (Track) -> Set<String>,
    onFeedback: (String) -> Unit,
    onShowingChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lastTarget by remember { mutableStateOf<Track?>(null) }
    var awaitingPermission by rememberSaveable(stateSaver = PendingDeletionSaver) { mutableStateOf<Track?>(null) }
    var awaitingAliases by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var systemDeletes by rememberSaveable { mutableStateOf(false) }
    var legacyRetry by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val reportDeleted by rememberUpdatedState(onDeleted)
    val reportFeedback by rememberUpdatedState(onFeedback)
    LaunchedEffect(target) { if (target != null) lastTarget = target }
    val shown = target ?: lastTarget
    val systemPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pending = awaitingPermission
        if (pending != null && result.resultCode == Activity.RESULT_OK) {
            if (systemDeletes) {
                // Android 11+'s consent dialog performs the deletion itself.
                awaitingPermission = null
                reportDeleted(pending, awaitingAliases.toSet())
            } else legacyRetry++ // Android 10 grants access; we still have to perform the delete.
        } else {
            awaitingPermission = null
            reportFeedback("已取消删除，文件未由本应用删除。")
        }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && awaitingPermission != null) legacyRetry++
        else { awaitingPermission = null; reportFeedback("未获得删除权限，文件未更改。") }
    }
    LaunchedEffect(legacyRetry) {
        if (legacyRetry == 0) return@LaunchedEffect
        val pending = awaitingPermission ?: return@LaunchedEffect
        try {
            val deleted = withContext(Dispatchers.IO) { deleteExactAudio(context, pending.uri) }
            if (deleted) reportDeleted(pending, awaitingAliases.toSet()) else reportFeedback("没有删除文件：文件可能已移动，或此目录只允许读取。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reportFeedback("仍无法删除，请在系统文件管理器中检查文件权限。") }
        finally { awaitingPermission = null }
    }
    AppSheet(visible = target != null, onDismiss = { if (!busy) onDismiss() },
        title = "删除歌曲", onShowingChanged = onShowingChanged) {
        shown?.let { track ->
            SheetTrackHeader(track)
            BasicText("将从设备中删除这首歌曲的音频文件，不只是移出播放列表。删除后无法在本应用内恢复。",
                Modifier.padding(vertical = 16.dp), style = TextStyle(color = Color(0xFFBDBDC4),
                    fontSize = 15.sp, lineHeight = 22.sp, fontFamily = PlayerTypography.latin))
            if (busy) MusicText("正在请求删除…", modifier = Modifier.padding(16.dp)) else {
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button,
                    onClick = {
                        if (!busy && !track.isOnline) scope.launch {
                            busy = true
                            try {
                                val aliases = resolveAliases(track)
                                awaitingAliases = ArrayList(aliases)
                                val mediaUri = withContext(Dispatchers.IO) {
                                    if (track.uri.authority == MediaStore.AUTHORITY) track.uri
                                    else if (Build.VERSION.SDK_INT >= 29) runCatching {
                                        MediaStore.getMediaUri(context, track.uri)
                                    }.getOrNull() else null
                                }
                                if (Build.VERSION.SDK_INT >= 30 && mediaUri != null) {
                                    val request = withContext(Dispatchers.IO) {
                                        require(ContentUris.parseId(mediaUri) >= 0L)
                                        MediaStore.createDeleteRequest(context.contentResolver, listOf(mediaUri))
                                    }
                                    awaitingPermission = track
                                    systemDeletes = true
                                    systemPermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
                                    onDismiss()
                                } else if (Build.VERSION.SDK_INT <= 28 && track.uri.authority == MediaStore.AUTHORITY &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                    awaitingPermission = track
                                    legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    onDismiss()
                                } else {
                                    val deleted = withContext(Dispatchers.IO) { deleteExactAudio(context, track.uri) }
                                    if (deleted) { reportDeleted(track, aliases); onDismiss() }
                                    else reportFeedback("文件未删除，请检查歌曲是否仍在原位置及目录写入权限。")
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                if (Build.VERSION.SDK_INT == 29 && error is RecoverableSecurityException) {
                                    awaitingPermission = track
                                    systemDeletes = false
                                    runCatching {
                                        systemPermission.launch(IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build())
                                        onDismiss()
                                    }.onFailure { awaitingPermission = null; reportFeedback("无法打开系统删除授权，文件未更改。") }
                                } else reportFeedback("无法删除：此目录可能只允许读取，请使用文件管理器检查。")
                            } finally { busy = false }
                        }
                    }).padding(16.dp)) {
                    BasicText("删除文件", style = TextStyle(color = Color(0xFFFF6961), fontSize = 17.sp,
                        fontFamily = PlayerTypography.medium))
                }
                SheetActionDivider()
                SheetActionRow("取消", onClick = onDismiss)
            }
        }
    }
}
