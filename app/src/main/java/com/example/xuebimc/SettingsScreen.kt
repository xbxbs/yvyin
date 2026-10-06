package com.example.xuebimc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexzhirkevich.cupertino.CupertinoSwitch
import io.github.alexzhirkevich.cupertino.CupertinoSwitchDefaults
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import io.github.alexzhirkevich.cupertino.theme.darkColorScheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SettingsSecondary = Color(0xFFEBEBF5).copy(alpha = .6f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    sources: List<OnlineSource>,
    currentSourceId: String,
    bottomInset: Dp,
    onBack: () -> Unit,
    isActive: Boolean = true,
    onOverlayVisibilityChange: (Boolean) -> Unit = {},
    onDeveloperOptions: () -> Unit = {},
) {
    val values by preferences.values.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<String?>(null) }
    var pickerPending by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }
    val pathAnchor = rememberMenuAnchor()
    val qualityAnchor = rememberMenuAnchor()
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val source = sources.firstOrNull { it.id == currentSourceId }
    val requestedQuality = OnlinePlaybackQuality.fromLevel(values.onlineQuality) ?: OnlinePlaybackQuality.Standard
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try {
                val name = writableDownloadFolder(context, uri)
                preferences.setDownloadFolder(uri.toString(), name)
                folderError = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { folderError = "这个文件夹无法写入，下载位置未更改。" }
        }
    }
    LaunchedEffect(isActive) { if (!isActive) { menu = null; pickerPending = false } }
    BackHandler(isActive && menu == null, onBack = onBack)
    CupertinoTheme(colorScheme = darkColorScheme()) {
        LazyColumn(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = bottomInset + 24.dp)) {
            item(key = "settings:title") {
                BasicText("设置", Modifier.padding(bottom = 26.dp), style = TextStyle(
                    color = Color.White, fontSize = 34.sp, lineHeight = 40.sp,
                    fontFamily = PlayerTypography.bold, fontWeight = FontWeight.Bold))
            }
            item(key = "settings:preferences") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E))) {
                    PreferenceRow("下载路径", values.downloadFolderName, pathAnchor.first) { pathAnchor.second(); menu = "folder" }
                    PreferenceDivider()
                    PreferenceRow("在线播放音质", requestedQuality.label, qualityAnchor.first) { qualityAnchor.second(); menu = "quality" }
                    PreferenceDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PreferenceText("点歌时打开播放页", Modifier.weight(1f))
                        CupertinoSwitch(checked = values.autoOpenPlayer, onCheckedChange = preferences::setAutoOpenPlayer,
                            colors = CupertinoSwitchDefaults.colors(checkedTrackColor = LibraryAccent),
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "点歌时打开播放页" })
                    }
                    PreferenceDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PreferenceText("液态玻璃", Modifier.weight(1f))
                        CupertinoSwitch(checked = values.liquidGlass, onCheckedChange = preferences::setLiquidGlass,
                            colors = CupertinoSwitchDefaults.colors(checkedTrackColor = LibraryAccent),
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "液态玻璃" })
                    }
                }
            }
            folderError?.let { error -> item(key = "settings:error") {
                PreferenceText(error, Modifier.padding(top = 12.dp), color = SettingsSecondary, size = 13)
            } }
            item(key = "settings:version") {
                Box(Modifier.fillMaxWidth().padding(top = 26.dp).heightIn(min = 44.dp)
                    .combinedClickable(onClick = {}, onLongClick = onDeveloperOptions, onLongClickLabel = "打开开发者选项"),
                    contentAlignment = Alignment.Center) {
                    PreferenceText("余音 $version", color = SettingsSecondary, size = 12)
                }
            }
        }
        var lastMenu by remember { mutableStateOf("quality") }
        LaunchedEffect(menu) { menu?.let { lastMenu = it } }
        val shownMenu = menu ?: lastMenu
        AppContextMenu(visible = menu != null && isActive, anchor = null, onDismiss = { menu = null },
            onShowingChanged = { showing ->
                onOverlayVisibilityChange(showing)
                if (!showing && pickerPending && isActive) {
                    pickerPending = false
                    folderPicker.launch(values.downloadTreeUri?.let(Uri::parse))
                }
            },
            header = { PreferenceText(if (shownMenu == "folder") "下载路径" else "在线播放音质", Modifier.padding(16.dp)) },
        ) {
            if (shownMenu == "folder") {
                SheetActionRow("选择文件夹", PlayerIconType.Download) { pickerPending = true; menu = null }
                SheetActionDivider()
                SheetActionRow("恢复默认位置", PlayerIconType.Album, selected = values.downloadTreeUri == null) {
                    preferences.setDownloadFolder(null, AppPreferences.DEFAULT_DOWNLOAD_FOLDER)
                    folderError = null; menu = null
                }
            } else {
                OnlinePlaybackQuality.entries.forEachIndexed { index, quality ->
                    if (index > 0) SheetActionDivider()
                    val supported = source?.enabled == true && source.supportsPlayback && quality in source.supportedQualities
                    if (supported) SheetActionRow(quality.label, selected = quality == requestedQuality) {
                        preferences.setOnlineQuality(quality.level); menu = null
                    } else PreferenceText("${quality.label} · 当前音源不支持", Modifier.fillMaxWidth().padding(16.dp), SettingsSecondary, 15)
                }
                PreferenceText("${source?.name ?: "当前音源"} · 下一次在线播放生效", Modifier.padding(16.dp), SettingsSecondary, 12)
            }
        }
    }
}

@Composable
private fun PreferenceRow(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PreferenceText(label)
            PreferenceText(value, color = SettingsSecondary, size = 13)
        }
        PreferenceText("›", color = SettingsSecondary, size = 24)
    }
}

@Composable
private fun PreferenceDivider() {
    val pixel = with(androidx.compose.ui.platform.LocalDensity.current) { .5f.toDp() }
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(pixel).background(Color(0xFF545458).copy(alpha = .6f)))
}

@Composable
private fun PreferenceText(text: String, modifier: Modifier = Modifier, color: Color = Color.White, size: Int = 17) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size.sp,
        lineHeight = (size * 1.3f).sp, fontFamily = PlayerTypography.latin))
}

private suspend fun writableDownloadFolder(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
    val resolver = context.contentResolver
    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    val id = DocumentsContract.getTreeDocumentId(uri)
    val document = DocumentsContract.buildDocumentUriUsingTree(uri, id)
    val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS)
    val name = resolver.query(document, columns, null, null, null)?.use { cursor ->
        require(cursor.moveToFirst())
        require(cursor.getLong(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L)
        cursor.getString(0)
    } ?: error("无法读取文件夹")
    if (uri.authority == "com.android.externalstorage.documents" && ':' in id) {
        val volume = if (id.substringBefore(':') == "primary") "内部存储" else "存储卡"
        "$volume/${id.substringAfter(':')}".trimEnd('/')
    } else name.ifBlank { "所选文件夹" }
}
