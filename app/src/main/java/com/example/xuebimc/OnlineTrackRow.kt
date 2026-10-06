package com.example.xuebimc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OnlineRowSecondary = Color.White.copy(alpha = .58f)
private val OnlineRowAccent = Color(0xFFFA586A)

@Composable
internal fun OnlineTrackRow(
    track: Track,
    source: OnlineSource?,
    isCurrent: Boolean,
    artworkRepository: OnlineMusicRepository,
    onPlay: (Track) -> Unit,
    onMenu: (Track) -> Unit,
    showSource: Boolean = true,
) {
    val title = track.title.ifBlank { track.displayName.ifBlank { "未命名歌曲" } }
    val artist = track.artist.takeUnless { it.isBlank() || it.equals("<unknown>", ignoreCase = true) } ?: "未知歌手"
    val sourceName = source?.let { it.name.ifBlank { it.id } }
        ?: track.sourceId?.takeIf { it.isNotBlank() } ?: "来源未标注"
    val sourceBadge = when {
        source?.enabled != true -> "$sourceName · 未启用"
        !source.supportsPlayback -> "$sourceName · 仅搜索"
        else -> sourceName
    }
    val anchor = rememberMenuAnchor()
    var resolvedArtworkUri by remember(track.stableKey, track.artworkUri) { mutableStateOf(track.artworkUri) }
    // Keep the latest playback URI/headers; enrichment owns only the artwork field.
    val artworkTrack = if (resolvedArtworkUri == track.artworkUri) track else track.copy(artworkUri = resolvedArtworkUri)
    LaunchedEffect(track.stableKey, track.artworkUri, source?.enabled) {
        if (track.artworkUri == null && source?.enabled == true) {
            val detailed = artworkRepository.resolveArtwork(track)
            if (detailed.stableKey == track.stableKey) resolvedArtworkUri = detailed.artworkUri
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (isCurrent) Color.White.copy(alpha = .055f) else Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = if (source?.supportsPlayback == true) "播放歌曲" else "查看音源限制") { onPlay(artworkTrack) }
                .semantics { selected = isCurrent; if (isCurrent) stateDescription = "当前曲目" }
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArtwork(artworkTrack, Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)).clearAndSetSemantics {}, requestSize = 128)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OnlineRowText(title, size = 16.sp, color = if (isCurrent) OnlineRowAccent else Color.White, medium = true)
                OnlineRowText(listOf(artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), size = 12.sp, color = OnlineRowSecondary)
                if (showSource || source?.enabled != true || !source.supportsPlayback) {
                    OnlineRowText(sourceBadge, size = 10.sp, color = OnlineRowSecondary)
                }
            }
            if (track.durationMs > 0L) OnlineRowText(onlineRowDuration(track.durationMs), Modifier.padding(start = 8.dp), size = 11.sp, color = OnlineRowSecondary)
            OnlineRowMenuButton("$title，更多操作", { anchor.second(); onMenu(artworkTrack) }, anchor.first)
        }
        Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(.5.dp).background(Color.White.copy(alpha = .10f)))
    }
}

@Composable
private fun OnlineRowMenuButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(21.dp).clearAndSetSemantics {}) {
            val unit = size.minDimension / 24f
            withTransform({
                translate((size.width - unit * 24f) / 2f, (size.height - unit * 24f) / 2f)
                scale(unit, unit, pivot = Offset.Zero)
            }) {
                listOf(5f, 12f, 19f).forEach { x -> drawCircle(OnlineRowAccent, 1.7f, Offset(x, 12f)) }
            }
        }
    }
}

@Composable
private fun OnlineRowText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    color: Color = Color.White,
    medium: Boolean = false,
) {
    BasicText(
        text, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color, fontSize = size, lineHeight = size * 1.35f,
            fontFamily = PlayerTypography.familyFor(text, medium), fontSynthesis = FontSynthesis.None,
            fontFeatureSettings = "tnum", textAlign = TextAlign.Start,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

private fun onlineRowDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1000
    val minutes = seconds / 60
    val tail = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$tail" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$tail"
}
