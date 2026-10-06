package com.example.xuebimc

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

private data class MenuRequest(
    val owner: Any,
    val anchor: Rect?,
    val dismiss: State<() -> Unit>,
    val showing: State<(Boolean) -> Unit>,
    val header: State<(@Composable () -> Unit)?>,
    val content: State<@Composable ColumnScope.() -> Unit>,
)

internal class ContextMenuController {
    internal var lastAnchor: Rect? = null
    private var requestState by mutableStateOf<MenuRequest?>(null)
    private var visibleState by mutableStateOf(false)
    var isShowing by mutableStateOf(false)
        private set
    private fun present(request: MenuRequest) {
        if (requestState?.owner !== request.owner) requestState?.showing?.value?.invoke(false)
        requestState = request
        visibleState = true
    }
    private fun hide(owner: Any) { if (requestState?.owner === owner) visibleState = false }

    @Composable
    internal fun Register(
        visible: Boolean, anchor: Rect?, onDismiss: () -> Unit, onShowingChanged: (Boolean) -> Unit,
        header: (@Composable () -> Unit)?, content: @Composable ColumnScope.() -> Unit,
    ) {
        BackHandler(visible, onBack = onDismiss)
        val owner = remember { Any() }
        val dismiss = rememberUpdatedState(onDismiss)
        val showing = rememberUpdatedState(onShowingChanged)
        val currentHeader = rememberUpdatedState(header)
        val currentContent = rememberUpdatedState(content)
        DisposableEffect(visible, anchor) {
            if (visible) present(MenuRequest(owner, anchor ?: lastAnchor, dismiss, showing, currentHeader, currentContent))
            else hide(owner)
            onDispose { hide(owner) }
        }
    }

    @Composable
    internal fun Render(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val progress = remember { Animatable(0f) }
        val present by remember { derivedStateOf { visibleState || progress.value > .001f } }
        LaunchedEffect(visibleState) {
            progress.animateTo(if (visibleState) 1f else 0f,
                spring(dampingRatio = 1f, stiffness = if (visibleState) 550f else 700f))
        }
        val request = requestState
        SideEffect {
            isShowing = present
            request?.showing?.value?.invoke(present)
        }
        BackHandler(present) { request?.dismiss?.value?.invoke() }
        val scene = rememberGraphicsLayer()
        val material = rememberGraphicsLayer()
        val sceneCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
        val menuCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
        val blurRadius = with(density) { 26.dp.toPx() }
        val materialBlur = remember(blurRadius) { BlurEffect(blurRadius * .5f, blurRadius * .5f, TileMode.Clamp) }
        val depthBlur = remember(density) { with(density) { BlurEffect(3.dp.toPx(), 3.dp.toPx(), TileMode.Clamp) } }
        if (Build.VERSION.SDK_INT >= 31) material.renderEffect = materialBlur
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val margin = with(density) { 12.dp.toPx() }
            val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat() + margin
            val safeBottom = heightPx - WindowInsets.safeDrawing.getBottom(density) - margin
            Box(Modifier.fillMaxSize().then(if (present) Modifier.clearAndSetSemantics {} else Modifier).graphicsLayer {
                renderEffect = if (present && Build.VERSION.SDK_INT >= 31) depthBlur else null
            }.onGloballyPositioned { sceneCoords[0] = it }.drawWithContent {
                // Most frames have no menu. Do not record/replay the entire animated player
                // just to keep an unused popup backdrop alive (especially at 120 Hz).
                if (present) {
                    scene.record { this@drawWithContent.drawContent() }
                    drawLayer(scene)
                } else {
                    drawContent()
                }
            }) { content() }
            if (present && request != null) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }
                    .background(Color.Black.copy(alpha = .14f)).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "关闭菜单", onClick = { request.dismiss.value() },
                    ))
                val targetWidth = (maxWidth * .68f).coerceIn(240.dp, 360.dp).coerceAtMost(maxWidth - 24.dp)
                val menuWidth = with(density) { targetWidth.toPx() }
                var measuredHeight by remember { mutableIntStateOf(0) }
                val anchor = request.anchor ?: Rect(widthPx - margin, safeTop, widthPx - margin, safeTop + margin)
                val x = (anchor.right - menuWidth).coerceIn(margin, (widthPx - margin - menuWidth).coerceAtLeast(margin))
                val below = anchor.bottom + margin * .5f
                val top = if (below + measuredHeight <= safeBottom) below else anchor.top - measuredHeight - margin * .5f
                val y = top.coerceIn(safeTop, (safeBottom - measuredHeight).coerceAtLeast(safeTop))
                val pivot = TransformOrigin(((anchor.center.x - x) / menuWidth).coerceIn(0f, 1f),
                    if (y >= anchor.bottom) 0f else 1f)
                val shape = RoundedCornerShape(18.dp)
                Column(Modifier.width(targetWidth)
                    .heightIn(max = with(density) { (safeBottom - safeTop).coerceAtLeast(100f).toDp() })
                    .onSizeChanged { measuredHeight = it.height }
                    .graphicsLayer {
                        translationX = x
                        translationY = y
                        transformOrigin = pivot
                        scaleX = .94f + .06f * progress.value
                        scaleY = scaleX
                        alpha = progress.value
                        this.shape = shape
                        shadowElevation = 16.dp.toPx()
                    }
                    .onGloballyPositioned { menuCoords[0] = it }
                    .clip(shape).drawBehind {
                        val source = sceneCoords[0]
                        val own = menuCoords[0]
                        val available = Build.VERSION.SDK_INT >= 31 && source?.isAttached == true && own?.isAttached == true
                        if (available) {
                            val offset = source!!.localPositionOf(own!!, Offset.Zero)
                            val pad = ceil(blurRadius * 3f)
                            material.record(size = IntSize(ceil((size.width + pad * 2f) * .5f).toInt(),
                                ceil((size.height + pad * 2f) * .5f).toInt())) {
                                scale(.5f, pivot = Offset.Zero) {
                                    translate(pad - offset.x, pad - offset.y) { drawLayer(scene) }
                                }
                            }
                            translate(-pad, -pad) { scale(2f, pivot = Offset.Zero) { drawLayer(material) } }
                        }
                        drawRect(Color(0xFF28272C).copy(alpha = if (available) .75f else .98f))
                    }.border(.5.dp, Color.White.copy(alpha = .15f), shape)
                    .pointerInput(Unit) { detectTapGestures {} }
                    .verticalScroll(rememberScrollState()).semantics { paneTitle = "歌曲菜单" },
                ) {
                    CompositionLocalProvider(LocalInsideContextMenu provides true) {
                        request.header.value?.invoke()
                        request.content.value(this)
                    }
                }
            }
        }
    }
}

internal val LocalContextMenuController = staticCompositionLocalOf<ContextMenuController?> { null }
internal val LocalInsideContextMenu = staticCompositionLocalOf { false }

@Composable
internal fun AppContextMenuHost(content: @Composable () -> Unit) {
    val host = remember { ContextMenuController() }
    CompositionLocalProvider(LocalContextMenuController provides host) { host.Render(content) }
}

/** A portal into the single app window, never an Android PopupWindow or bottom drawer. */
@Composable
internal fun AppContextMenu(
    visible: Boolean,
    anchor: Rect?,
    onDismiss: () -> Unit,
    onShowingChanged: (Boolean) -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalContextMenuController.current
    if (host != null) host.Register(visible, anchor, onDismiss, onShowingChanged, header, content)
    else AppSheet(visible, onDismiss, onShowingChanged = onShowingChanged, content = content)
}

/** No snapshot writes while scrolling: anchor bounds are sampled only when opening the menu. */
@Composable
internal fun rememberMenuAnchor(): Pair<Modifier, () -> Unit> {
    val host = LocalContextMenuController.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return Modifier.onGloballyPositioned { coordinates[0] = it } to {
        host?.lastAnchor = coordinates[0]?.takeIf { it.isAttached }?.boundsInRoot()
    }
}
