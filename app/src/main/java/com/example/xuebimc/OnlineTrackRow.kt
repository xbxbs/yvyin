package com.example.xuebimc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlin.math.sin

private val OnlineRowSecondary = Color(0xFFEBEBF5).copy(alpha = .60f)
private val OnlineRowTertiary = Color(0xFFEBEBF5).copy(alpha = .30f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OnlineTrackRow(
    track: Track,
    source: OnlineSource?,
    isCurrent: Boolean,
    artworkRepository: OnlineMusicRepository,
    onPlay: (Track) -> Unit,
    onMenu: (Track) -> Unit,
    showSource: Boolean = true,
    isPlaying: Boolean = false,
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
    val haptics = LocalHapticFeedback.current
    val coverShape = RoundedCornerShape(6.dp)
    val coverBorder = with(LocalDensity.current) { .5f.toDp() }
    var resolvedArtworkUri by remember(track.stableKey, track.artworkUri) { mutableStateOf(track.artworkUri) }
    // Keep the latest playback URI/headers; enrichment owns only the artwork field.
    val artworkTrack = if (resolvedArtworkUri == track.artworkUri) track else track.copy(artworkUri = resolvedArtworkUri)
    LaunchedEffect(track.stableKey, track.artworkUri, source?.enabled, artworkRepository) {
        if (track.artworkUri == null && source?.enabled == true) {
            // A transient metadata miss is not a permanent row state. Cancellation still
            // propagates when a search replaces this row; never publish into its successor.
            repeat(2) { attempt ->
                if (attempt > 0) delay(1_000L)
                val detailed = artworkRepository.resolveArtwork(track)
                if (detailed.stableKey == track.stableKey && detailed.artworkUri != null) {
                    resolvedArtworkUri = detailed.artworkUri
                    return@LaunchedEffect
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (source?.supportsPlayback == true) "播放歌曲" else "查看音源限制",
                    onClick = { onPlay(artworkTrack) },
                    onLongClickLabel = "$title，更多操作",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        anchor.second()
                        onMenu(artworkTrack)
                    },
                )
                .semantics { selected = isCurrent; if (isCurrent) stateDescription = "当前曲目" }
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(coverShape)
                    .border(coverBorder, Color.White.copy(alpha = .12f), coverShape)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                TrackArtwork(artworkTrack, Modifier.size(48.dp), requestSize = 128)
                if (isCurrent) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .40f)))
                    OnlineRowEqualizer(isPlaying, Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OnlineRowText(title, size = 17.sp)
                OnlineRowText(listOf(artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), size = 15.sp, color = OnlineRowSecondary)
                if (showSource || source?.enabled != true || !source.supportsPlayback) {
                    OnlineRowText(sourceBadge, size = 10.sp, color = OnlineRowSecondary)
                }
            }
            if (track.durationMs > 0L) OnlineRowText(onlineRowDuration(track.durationMs), Modifier.padding(start = 8.dp), size = 12.sp, color = OnlineRowTertiary)
            OnlineRowMenuButton("$title，更多操作", { anchor.second(); onMenu(artworkTrack) }, anchor.first)
        }
        Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(.5.dp).background(Color.White.copy(alpha = .10f)))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun OnlineRowEqualizer(playing: Boolean, modifier: Modifier) {
    var phase by remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playing, lifecycle) {
        if (!playing) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val motionScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            if (motionScale <= 0f || !motionScale.isFinite()) return@repeatOnLifecycle
            var previous = withFrameNanos { it }
            while (true) {
                withFrameNanos { frame ->
                    val seconds = ((frame - previous) / 1_000_000_000f).coerceIn(0f, .05f)
                    previous = frame
                    phase = (phase + seconds * 5f / motionScale) % 6.2831855f
                }
            }
        }
    }
    Canvas(modifier) {
        val stroke = size.width * .12f
        repeat(4) { index ->
            val height = size.height * (.26f + .58f * (.5f + .5f * sin(phase + index * 1.7f)))
            val x = size.width * (.17f + index * .22f)
            drawLine(Color.White, Offset(x, (size.height - height) / 2),
                Offset(x, (size.height + height) / 2), stroke, StrokeCap.Round)
        }
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
                listOf(5f, 12f, 19f).forEach { x -> drawCircle(OnlineRowSecondary, 1.7f, Offset(x, 12f)) }
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
