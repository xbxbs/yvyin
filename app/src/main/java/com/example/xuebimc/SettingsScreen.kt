package com.example.xuebimc

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SettingsAccent = Color(0xFFFA2D48)
private val SettingsSecondary = Color(0xFFEBEBF5).copy(alpha = .6f)

@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    sources: List<OnlineSource>,
    hasAudioPermission: Boolean,
    scanning: Boolean,
    bottomInset: Dp,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    isActive: Boolean = true,
    onOverlayVisibilityChange: (Boolean) -> Unit = {},
) {
    val values by preferences.values.collectAsState()
    val context = LocalContext.current
    val appName = remember(context) { context.applicationInfo.loadLabel(context.packageManager).toString() }
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    var showSources by remember { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val sourceAnchor = rememberMenuAnchor()
    LaunchedEffect(isActive) { if (!isActive) showSources = false }
    val selectedSource = sources.firstOrNull { it.id == values.defaultOnlineSource }
    val sourceSummary = when {
        selectedSource == null -> "${values.defaultOnlineSource} · 当前不可用"
        !selectedSource.enabled -> "${selectedSource.name} · 当前未启用"
        !selectedSource.supportsPlayback -> "${selectedSource.name} · 仅搜索"
        else -> selectedSource.name
    }

    if (showLicenses) {
        SettingsLicenses(bottomInset, onBack = { showLicenses = false }, isActive = isActive)
        return
    }
    BackHandler(enabled = isActive && !showSources, onBack = onBack)
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = bottomInset + 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "settings:header") {
            SettingsText("设置", Modifier.padding(bottom = 4.dp), size = 34.sp, bold = true)
        }
        item(key = "settings:appearance") {
            SettingsGroup("外观与显示") {
                SettingsToggle("动态背景", "跟随封面缓慢流动；关闭后保持静态。", values.animatedBackground,
                    preferences::setAnimatedBackground)
                SettingsDivider()
                SettingsToggle("高刷新率优先", "向系统请求较高刷新率；实际值由设备、系统和省电策略决定。",
                    values.preferHighRefresh, preferences::setPreferHighRefresh)
            }
        }
        item(key = "settings:online") {
            SettingsGroup("在线音乐") {
                SettingsAction("默认搜索音源", sourceSummary, "选择", modifier = sourceAnchor.first) {
                    sourceAnchor.second()
                    showSources = true
                }
            }
        }
        item(key = "settings:local") {
            SettingsGroup("本地音乐") {
                SettingsAction("音频访问权限", if (hasAudioPermission) "已授权" else "未授权 · 扫描需要音频访问权限",
                    "系统设置", onClick = onOpenSystemSettings)
                SettingsDivider()
                SettingsAction(
                    title = "重新扫描音乐",
                    detail = when {
                        scanning -> "正在读取设备上的音频文件…"
                        !hasAudioPermission -> "请先在系统设置中授予音频访问权限。"
                        else -> "更新资料库中的本地歌曲。"
                    },
                    actionLabel = if (scanning) "扫描中…" else "扫描",
                    enabled = hasAudioPermission && !scanning,
                    modifier = if (scanning) Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    } else Modifier,
                    onClick = onScan,
                )
            }
        }
        item(key = "settings:about") {
            SettingsGroup("关于") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsText(appName, medium = true)
                    SettingsText(version?.let { "版本 $it" } ?: "版本信息不可用", size = 13.sp, color = SettingsSecondary)
                }
                SettingsDivider()
                SettingsAction("开源许可", "查看随应用附带的许可原文。", "查看") { showLicenses = true }
            }
        }
    }
    AppContextMenu(
        visible = showSources && isActive,
        anchor = null,
        onDismiss = { showSources = false },
        onShowingChanged = onOverlayVisibilityChange,
        header = {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsText("默认搜索音源", medium = true)
                SettingsText("用于在线搜索的默认来源；仅可选择已启用音源。", size = 12.sp, color = SettingsSecondary)
            }
        },
    ) {
        if (sources.isEmpty()) SettingsText("暂无可用音源", Modifier.padding(16.dp), color = SettingsSecondary)
        sources.forEachIndexed { index, source ->
            if (index > 0) SheetActionDivider()
            if (source.enabled) {
                SheetActionRow(
                    source.name + (if (source.supportsPlayback) "" else " · 仅搜索") +
                        (if (source.id == values.defaultOnlineSource) " · 当前" else ""),
                    PlayerIconType.Info,
                ) {
                    // Use the current configuration, never expose disabled adapters as selectable settings.
                    preferences.setDefaultOnlineSource(source.id)
                    showSources = false
                }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(16.dp)) {
                    SettingsText("${source.name} · 未启用", color = SettingsSecondary)
                    if (source.description.isNotBlank()) SettingsText(source.description, size = 12.sp, color = SettingsSecondary)
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onBack).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { SettingsText("返回", color = SettingsAccent) }
        Spacer(Modifier.width(12.dp))
        SettingsText(title, Modifier.weight(1f), size = 28.sp, medium = true, maxLines = 1)
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsText(title, Modifier.padding(start = 4.dp), size = 13.sp, color = SettingsSecondary)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .065f)),
            content = content)
    }
}

@Composable
private fun SettingsDivider() {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(.5.dp).background(Color.White.copy(alpha = .10f)))
}

@Composable
private fun SettingsToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val thumbOffset by animateDpAsState(if (checked) 23.dp else 3.dp, tween(160), label = "settings-toggle")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsText(title, medium = true)
            SettingsText(detail, size = 12.sp, color = SettingsSecondary)
        }
        Box(Modifier.size(48.dp, 28.dp).clip(CircleShape)
            .background(if (checked) SettingsAccent else Color.White.copy(alpha = .18f)).clearAndSetSemantics {}) {
            Box(Modifier.offset(x = thumbOffset, y = 3.dp).size(22.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun SettingsAction(
    title: String,
    detail: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsText(title, color = if (enabled) Color.White else SettingsSecondary, medium = true)
            if (detail.isNotBlank()) SettingsText(detail, size = 12.sp, color = SettingsSecondary)
        }
        SettingsText(actionLabel, size = 13.sp, color = if (enabled) SettingsAccent else SettingsSecondary, maxLines = 1)
    }
}

@Composable
private fun SettingsText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    color: Color = Color.White,
    medium: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    bold: Boolean = false,
) {
    BasicText(text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = TextStyle(
        color = color, fontSize = size, lineHeight = size * 1.4f,
        fontFamily = if (bold) PlayerTypography.bold else PlayerTypography.familyFor(text, medium),
        fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
        fontSynthesis = FontSynthesis.None,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    ))
}

@Composable
private fun SettingsLicenses(bottomInset: Dp, onBack: () -> Unit, isActive: Boolean) {
    val context = LocalContext.current.applicationContext
    var selectedFile by rememberSaveable { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var loading by remember(selectedFile) { mutableStateOf(true) }
    var failure by remember(selectedFile) { mutableStateOf<String?>(null) }
    var entries by remember(selectedFile) { mutableStateOf(emptyList<String>()) }
    val goBack: () -> Unit = { if (selectedFile != null) selectedFile = null else onBack() }
    BackHandler(enabled = isActive, onBack = goBack)
    LaunchedEffect(context, selectedFile, reload) {
        loading = true
        failure = null
        val file = selectedFile
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (file == null) context.assets.list("licenses").orEmpty().sorted()
                else context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
                    .split(Regex("\\r?\\n[\\t ]*\\r?\\n")).filter { it.isNotBlank() }
            }
        }
        loaded.onSuccess { entries = it }.onFailure { failure = "无法读取随应用附带的许可文件。" }
        loading = false
    }
    key(selectedFile) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "licenses:header") { SettingsHeader(if (selectedFile == null) "开源许可" else "许可原文", goBack) }
            selectedFile?.let { file ->
                item(key = "licenses:filename") { SettingsText(file, color = SettingsSecondary, size = 13.sp) }
            }
            when {
                loading -> item(key = "licenses:loading") {
                    SettingsText("正在读取…", Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    }, color = SettingsSecondary)
                }
                failure != null -> item(key = "licenses:failure") {
                    SettingsAction("读取失败", failure.orEmpty(), "重试") { reload++ }
                }
                entries.isEmpty() -> item(key = "licenses:empty") {
                    SettingsText("未找到许可文件。", color = SettingsSecondary)
                }
                selectedFile == null -> items(entries, key = { it }, contentType = { "license-file" }) { file ->
                    SettingsAction(file.substringBeforeLast('.'), "", "阅读") { selectedFile = file }
                }
                else -> items(entries, contentType = { "license-text" }) { paragraph ->
                    SelectionContainer { SettingsText(paragraph, size = 13.sp, color = Color.White.copy(alpha = .85f)) }
                }
            }
        }
    }
}
