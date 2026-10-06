package com.example.xuebimc

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

private val GlassSecondary = Color(0xFFEBEBF5).copy(alpha = .6f)
private val GlassInactive = Color(0xFFEBEBF5).copy(alpha = .6f)
private val GlassSelected = Color(0xFFFA2D48)
private val MiniPlayerRadius = 20.dp
private val MiniPlayerInset = 8.dp
private val TabBarRadius = 29.dp
private val TabBarInset = 4.dp
private val NavigationItemWidth = 76.dp
private val NavigationWidth = NavigationItemWidth * 3 + TabBarInset * 2

enum class MusicTab { Library, Online, Settings }

/**
 * Floating bottom chrome (iOS 26 style): a mini-player capsule above a tab capsule, both hovering
 * over the content with margins. Each capsule blurs only the slice of [backdrop] directly behind it.
 */
@Composable
fun GlassTabBar(
    backdrop: HazeState,
    track: Track?,
    isPlaying: Boolean,
    selectedTab: MusicTab,
    onSelectTab: (MusicTab) -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onHeightChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onExpand: () -> Unit = {},
) {
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Reserve the expanded inset even while compact: changing list padding would reverse its
    // scroll direction and cause a collapse/expand feedback loop near the final songs.
    val reservedInset = (if (track == null) 58.dp else 122.dp) + navigationBottom + 24.dp
    SideEffect { onHeightChange(reservedInset) }
    val collapse = animateFloatAsState(if (compact && track != null) 1f else 0f,
        spring(dampingRatio = .85f, stiffness = 380f), label = "bottomChromeCollapse")
    @Composable fun Tabs(modifier: Modifier = Modifier) {
        Row(
            modifier.width(NavigationWidth).height(58.dp)
                .glassCapsule(backdrop, RoundedCornerShape(TabBarRadius)).padding(TabBarInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassTab("资料库", GlassTabIcon.Library, selected = selectedTab == MusicTab.Library,
                onClick = { onSelectTab(MusicTab.Library) }, modifier = Modifier.width(NavigationItemWidth))
            GlassTab("在线", GlassTabIcon.Online, selected = selectedTab == MusicTab.Online,
                onClick = { onSelectTab(MusicTab.Online) }, modifier = Modifier.width(NavigationItemWidth))
            GlassTab("设置", GlassTabIcon.Settings, selected = selectedTab == MusicTab.Settings,
                onClick = { onSelectTab(MusicTab.Settings) }, modifier = Modifier.width(NavigationItemWidth))
        }
    }
    Column(
        modifier
            .widthIn(max = 520.dp)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (track == null) Tabs() else {
            Layout(
                modifier = Modifier.fillMaxWidth().clipToBounds(),
                content = {
                    Box(Modifier.glassCapsule(backdrop, RoundedCornerShape(MiniPlayerRadius))) {
                        // The same mini-player stays composed throughout the morph.
                        GlassMiniPlayer(track, isPlaying, onOpenPlayer, onTogglePlayback, onNext)
                    }
                    Tabs(if (compact) Modifier.clearAndSetSemantics {} else Modifier)
                    Box(
                        Modifier.musicGlassSurface(backdrop, CircleShape)
                            .clickable(enabled = compact, role = Role.Button, onClick = onExpand)
                            .then(if (compact) Modifier.semantics { contentDescription = "展开底部导航" }
                                else Modifier.clearAndSetSemantics {}),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlassIcon(when (selectedTab) {
                            MusicTab.Library -> GlassTabIcon.Library
                            MusicTab.Online -> GlassTabIcon.Online
                            MusicTab.Settings -> GlassTabIcon.Settings
                        }, Modifier.size(22.dp), GlassSelected)
                    }
                },
            ) { measurables, constraints ->
                val p = collapse.value.coerceIn(0f, 1f)
                val width = constraints.maxWidth
                val miniHeight = 56.dp.roundToPx()
                val tabsHeight = 58.dp.roundToPx()
                val gap = 8.dp.roundToPx()
                val circleWidth = 56.dp.roundToPx()
                val miniWidth = (width - ((circleWidth + gap) * p).roundToInt()).coerceAtLeast(1)
                val mini = measurables[0].measure(Constraints.fixed(miniWidth, miniHeight))
                val tabs = measurables[1].measure(Constraints.fixed(minOf(width, NavigationWidth.roundToPx()), tabsHeight))
                val circle = measurables[2].measure(Constraints.fixed(circleWidth, miniHeight))
                layout(width, miniHeight + ((tabsHeight + gap) * (1f - p)).roundToInt()) {
                    mini.placeRelative(0, 0)
                    tabs.placeRelativeWithLayer((width - tabs.width) / 2, miniHeight + gap) { alpha = 1f - p }
                    circle.placeRelativeWithLayer(width - circleWidth, 0) {
                        alpha = p
                        scaleX = .85f + .15f * p
                        scaleY = scaleX
                    }
                }
            }
        }
    }
}

/** One thin material with a fine edge; the mini player and tabs share the same sampling path. */
@Composable
private fun Modifier.glassCapsule(
    backdrop: HazeState,
    shape: RoundedCornerShape,
): Modifier {
    return this
        // Swallow touches on empty glass so they never reach the list underneath.
        .pointerInput(Unit) { detectTapGestures { } }
        .musicGlassSurface(backdrop, shape)
}

@Composable
private fun GlassMiniPlayer(
    track: Track,
    isPlaying: Boolean,
    onOpenPlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .clickable(role = Role.Button, onClickLabel = "打开播放器", onClick = onOpenPlayer)
                .semantics { stateDescription = if (isPlaying) "正在播放" else "已暂停" }
                .padding(start = MiniPlayerInset, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackArtwork(
                track, Modifier.size(40.dp).sharedMiniArtwork(track, MiniPlayerRadius - MiniPlayerInset)
                    .clip(RoundedCornerShape(MiniPlayerRadius - MiniPlayerInset)).clearAndSetSemantics {},
                requestSize = 144,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                GlassText(track.title.ifBlank { track.displayName.ifBlank { "未命名歌曲" } }, size = 14.sp, family = PlayerTypography.medium)
                GlassText(
                    track.artist.takeUnless { it.isBlank() || it.equals("<unknown>", ignoreCase = true) } ?: "未知歌手",
                    Modifier.padding(top = 2.dp), size = 12.sp, color = GlassSecondary,
                )
            }
        }
        GlassIconButton(if (isPlaying) GlassTabIcon.Pause else GlassTabIcon.Play, if (isPlaying) "暂停播放" else "继续播放", onTogglePlayback)
        GlassIconButton(GlassTabIcon.Next, "下一首", onNext)
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun GlassTab(label: String, icon: GlassTabIcon, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val color by animateColorAsState(if (selected) GlassSelected else GlassInactive,
        spring(dampingRatio = .85f, stiffness = 500f), label = "tabInk")
    val fill by animateColorAsState(if (selected) Color.White.copy(alpha = .065f) else Color.Transparent,
        spring(dampingRatio = .85f, stiffness = 500f), label = "tabFill")
    // The tap target fills its lane; only a small visual capsule sits behind the icon and label.
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(23.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(68.dp).height(46.dp).clip(RoundedCornerShape(23.dp)).background(fill),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlassIcon(icon, Modifier.size(20.dp), color)
            GlassText(label, Modifier.padding(top = 2.dp), size = 10.sp, color = color,
                family = PlayerTypography.medium, align = TextAlign.Center)
        }
    }
}

@Composable
private fun GlassIconButton(icon: GlassTabIcon, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { GlassIcon(icon, Modifier.size(22.dp), Color.White) }
}

@Composable
private fun GlassText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit,
    color: Color = Color.White,
    family: FontFamily = PlayerTypography.latin,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color, fontSize = size, lineHeight = size * 1.3f, fontFamily = family,
            fontSynthesis = FontSynthesis.None, textAlign = align,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

private enum class GlassTabIcon { Library, Online, Settings, Play, Pause, Next }

@Composable
private fun GlassIcon(icon: GlassTabIcon, modifier: Modifier, color: Color) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val unit = size.minDimension / 24f
        withTransform({
            translate((size.width - unit * 24f) / 2f, (size.height - unit * 24f) / 2f)
            scale(unit, unit, pivot = Offset.Zero)
        }) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (icon) {
                GlassTabIcon.Settings -> {
                    val gear = Path()
                    repeat(48) { step ->
                        val angle = -PI / 2 + step * PI / 24
                        val radius = if (step % 6 in 1..3) 9f else 7.3f
                        val x = 12f + cos(angle).toFloat() * radius
                        val y = 12f + sin(angle).toFloat() * radius
                        if (step == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                    }
                    gear.close()
                    drawPath(gear, color, style = stroke)
                    drawCircle(color, 2.8f, Offset(12f, 12f), style = stroke)
                }
                GlassTabIcon.Library -> {
                    // Stacked albums: a back sleeve and a front square holding a note.
                    drawLine(color, Offset(6f, 3.5f), Offset(18f, 3.5f), 1.7f, StrokeCap.Round)
                    drawRoundRect(color, Offset(3.5f, 6.5f), Size(17f, 14f), CornerRadius(3f), style = stroke)
                    drawLine(color, Offset(13.5f, 9.5f), Offset(13.5f, 16f), 1.7f, StrokeCap.Round)
                    drawLine(color, Offset(13.5f, 9.5f), Offset(16f, 10.5f), 1.7f, StrokeCap.Round)
                    drawCircle(color, 1.9f, Offset(11.6f, 16.2f))
                }
                GlassTabIcon.Online -> {
                    drawCircle(color, 8.5f, Offset(12f, 12f), style = stroke)
                    drawOval(color, Offset(8f, 3.5f), Size(8f, 17f), style = stroke)
                    drawLine(color, Offset(3.5f, 12f), Offset(20.5f, 12f), 1.7f, StrokeCap.Round)
                }
                GlassTabIcon.Play -> drawPath(
                    Path().apply { moveTo(7f, 4.5f); lineTo(19.5f, 12f); lineTo(7f, 19.5f); close() }, color,
                )
                GlassTabIcon.Pause -> {
                    drawRoundRect(color, Offset(6f, 4.5f), Size(4f, 15f), CornerRadius(1f))
                    drawRoundRect(color, Offset(14f, 4.5f), Size(4f, 15f), CornerRadius(1f))
                }
                GlassTabIcon.Next -> {
                    drawPath(Path().apply { moveTo(3f, 6f); lineTo(11f, 12f); lineTo(3f, 18f); close() }, color)
                    drawPath(Path().apply { moveTo(11f, 6f); lineTo(19f, 12f); lineTo(11f, 18f); close() }, color)
                    drawRoundRect(color, Offset(19f, 6f), Size(2f, 12f), CornerRadius(1f))
                }
            }
        }
    }
}
