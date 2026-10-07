package com.example.xuebimc

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared iOS-style modal card. It is centered so text fields remain visible above the IME. */
@Composable
internal fun AppSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    scrollable: Boolean = true,
    title: String = "歌曲操作",
    onShowingChanged: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(visible, onBack = onDismiss)
    val progress = remember { Animatable(0f) }
    val showing by remember(visible) { derivedStateOf { visible || progress.value > .001f } }
    LaunchedEffect(visible) {
        progress.animateTo(if (visible) 1f else 0f,
            spring(dampingRatio = 1f, stiffness = if (visible) 420f else 550f))
    }
    SideEffect { onShowingChanged(showing) }
    if (!showing) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableHeight = (maxHeight - 32.dp).coerceAtLeast(160.dp)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }
            .background(Color.Black.copy(alpha = .46f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "关闭菜单", onClick = onDismiss,
            ))
        Column(
            Modifier.align(Alignment.Center).imePadding().padding(16.dp)
                .widthIn(max = 520.dp).fillMaxWidth().heightIn(max = availableHeight)
                .graphicsLayer {
                    val p = progress.value
                    alpha = p
                    scaleX = .96f + .04f * p
                    scaleY = scaleX
                }
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF232226))
                .border(.5.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(22.dp))
                .semantics { paneTitle = title },
        ) {
            Column(
                Modifier.weight(1f, fill = false)
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(20.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun SheetActionRow(
    label: String,
    icon: PlayerIconType? = null,
    selected: Boolean = false,
    filledStar: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(Color.White.copy(alpha = when {
                pressed -> .12f
                focused -> .08f
                else -> 0f
            }))
            // An explicit indication overload also supports older Cupertino themes.
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (selected) LibraryAccent else Color.White.copy(alpha = .96f),
                fontSize = 17.sp, lineHeight = 22.sp, fontFamily = PlayerTypography.familyFor(label),
                fontWeight = FontWeight.Normal),
        )
        if (icon != null) {
            Spacer(Modifier.width(16.dp))
            PlayerIcon(icon, Modifier.size(20.dp),
                tint = if (selected) LibraryAccent else Color.White.copy(alpha = .72f),
                filled = selected || (icon == PlayerIconType.Star && filledStar))
        }
    }
}

/** Shared menu header; the playback page's independent typography stays untouched. */
@Composable
internal fun SheetTrackHeader(track: Track) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtwork(track, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).clearAndSetSemantics {}, 128)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            BasicText(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White.copy(alpha = .96f), fontSize = 15.sp, lineHeight = 20.sp,
                    fontFamily = PlayerTypography.familyFor(track.title, medium = true)))
            BasicText(track.artist, Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White.copy(alpha = .68f), fontSize = 13.sp, lineHeight = 18.sp,
                    fontFamily = PlayerTypography.familyFor(track.artist)))
        }
    }
}

@Composable
internal fun SheetActionGroup(content: @Composable ColumnScope.() -> Unit) {
    val insidePopup = LocalInsideContextMenu.current
    Column(
        Modifier.fillMaxWidth()
            .then(if (insidePopup) Modifier else Modifier
                .clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = .055f))),
        content = content,
    )
}

@Composable
internal fun SheetActionDivider() {
    // Keep the separator at one physical pixel on every display density.
    val pixel = with(LocalDensity.current) { 1f.toDp() }
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(pixel).drawBehind {
        drawLine(Color.White.copy(alpha = .10f), Offset(0f, size.height / 2f),
            Offset(size.width, size.height / 2f), strokeWidth = 1f)
    })
}

@Composable
internal fun SheetActionSectionDivider() {
    Box(Modifier.fillMaxWidth().height(6.dp).background(Color.Black.copy(alpha = .15f)))
}
