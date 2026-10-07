package com.example.xuebimc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal

/** One flat information surface reused by the info menu and the audio-quality badge. */
@Composable
internal fun PlayerTrackInformation(track: Track, durationMs: Long, source: String, onChooseLyrics: () -> Unit) {
    val quality = track.audioQuality
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackArtwork(track, Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)).clearAndSetSemantics {}, requestSize = 128)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(track.title, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White, fontSize = 17.sp, lineHeight = 22.sp,
                    fontFamily = PlayerTypography.familyFor(track.title, medium = true)))
            BasicText(track.artist.ifBlank { "未知艺人" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White.copy(alpha = .7f), fontSize = 14.sp, lineHeight = 19.sp,
                    fontFamily = PlayerTypography.familyFor(track.artist)))
        }
    }
    val qualityLabel = quality.badgeLabel ?: if (quality.level == AudioQualityLevel.Lossy) "有损音频" else "音质待确认"
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerIcon(if (quality.isLossless) PlayerIconType.Lossless else PlayerIconType.Info,
            Modifier.size(18.dp), tint = Color.White.copy(alpha = .78f))
        Spacer(Modifier.width(8.dp))
        BasicText(qualityLabel, style = TextStyle(color = Color.White.copy(alpha = .9f),
            fontSize = 14.sp, lineHeight = 19.sp, fontFamily = PlayerTypography.latin))
    }
    SheetActionDivider()
    PlayerInformationRow("专辑", track.album.ifBlank { "未知专辑" }, stacked = true)
    SheetActionDivider()
    PlayerInformationRow("格式", quality.formatLabel)
    val depth = quality.bitsPerSample.takeIf { it > 0 }?.let { "$it bit" }
    val rate = quality.sampleRate.takeIf { it > 0 }?.let { hz ->
        val mega = hz >= 1_000_000
        BigDecimal.valueOf(hz.toLong()).divide(BigDecimal.valueOf(if (mega) 1_000_000L else 1000L))
            .stripTrailingZeros().toPlainString() + if (mega) " MHz" else " kHz"
    }
    PlayerInformationRow("位深", depth ?: "未知")
    PlayerInformationRow("采样率", rate ?: "未知")
    PlayerInformationRow("比特率", quality.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "未知")
    PlayerInformationRow("时长", formatPlaybackTime(durationMs))
    PlayerInformationRow("来源", source)
    PlayerInformationRow("歌词", if (track.performanceLines.isEmpty()) "无同步歌词" else "${track.performanceLines.size} 行")
    val qualityNote = "按文件编码与采样率判定；不代表原始母带或设备输出。" +
        if (quality.level == AudioQualityLevel.Lossless || quality.level == AudioQualityLevel.HiResLossless)
            "\nApple Music 分级：48 kHz 以内无损，高于 48 kHz 高解析度无损。" else ""
    BasicText(qualityNote,
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp),
        style = TextStyle(color = Color.White.copy(alpha = .68f), fontSize = 12.sp, lineHeight = 17.sp,
            fontFamily = PlayerTypography.latin))
    SheetActionSectionDivider()
    MusicDetailAction("选择歌词文件", PlayerIconType.Lyrics, onChooseLyrics)
}

@Composable
internal fun PlayerInformationRow(label: String, value: String, stacked: Boolean = false) {
    val fontScale = LocalDensity.current.fontScale
    val labelStyle = TextStyle(color = Color.White.copy(alpha = .68f),
        fontSize = 15.sp, lineHeight = 20.sp, fontFamily = PlayerTypography.latin)
    val valueStyle = TextStyle(color = Color.White.copy(alpha = .96f),
        fontSize = 15.sp, lineHeight = 20.sp, fontFamily = PlayerTypography.latin, fontFeatureSettings = "tnum")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        // Long metadata and accessibility text use the full measure, never a squeezed right column.
        if (stacked || fontScale > 1.2f || maxWidth < 250.dp || value.length > 32) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                BasicText(label, style = labelStyle)
                BasicText(value, style = valueStyle)
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(vertical = 9.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BasicText(label, Modifier.weight(.36f), style = labelStyle)
                BasicText(value, Modifier.weight(.64f), style = valueStyle.copy(textAlign = TextAlign.End))
            }
        }
    }
}

/** Shared disclosure row: icon at the leading edge, normal-weight text, quiet chevron. */
@Composable
internal fun MusicDetailAction(label: String, icon: PlayerIconType, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .background(Color.White.copy(alpha = when { pressed -> .12f; focused -> .08f; else -> 0f }))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerIcon(icon, Modifier.size(20.dp), tint = Color.White.copy(alpha = .72f))
        Spacer(Modifier.width(12.dp))
        BasicText(label, Modifier.weight(1f), style = TextStyle(color = Color.White,
            fontSize = 17.sp, lineHeight = 22.sp, fontFamily = PlayerTypography.latin))
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.size(16.dp).clearAndSetSemantics {}) {
            val tint = Color.White.copy(alpha = .55f)
            val stroke = 1.6.dp.toPx()
            drawLine(tint, Offset(size.width * .35f, size.height * .2f), Offset(size.width * .65f, size.height * .5f), stroke, StrokeCap.Round)
            drawLine(tint, Offset(size.width * .65f, size.height * .5f), Offset(size.width * .35f, size.height * .8f), stroke, StrokeCap.Round)
        }
    }
}
