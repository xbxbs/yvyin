package com.example.xuebimc

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared, in-window presentation: opening a menu must not switch the display's refresh mode. */
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
        val availableHeight = (maxHeight - 56.dp).coerceAtLeast(120.dp)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }
                .background(Color.Black.copy(alpha = .38f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "关闭菜单", onClick = onDismiss,
            ))
            Column(
                Modifier.align(Alignment.BottomCenter).graphicsLayer {
                    translationY = (1f - progress.value) * size.height
                }.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 520.dp).fillMaxWidth().heightIn(max = availableHeight)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF232226))
                    .border(.5.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(28.dp))
                    .semantics { paneTitle = title }
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Box(
                    Modifier.fillMaxWidth().height(44.dp)
                        .clickable(role = Role.Button, onClickLabel = "关闭菜单", onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = .28f)))
                }
                Column(
                    Modifier.weight(1f, fill = false)
                        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
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
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (selected) LibraryAccent else Color.White.copy(alpha = .94f),
                fontSize = 17.sp, lineHeight = 22.sp, fontFamily = PlayerTypography.latin),
        )
        if (icon != null) {
            Spacer(Modifier.width(16.dp))
            PlayerIcon(icon, Modifier.size(20.dp),
                tint = if (selected) LibraryAccent else Color.White.copy(alpha = .66f), filled = selected)
        }
    }
}

@Composable
internal fun SheetActionGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().then(if (LocalInsideContextMenu.current) Modifier else
        Modifier.clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = .055f))), content = content)
}

@Composable
internal fun SheetActionDivider() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(.5.dp).background(Color.White.copy(alpha = .10f)))
}
