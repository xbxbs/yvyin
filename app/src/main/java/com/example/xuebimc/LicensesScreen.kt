package com.example.xuebimc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val LicenseSecondary = Color(0xFFEBEBF5).copy(alpha = .6f)

private data class LicenseEntry(val asset: String, val title: String, val license: String)

private sealed interface LicenseLoad<out T> {
    data object Loading : LicenseLoad<Nothing>
    data class Ready<T>(val value: T) : LicenseLoad<T>
    data object Failed : LicenseLoad<Nothing>
}

@Composable
internal fun LicensesScreen(bottomInset: Dp, isActive: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedAsset by rememberSaveable { mutableStateOf<String?>(null) }
    var listRetry by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val licenses by produceState<LicenseLoad<List<LicenseEntry>>>(LicenseLoad.Loading, context, listRetry) {
        value = LicenseLoad.Loading
        value = try {
            LicenseLoad.Ready(withContext(Dispatchers.IO) {
                context.assets.list("licenses").orEmpty()
                    .filter { it.endsWith(".txt", ignoreCase = true) }
                    .sortedBy { it.lowercase(Locale.ROOT) }
                    .map(::licenseEntry)
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LicenseLoad.Failed
        }
    }
    val back: () -> Unit = { if (selectedAsset != null) selectedAsset = null else onBack() }
    BackHandler(isActive, back)
    CompositionLocalProvider(LocalIndication provides PressFadeIndication) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                LicenseButton(if (selectedAsset == null) "返回设置" else "返回开源许可", back)
                LicenseText(selectedAsset?.let(::licenseEntry)?.title ?: "开源许可", 34,
                    Modifier.padding(top = 2.dp, bottom = 8.dp).semantics { heading() },
                    Color.White, FontWeight.Bold)
                LicenseText(selectedAsset?.let(::licenseEntry)?.license ?: "随应用提供的组件与字体许可", 14,
                    Modifier.padding(bottom = 20.dp))
            }
            val asset = selectedAsset
            if (asset != null) {
                key(asset) { LicenseDetail(asset, bottomInset, Modifier.weight(1f)) }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomInset + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (val result = licenses) {
                        LicenseLoad.Loading -> item { LicenseText("正在读取许可列表…", 15) }
                        LicenseLoad.Failed -> item {
                            LicenseText("许可列表读取失败，请重试。", 15)
                            LicenseButton("重试", { listRetry++ })
                        }
                        is LicenseLoad.Ready -> {
                            if (result.value.isEmpty()) item { LicenseText("安装包中没有可读取的许可文本。", 15) }
                            items(result.value, key = { it.asset }) { entry ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF1C1C1E))
                                    .clickable(role = Role.Button, onClickLabel = "阅读许可") { selectedAsset = entry.asset }
                                    .heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        LicenseText(entry.title, 17, color = Color.White)
                                        LicenseText(entry.license, 13)
                                    }
                                    LicenseText("阅读", 14, color = LibraryAccent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseDetail(asset: String, bottomInset: Dp, modifier: Modifier) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val document by produceState<LicenseLoad<List<String>>>(LicenseLoad.Loading, context, asset, retry) {
        value = LicenseLoad.Loading
        value = try {
            LicenseLoad.Ready(withContext(Dispatchers.IO) {
                context.assets.open("licenses/$asset").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .trim().also { check(it.isNotEmpty()) }.split(Regex("\n\\s*\n"))
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LicenseLoad.Failed
        }
    }
    SelectionContainer(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (val result = document) {
                LicenseLoad.Loading -> item { LicenseText("正在读取许可文本…", 15) }
                LicenseLoad.Failed -> item {
                    LicenseText("许可文本读取失败，请重试。", 15)
                    LicenseButton("重试", { retry++ })
                }
                is LicenseLoad.Ready -> items(result.value.size) { index ->
                    LicenseText(result.value[index], 15, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun LicenseButton(label: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        LicenseText(label, 17, color = LibraryAccent)
    }
}

@Composable
private fun LicenseText(text: String, size: Int, modifier: Modifier = Modifier,
    color: Color = LicenseSecondary, weight: FontWeight = FontWeight.Normal) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size.sp,
        lineHeight = (size * 1.45f).sp, fontWeight = weight,
        fontFamily = if (weight == FontWeight.Bold) PlayerTypography.bold else PlayerTypography.latin))
}

private fun licenseEntry(asset: String): LicenseEntry = when (asset) {
    "AMLL-AGPL-3.0.txt" -> LicenseEntry(asset, "Apple Music-like Lyrics", "AGPL-3.0")
    "cupertino-Apache-2.0.txt" -> LicenseEntry(asset, "Compose Cupertino", "Apache-2.0")
    "haze-Apache-2.0.txt" -> LicenseEntry(asset, "Haze", "Apache-2.0")
    "inter-OFL.txt" -> LicenseEntry(asset, "Inter 字体", "SIL Open Font License 1.1")
    "jaudiotagger-LGPL-2.1.txt" -> LicenseEntry(asset, "jaudiotagger", "LGPL-2.1-or-later")
    "network-Apache-2.0.txt" -> LicenseEntry(asset, "OkHttp / Okio", "Apache-2.0")
    "notosanssc-OFL.txt" -> LicenseEntry(asset, "Noto Sans SC 字体", "SIL Open Font License 1.1")
    else -> LicenseEntry(asset, asset.removeSuffix(".txt"), "许可文本")
}
