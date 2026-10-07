package com.example.xuebimc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Resolution has immediate on-screen feedback even before DownloadManager assigns an id. */
internal data class DownloadPreparation(
    val key: String,
    val track: Track,
    val quality: OnlinePlaybackQuality,
    val embedCover: Boolean,
    val embedLyrics: Boolean,
    val error: String? = null,
)

private val DownloadSecondary = Color(0xFFB2B2B8)
private val DownloadWarning = Color(0xFFFFCB78)
private val FinishedDownloads = setOf(MusicDownloads.State.COMPLETE, MusicDownloads.State.COMPLETE_WITH_WARNINGS)
private val ActiveDownloads = setOf(MusicDownloads.State.WAITING, MusicDownloads.State.DOWNLOADING,
    MusicDownloads.State.EMBEDDING, MusicDownloads.State.COPYING)

@Composable
internal fun DownloadsScreen(
    preparations: List<DownloadPreparation>,
    bottomInset: Dp,
    isActive: Boolean,
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onOpen: (MusicDownloads.PendingDownload) -> Unit,
    onRetryPreparation: (DownloadPreparation) -> Unit,
    onDismissPreparation: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<MusicDownloads.PendingDownload>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var retrying by remember { mutableStateOf<String?>(null) }
    val update by MusicDownloads.updates.collectAsState()
    LaunchedEffect(isActive, lifecycle, update) {
        if (!isActive) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    history = withContext(Dispatchers.IO) { MusicDownloads.history(context) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { feedback = "暂时无法读取下载记录，请稍后重新进入。" }
                loaded = true
                delay(1_000L)
            }
        }
    }
    BackHandler(isActive, onBack)
    val active = history.filter { it.state in ActiveDownloads }
    val finished = history.filterNot { it.state in ActiveDownloads }
    LazyColumn(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomInset + 24.dp)) {
        item(key = "navigation") {
            DownloadButton("返回", onBack, Modifier.padding(bottom = 6.dp))
            DownloadText("下载管理", 34, Modifier.semantics { heading() }, Color.White, FontWeight.Bold)
            DownloadText(if (active.isNotEmpty() || preparations.any { it.error == null })
                "${active.size + preparations.count { it.error == null }} 项正在处理"
                else "${history.count { it.state in FinishedDownloads }} 首已下载", 14,
                Modifier.padding(top = 8.dp, bottom = 26.dp))
        }
        if (preparations.isNotEmpty() || active.isNotEmpty()) item(key = "active-heading") {
            DownloadText("正在下载", 20, Modifier.padding(bottom = 8.dp).semantics { heading() }, Color.White, FontWeight.SemiBold)
        }
        items(preparations, key = { "preparing:${it.key}" }) { request ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                DownloadText(request.track.title, 17, color = Color.White, maxLines = 2)
                DownloadText("${request.track.artist} · 请求音质：${request.quality.label}", 13, Modifier.padding(top = 4.dp))
                DownloadText(request.error ?: "正在获取下载地址与内嵌内容…", 14,
                    Modifier.padding(top = 10.dp), if (request.error == null) DownloadSecondary else DownloadWarning)
                if (request.error != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DownloadButton("重试", { onRetryPreparation(request) })
                    DownloadButton("移除提示", { onDismissPreparation(request.key) })
                }
            }
            DownloadDivider()
        }
        items(active, key = { it.token }) { task -> DownloadRecord(task, onOpen, null) }
        if (finished.isNotEmpty()) item(key = "history-heading") {
            DownloadText("下载记录", 20, Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
                Color.White, FontWeight.SemiBold)
        }
        items(finished, key = { it.token }) { task ->
            DownloadRecord(task, onOpen, if (task.state in setOf(MusicDownloads.State.RETRY, MusicDownloads.State.FAILED)) {
                {
                    if (retrying == null) scope.launch {
                        retrying = task.token
                        try {
                            val resumed = withContext(Dispatchers.IO) { MusicDownloads.retry(context, task.token) }
                            feedback = if (resumed) "已重新尝试保存此任务。" else "此任务无法继续，请重新选择歌曲下载。"
                            history = withContext(Dispatchers.IO) { MusicDownloads.history(context) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { feedback = "无法恢复任务，请检查下载目录的访问权限。" }
                        finally { retrying = null }
                    }
                }
            } else null, retrying == task.token)
        }
        if (history.isEmpty() && preparations.isEmpty()) item(key = "empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
                PlayerIcon(PlayerIconType.Download, Modifier.size(34.dp), tint = DownloadSecondary)
                DownloadText(if (loaded) "还没有下载记录" else "正在读取下载记录…", 20,
                    Modifier.padding(top = 20.dp), Color.White, FontWeight.SemiBold)
                if (loaded) {
                    DownloadText("在在线歌曲的更多菜单中选择下载。这里会显示进度、保存位置和处理结果。", 15,
                        Modifier.padding(top = 10.dp, bottom = 8.dp))
                    DownloadButton("查找歌曲", onBrowse)
                }
            }
        }
        feedback?.let { text -> item(key = "feedback") {
            DownloadText(text, 14, Modifier.padding(vertical = 16.dp), DownloadWarning)
        } }
    }
}

@Composable
private fun DownloadRecord(
    task: MusicDownloads.PendingDownload,
    onOpen: (MusicDownloads.PendingDownload) -> Unit,
    onRetry: (() -> Unit)?,
    retrying: Boolean = false,
) {
    val completed = task.state in FinishedDownloads
    val progress = when (task.state) {
        MusicDownloads.State.DOWNLOADING -> if (task.totalBytes > 0) (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f) else null
        MusicDownloads.State.COPYING -> if (task.outputBytes > 0) (task.processedBytes.toFloat() / task.outputBytes).coerceIn(0f, 1f) else null
        else -> null
    }
    val status = when (task.state) {
        MusicDownloads.State.WAITING -> "等待下载"
        MusicDownloads.State.DOWNLOADING -> "正在下载"
        MusicDownloads.State.EMBEDDING -> "正在内嵌封面与歌词"
        MusicDownloads.State.COPYING -> "正在保存到下载目录"
        MusicDownloads.State.RETRY -> "等待恢复保存"
        MusicDownloads.State.COMPLETE -> "已完成"
        MusicDownloads.State.COMPLETE_WITH_WARNINGS -> "已保存 · 内嵌未全部完成"
        MusicDownloads.State.FAILED -> "下载失败"
        MusicDownloads.State.CANCELLED -> "已取消"
        MusicDownloads.State.CONFLICT -> "保存冲突 · 原文件未覆盖"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        DownloadText(task.title.ifBlank { task.fileName }, 17, color = Color.White, maxLines = 2)
        DownloadText(listOf(task.artist, task.qualityLabel.takeIf { it.isNotBlank() }?.let { "请求音质：$it" }.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" · "),
            13, Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            DownloadText(status, 14, Modifier.weight(1f), if (task.error != null || task.warning != null) DownloadWarning else DownloadSecondary)
            if (progress != null) DownloadText("${(progress * 100).toInt()}%", 13, Modifier.padding(start = 12.dp))
        }
        if (progress != null) {
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF303034)).semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(LibraryAccent))
            }
        }
        if (task.state == MusicDownloads.State.DOWNLOADING) DownloadText(
            "${downloadBytes(task.downloadedBytes)} / ${if (task.totalBytes > 0) downloadBytes(task.totalBytes) else "大小未知"}",
            12, Modifier.padding(top = 6.dp))
        task.error?.takeIf { it.isNotBlank() }?.let { DownloadText(it, 13, Modifier.padding(top = 8.dp), DownloadWarning) }
        task.warning?.takeIf { it.isNotBlank() }?.let { DownloadText(it, 13, Modifier.padding(top = 8.dp), DownloadWarning) }
        DownloadText(task.folderName, 12, Modifier.padding(top = 8.dp), maxLines = 2)
        if (task.createdAt > 0) DownloadText(remember(task.createdAt) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.createdAt))
        }, 12, Modifier.padding(top = 4.dp))
        if (completed && task.documentUri != null) DownloadButton("播放", { onOpen(task) })
        else if (onRetry != null) DownloadButton(if (retrying) "正在恢复…" else "重试保存", onRetry, enabled = !retrying)
    }
    DownloadDivider()
}

@Composable
private fun DownloadButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.CenterStart) {
        DownloadText(label, 16, color = if (enabled) LibraryAccent else DownloadSecondary)
    }
}

@Composable
private fun DownloadDivider() {
    Box(Modifier.fillMaxWidth().height(.5.dp).background(Color(0xFF343438)))
}

@Composable
private fun DownloadText(text: String, size: Int, modifier: Modifier = Modifier,
    color: Color = DownloadSecondary, weight: FontWeight = FontWeight.Normal, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size.sp, lineHeight = (size * 1.35f).sp,
        fontFamily = if (weight >= FontWeight.SemiBold) PlayerTypography.bold else PlayerTypography.latin,
        fontWeight = weight), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private fun downloadBytes(value: Long): String = when {
    value < 0 -> "0 B"
    value < 1024L -> "$value B"
    value < 1024L * 1024L -> String.format(Locale.getDefault(), "%.0f KB", value / 1024.0)
    value < 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", value / (1024.0 * 1024.0))
    else -> String.format(Locale.getDefault(), "%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
}

@Composable
internal fun DownloadQualityMenu(
    track: Track?,
    sources: List<OnlineSource>,
    preferences: AppPreferenceValues,
    onDismiss: () -> Unit,
    onShowingChanged: (Boolean) -> Unit,
    onDownload: (PreparedMusicDownload) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { OnlineMusicRepository(context) }
    var lastTrack by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(track) { if (track != null) lastTrack = track }
    val displayed = track ?: lastTrack
    val source = sources.firstOrNull { it.id == displayed?.sourceId }
    val preferred = OnlinePlaybackQuality.fromLevel(preferences.downloadQuality)
    var previews by remember { mutableStateOf<Map<OnlinePlaybackQuality, DownloadQualityPreview>>(emptyMap()) }
    var selected by remember { mutableStateOf<OnlinePlaybackQuality?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var submitted by remember { mutableStateOf(false) }

    // The effect owns every request. Dismissal, track change or source capability change cancels
    // both the queue and active HTTP connections; late responses cannot update another track.
    LaunchedEffect(track?.stableKey, source, attempt) {
        val target = track ?: return@LaunchedEffect
        submitted = false
        val supported = if (source?.enabled == true && source.supportsDownload) source.supportedQualities else emptyList()
        selected = preferred?.takeIf { it in supported }
        previews = OnlinePlaybackQuality.entries.associateWith { quality ->
            if (quality in supported) DownloadQualityPreview.Pending
            else DownloadQualityPreview.Unavailable("当前音源不支持此音质")
        }
        coroutineScope {
            val requests = Semaphore(2)
            supported.forEach { quality -> launch {
                requests.withPermit {
                    val result = try {
                        DownloadQualityPreview.Available(repository.prepareDownload(target, quality))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        DownloadQualityPreview.Unavailable(failure.message?.take(160)
                            ?: "无法获取此音质，请稍后重试")
                    }
                    ensureActive()
                    previews = previews + (quality to result)
                }
            } }
        }
    }
    val selectedDownload = (previews[selected] as? DownloadQualityPreview.Available)?.prepared
        ?.takeIf { track != null && it.track.stableKey == track.stableKey }
    val loading = previews.values.any { it is DownloadQualityPreview.Pending }
    val failed = previews.any { (quality, result) ->
        quality in source?.supportedQualities.orEmpty() && result is DownloadQualityPreview.Unavailable
    }
    AppContextMenu(visible = track != null, anchor = null, onDismiss = onDismiss,
        onShowingChanged = { showing ->
            // Keep labels during the exit animation, then release all signed URLs from UI state.
            if (!showing && track == null) {
                previews = emptyMap()
                lastTrack = null
                selected = null
            }
            onShowingChanged(showing)
        }, header = { displayed?.let { SheetTrackHeader(it) } },
        presentation = ContextMenuPresentation.Information, title = "选择下载音质") {
        DownloadText("选择下载音质", 20,
            Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp).semantics { heading() },
            Color.White, FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DownloadText(source?.name ?: "音源未启用", 13, Modifier.weight(1f).padding(vertical = 12.dp))
            if (failed) DownloadButton("重新检查", { attempt++ }, enabled = !loading && !submitted)
        }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            OnlinePlaybackQuality.entries.forEachIndexed { index, quality ->
                if (index > 0) SheetActionDivider()
                DownloadQualityChoice(quality, previews[quality] ?: DownloadQualityPreview.Pending,
                    selected = selected == quality, enabled = track != null && !submitted,
                    onSelect = { selected = quality })
            }
        }
        DownloadText("${if (preferences.downloadEmbedCover) "内嵌封面" else "不内嵌封面"} · " +
            "${if (preferences.downloadEmbedLyrics) "内嵌歌词" else "不内嵌歌词"}", 12,
            Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp))
        DownloadText("文件大小不含内嵌内容。不可用的音质无法选择，下载不会自动降级。", 12,
            Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DownloadChoiceButton("取消", onDismiss, Modifier.weight(1f))
            DownloadChoiceButton(if (submitted) "正在加入…" else "下载", {
                selectedDownload?.let {
                    submitted = true
                    onDownload(it)
                    onDismiss()
                }
            }, Modifier.weight(1f), enabled = selectedDownload != null && !submitted, primary = true)
        }
    }
}

@Composable
private fun DownloadQualityChoice(
    quality: OnlinePlaybackQuality,
    preview: DownloadQualityPreview,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val available = preview is DownloadQualityPreview.Available
    val selectable = enabled && available
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val label = when (quality) {
        OnlinePlaybackQuality.Standard -> "标准音质"
        OnlinePlaybackQuality.High -> "高品质"
        OnlinePlaybackQuality.Lossless -> "无损音质"
        OnlinePlaybackQuality.HiRes -> "高解析度无损"
    }
    val detail = when (preview) {
        DownloadQualityPreview.Pending -> "正在获取格式与大小…"
        is DownloadQualityPreview.Available ->
            "${preview.prepared.formatLabel ?: "格式未知"} · ${preview.prepared.totalBytes?.let(::downloadChoiceBytes) ?: "大小未知"}"
        is DownloadQualityPreview.Unavailable -> "不可用 · ${preview.reason}"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
        .background(when {
            pressed || focused -> Color.White.copy(alpha = .08f)
            selected && available -> LibraryAccent.copy(alpha = .09f)
            else -> Color.Transparent
        })
        .selectable(selected = selected && available, enabled = selectable, role = Role.RadioButton,
            interactionSource = interaction, indication = null, onClick = onSelect)
        .semantics { stateDescription = detail }
        .padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            DownloadText(label, 17, color = if (available) Color.White else DownloadSecondary)
            DownloadText(detail, 13, Modifier.padding(top = 4.dp),
                color = if (preview is DownloadQualityPreview.Unavailable) DownloadWarning else DownloadSecondary)
        }
        Canvas(Modifier.size(22.dp)) {
            val checked = selected && available
            val color = if (checked) LibraryAccent else DownloadSecondary.copy(alpha = if (available) .7f else .3f)
            drawCircle(color, radius = size.minDimension / 2f - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            if (checked) drawCircle(color, radius = 5.dp.toPx())
        }
    }
}

@Composable
private fun DownloadChoiceButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val background = when {
        primary && enabled -> LibraryAccent
        primary -> Color.White.copy(alpha = .08f)
        pressed || focused -> Color.White.copy(alpha = .12f)
        else -> Color.White.copy(alpha = .06f)
    }
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .background(background.copy(alpha = if (primary && pressed) .75f else background.alpha))
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction,
            indication = null, onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        DownloadText(label, 16, color = if (enabled) Color.White else DownloadSecondary,
            weight = if (primary) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private fun downloadChoiceBytes(value: Long): String = when {
    value < 1024L * 1024L -> downloadBytes(value)
    value < 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f MB", value / (1024.0 * 1024.0))
    else -> String.format(Locale.getDefault(), "%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
}
