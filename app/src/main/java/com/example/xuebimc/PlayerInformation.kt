package com.example.xuebimc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackArtwork(track, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), requestSize = 128)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            MusicText(track.title, 17f, true, maxLines = 2)
            quality.badgeLabel?.let { label ->
                Row(Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = .10f))
                    .padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(PlayerIconType.Lossless, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    MusicText(label, 11f, true, maxLines = 1)
                }
            }
        }
    }
    SheetActionDivider()
    PlayerInformationRow("专辑", track.album.ifBlank { "未知专辑" })
    PlayerInformationRow("格式", quality.formatLabel)
    val depth = quality.bitsPerSample.takeIf { it > 0 }?.let { "$it bit" }
    val rate = quality.sampleRate.takeIf { it > 0 }?.let { hz ->
        val mega = hz >= 1_000_000
        BigDecimal.valueOf(hz.toLong()).divide(BigDecimal.valueOf(if (mega) 1_000_000L else 1000L))
            .stripTrailingZeros().toPlainString() + if (mega) " MHz" else " kHz"
    }
    PlayerInformationRow("位深 / 采样率", listOfNotNull(depth, rate).joinToString(" / ").ifBlank { "未知" })
    PlayerInformationRow("比特率", quality.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "未知")
    PlayerInformationRow("时长", formatPlaybackTime(durationMs))
    PlayerInformationRow("来源", source)
    PlayerInformationRow("歌词", if (track.performanceLines.isEmpty()) "无同步歌词" else "${track.performanceLines.size} 行")
    SheetActionSectionDivider()
    MusicDetailAction("选择歌词文件", PlayerIconType.Lyrics, onChooseLyrics)
}

@Composable
internal fun PlayerInformationRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(label, Modifier.width(98.dp), style = TextStyle(color = Color(0xFFEBEBF5).copy(alpha = .6f),
            fontSize = 15.sp, fontFamily = PlayerTypography.latin))
        BasicText(value, Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 20.sp,
                textAlign = TextAlign.End, fontFamily = PlayerTypography.latin, fontFeatureSettings = "tnum"))
    }
}

/** Shared disclosure row: icon at the leading edge, normal-weight text, quiet chevron. */
@Composable
internal fun MusicDetailAction(label: String, icon: PlayerIconType, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerIcon(icon, Modifier.size(20.dp), tint = Color.White.copy(alpha = .6f))
        Spacer(Modifier.width(12.dp))
        BasicText(label, Modifier.weight(1f), style = TextStyle(color = Color.White,
            fontSize = 17.sp, fontFamily = PlayerTypography.latin))
        MusicText("›", 21f)
    }
}
