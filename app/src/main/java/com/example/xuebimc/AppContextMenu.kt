package com.example.xuebimc

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

private data class MenuRequest(
    val owner: Any,
    val anchor: Rect?,
    val dismiss: State<() -> Unit>,
    val showing: State<(Boolean) -> Unit>,
    val header: State<(@Composable () -> Unit)?>,
    val presentation: ContextMenuPresentation,
    val title: State<String>,
    val content: State<@Composable ColumnScope.() -> Unit>,
)

/** Actions stay attached to their trigger; longer information gets its own readable surface. */
internal enum class ContextMenuPresentation { Menu, Information }

internal class ContextMenuController {
    internal var pendingAnchor: Rect? = null
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
        header: (@Composable () -> Unit)?, presentation: ContextMenuPresentation, title: String,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        BackHandler(visible, onBack = onDismiss)
        val owner = remember { Any() }
        val dismiss = rememberUpdatedState(onDismiss)
        val showing = rememberUpdatedState(onShowingChanged)
        val currentHeader = rememberUpdatedState(header)
        val currentTitle = rememberUpdatedState(title)
        val currentContent = rememberUpdatedState(content)
        DisposableEffect(visible, anchor, presentation) {
            if (visible) {
                // Consume a button's sample once. A submenu keeps the currently presented origin.
                val origin = anchor ?: pendingAnchor ?: requestState?.takeIf { isShowing || visibleState }?.anchor
                pendingAnchor = null
                present(MenuRequest(owner, origin, dismiss, showing, currentHeader, presentation, currentTitle, currentContent))
            } else hide(owner)
            onDispose { hide(owner) }
        }
    }

    @Composable
    internal fun Render(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val progress = remember { Animatable(0f) }
        val progressVelocity = remember { floatArrayOf(0f) }
        val present by remember { derivedStateOf { visibleState || progress.value > .001f } }
        LaunchedEffect(visibleState) {
            progress.animateTo(if (visibleState) 1f else 0f,
                spring(dampingRatio = 1f, stiffness = if (visibleState) 550f else 700f),
                initialVelocity = progressVelocity[0],
            ) { progressVelocity[0] = velocity }
            progressVelocity[0] = 0f
        }
        val request = requestState
        SideEffect {
            isShowing = present
            request?.showing?.value?.invoke(present)
        }
        BackHandler(present) { request?.dismiss?.value?.invoke() }
        val backdrop = remember { HazeState() }
        val material = remember {
            HazeStyle(
                backgroundColor = Color(0xFF242426),
                tints = listOf(HazeTint(Color(0xFF242426).copy(alpha = .78f))),
                blurRadius = 28.dp,
                noiseFactor = 0f,
                fallbackTint = HazeTint(Color(0xFF242426).copy(alpha = .98f)),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val margin = with(density) { 12.dp.toPx() }
            val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat() + margin
            val safeBottom = heightPx - WindowInsets.safeDrawing.getBottom(density) - margin
            // The page stays sharp. Record it only while a popup needs its local glass material.
            Box(Modifier.fillMaxSize().then(if (present) {
                Modifier.clearAndSetSemantics {}.hazeSource(backdrop)
            } else Modifier)) { content() }
            if (present && request != null) {
                val information = request.presentation == ContextMenuPresentation.Information
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }
                    .background(Color.Black.copy(alpha = if (information) .28f else .12f)).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "关闭菜单", onClick = { request.dismiss.value() },
                    ))
                val targetWidth = (if (information) 344.dp else 280.dp).coerceAtMost(maxWidth - 32.dp)
                val menuWidth = with(density) { targetWidth.toPx() }
                var measuredHeight by remember { mutableIntStateOf(0) }
                val pull = remember(request.owner) { Animatable(0f) }
                val anchor = request.anchor ?: Rect(widthPx - margin, safeTop, widthPx - margin, safeTop + margin)
                val x = if (information) (widthPx - menuWidth) / 2f else
                    (anchor.right - menuWidth).coerceIn(margin, (widthPx - margin - menuWidth).coerceAtLeast(margin))
                val below = anchor.bottom + margin * .5f
                val top = if (below + measuredHeight <= safeBottom) below else anchor.top - measuredHeight - margin * .5f
                val y = if (information) safeTop + (safeBottom - safeTop - measuredHeight).coerceAtLeast(0f) / 2f else
                    top.coerceIn(safeTop, (safeBottom - measuredHeight).coerceAtLeast(safeTop))
                val pivot = if (information) TransformOrigin.Center else
                    TransformOrigin(((anchor.center.x - x) / menuWidth).coerceIn(0f, 1f),
                        ((anchor.center.y - y) / measuredHeight.coerceAtLeast(1)).coerceIn(0f, 1f))
                val shape = RoundedCornerShape(if (information) 22.dp else 16.dp)
                val menuScrollState = key(request.owner, request.presentation, request.title.value) {
                    rememberScrollState()
                }
                Column(Modifier.width(targetWidth)
                    .heightIn(max = with(density) {
                        ((safeBottom - safeTop) * if (information) .86f else .74f).coerceAtLeast(100f).toDp()
                    })
                    .onSizeChanged { measuredHeight = it.height }
                    .graphicsLayer {
                        val p = progress.value
                        translationX = x + if (information) 0f else
                            (anchor.center.x - x - pivot.pivotFractionX * menuWidth) * (1f - p)
                        translationY = y + pull.value + if (information) 0f else
                            (anchor.center.y - y - pivot.pivotFractionY * measuredHeight) * (1f - p)
                        transformOrigin = pivot
                        scaleX = .94f + .06f * p
                        scaleY = scaleX
                        alpha = p
                        this.shape = shape
                        shadowElevation = 12.dp.toPx()
                    }
                    .clip(shape).hazeEffect(backdrop, material)
                    .menuDismissGesture(pull) { request.dismiss.value() }
                    .pointerInput(Unit) { detectTapGestures {} }
                    .verticalScroll(menuScrollState).semantics { paneTitle = request.title.value },
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

/** Only unconsumed downward scroll at the content's top can move the popup. */
@Composable
private fun Modifier.menuDismissGesture(
    pull: Animatable<Float, AnimationVector1D>,
    onDismiss: () -> Unit,
): Modifier {
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val gestureState = remember(pull) { booleanArrayOf(false, false) }
    val connection = remember(pull, scope, gestureState) {
        object : NestedScrollConnection {
            fun move(delta: Float): Offset {
                if (!gestureState[0] || delta == 0f) return Offset.Zero
                val target = (pull.value + delta).coerceAtLeast(0f)
                val consumed = target - pull.value
                if (consumed != 0f) gestureState[1] = true
                scope.launch(start = CoroutineStart.UNDISPATCHED) { pull.snapTo(target) }
                return Offset(0f, consumed)
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && pull.value > 0f) move(available.y) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f) move(available.y) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (pull.value > 0f) Velocity(0f, available.y) else Velocity.Zero
        }
    }
    return nestedScroll(connection).pointerInput(pull, threshold) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gestureState[0] = true
            gestureState[1] = false
            scope.launch(start = CoroutineStart.UNDISPATCHED) { pull.stop() }
            val tracker = VelocityTracker().apply { addPointerInputChange(down) }
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) }) break
                    tracker.addPointerInputChange(change)
                    if (!change.pressed) {
                        released = change.previousPressed
                        break
                    }
                }
            } finally {
                gestureState[0] = false
                if (pull.value > 0f) {
                    val velocity = if (released) tracker.calculateVelocity().y else 0f
                    if (released && gestureState[1] && velocity >= 0f && pull.value + velocity * .12f >= threshold) dismiss()
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        pull.animateTo(0f, spring(dampingRatio = .9f, stiffness = 600f), initialVelocity = velocity)
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
    presentation: ContextMenuPresentation = ContextMenuPresentation.Menu,
    title: String = "歌曲菜单",
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalContextMenuController.current
    if (host != null) host.Register(visible, anchor, onDismiss, onShowingChanged, header, presentation, title, content)
    else AppSheet(visible, onDismiss, title = title, onShowingChanged = onShowingChanged) {
        header?.invoke()
        content()
    }
}

/** No snapshot writes while scrolling: anchor bounds are sampled only when opening the menu. */
@Composable
internal fun rememberMenuAnchor(): Pair<Modifier, () -> Unit> {
    val host = LocalContextMenuController.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return Modifier.onGloballyPositioned { coordinates[0] = it } to {
        host?.pendingAnchor = coordinates[0]?.takeIf { it.isAttached }?.boundsInRoot()
    }
}
