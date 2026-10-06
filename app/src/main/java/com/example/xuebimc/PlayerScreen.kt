package com.example.xuebimc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    title: String,
    artist: String,
    positionMs: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    volume: Float,
    isFavorite: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onFavorite: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onMore: () -> Unit,
    onOutput: () -> Unit,
    lyricsVisible: Boolean,
    controlsVisible: Boolean,
    onRevealControls: () -> Unit,
    lyricContent: @Composable (Modifier, Float) -> Unit,
    track: Track? = null,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onDismissDrag: (Float) -> Unit = {},
    onDismissRelease: (Float) -> Unit = {},
    onDismissCancel: () -> Unit = {},
    queueVisible: Boolean = false,
    queueContent: @Composable (Modifier, Float) -> Unit = { _, _ -> },
    onQuality: () -> Unit = {},
    playbackItemKey: String? = track?.stableKey,
) {
    val contextMenuOpen = LocalContextMenuController.current?.isShowing == true
    val audioQuality = remember(track?.codecMimeType, track?.mimeType, track?.sampleRate, track?.bits, track?.bitrate) {
        track?.audioQuality
    }
    val qualityLabel = audioQuality?.badgeLabel
    val detailVisible = lyricsVisible || queueVisible
    val showLyrics = lyricsVisible && !queueVisible
    // Shared geometry and queue opacity: x = detail, y = queue. The original
    // cover/lyric chrome retains its independent timing and easing below.
    val transition = remember {
        Animatable(Offset(if (detailVisible) 1f else 0f, if (queueVisible) 1f else 0f), Offset.VectorConverter)
    }
    val transitionVelocity = remember { arrayOf(Offset.Zero) }
    val mainChrome = remember { Animatable(if (detailVisible) 0f else 1f) }
    val compactChrome = remember { Animatable(if (showLyrics) 1f else 0f) }
    val lyricReveal = remember { Animatable(if (showLyrics) 1f else 0f) }
    val chromeEasing = remember { CubicBezierEasing(.25f, .1f, .25f, 1f) }
    val queueReveal: () -> Float = remember(transition) {
        { (2f * transition.value.y - 1f).coerceIn(0f, 1f) }
    }
    val compactMounted by remember { derivedStateOf { compactChrome.value > .001f } }
    val queueMounted by remember { derivedStateOf { queueReveal() > .001f } }
    val controls = remember { Animatable(if (queueVisible || !lyricsVisible || controlsVisible) 1f else 0f) }
    val controlsVelocity = remember { floatArrayOf(0f) }
    val revealControls by rememberUpdatedState(onRevealControls)
    val showControls = queueVisible || !lyricsVisible || controlsVisible
    LaunchedEffect(detailVisible, queueVisible, showLyrics) {
        launch {
            mainChrome.animateTo(
                if (detailVisible) 0f else 1f,
                tween(durationMillis = 220, easing = chromeEasing)
            )
        }
        launch {
            compactChrome.animateTo(
                if (showLyrics) 1f else 0f,
                tween(
                    durationMillis = 220,
                    delayMillis = if (showLyrics) 100 else 0,
                    easing = chromeEasing
                )
            )
        }
        launch {
            lyricReveal.animateTo(
                if (showLyrics) 1f else 0f,
                tween(
                    durationMillis = 340,
                    delayMillis = if (showLyrics) 160 else 0,
                    easing = chromeEasing
                )
            )
        }
        transition.animateTo(
            Offset(if (detailVisible) 1f else 0f, if (queueVisible) 1f else 0f),
            spring(dampingRatio = 1.06066f, stiffness = 200f),
            initialVelocity = transitionVelocity[0],
        ) { transitionVelocity[0] = velocity }
        transitionVelocity[0] = Offset.Zero
    }
    LaunchedEffect(showControls) {
        controls.animateTo(
            if (showControls) 1f else 0f,
            spring(dampingRatio = 1.06066f, stiffness = 200f),
            initialVelocity = controlsVelocity[0]
        ) { controlsVelocity[0] = velocity }
        controlsVelocity[0] = 0f
    }
    val density = LocalDensity.current
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val controlsPresent by remember { derivedStateOf { controls.value > .05f } }
    val controlsMounted by remember { derivedStateOf { controls.value > .001f } }
    val favoritePresent by remember { derivedStateOf { transition.value.x < .05f } }
    // Share only the full cover. Never lift the compact lyric header or an invisible queue cover.
    val fullCoverAtRest by remember {
        derivedStateOf { transition.value.x <= .001f && transition.value.y <= .001f }
    }
    val controlsEnabled = showControls && controlsPresent && !contextMenuOpen
    val seekGestureGuard = remember { PlayerSeekGestureGuard() }
    val seekGesture = PlayerSeekGesture(
        key = PlayerSeekGestureKey(playbackItemKey, durationMs, lyricsVisible, queueVisible),
        guard = seekGestureGuard,
        positionMs = positionMs,
        onSeek = onSeek,
        onRevealControls = onRevealControls,
    )
    val marqueeEnabled = (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f
    BoxWithConstraints(
        Modifier.fillMaxSize().sharedArtworkPlayerViewport().background(Color(0xFF241F2C))
            .pointerInput(showLyrics, seekGestureGuard) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        seekGestureGuard.observe(event)
                        if (showLyrics && event.changes.any { it.pressed && !it.previousPressed }) {
                            revealControls()
                        }
                    }
                }
            }
    ) {
        val pageWidth = maxWidth
        val pageHeight = (maxHeight - navigationBottom).coerceAtLeast(1.dp)
        fun designSize(value: Float) = pageWidth * (value / 390f)
        fun designText(value: Float) = with(density) { designSize(value).toSp() }
        val fullCoverSize = minOf(pageWidth * .858f, pageHeight * .405f)
        val compactCoverSize = designSize(52f)
        val compactTop = pageHeight * .082f
        val softWhite = Color.White.copy(alpha = .58f)
        MusicBackdrop(track, Modifier.fillMaxSize(), motionEnabled = isPlaying && !contextMenuOpen)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .08f)))
        // Stage z-order is local to this group; neither stage can overtake the controls.
        Box(Modifier.fillMaxSize()) {
            PlayerLyricStage(pageWidth, pageHeight, showLyrics, lyricReveal, controls, lyricContent)
            PlayerQueueStage(pageWidth, pageHeight, queueVisible, queueReveal, queueContent)
        }
        val grabberWidth = (pageWidth * .22f).coerceAtLeast(48.dp)
        Box(
            Modifier.size(grabberWidth, 48.dp)
                .graphicsLayer {
                    alpha = if (contextMenuOpen) 0f else 1f
                    val progress = transition.value.x.coerceIn(0f, 1f)
                    translationX = ((pageWidth - grabberWidth) / 2f).toPx()
                    translationY = (pageHeight * (.074f - .014f * progress) - 24.dp).toPx()
                }
                .playerDismissDrag(onDismissDrag, onDismissRelease, onDismissCancel)
                .semantics { contentDescription = "收起播放器" }
                .clickable(role = Role.Button, onClickLabel = "收起", onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(pageWidth * .145f, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = .35f)))
        }
        TrackArtwork(
            track,
            Modifier.size(fullCoverSize).graphicsLayer {
                val progress = transition.value.x.coerceIn(0f, 1f)
                alpha = (1f - 2f * transition.value.y).coerceIn(0f, 1f)
                val coverScale = 1f + (compactCoverSize / fullCoverSize - 1f) * progress
                val startX = (pageWidth - fullCoverSize) / 2
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = (startX + (pageWidth * .089f - startX) * progress).toPx()
                translationY = (pageHeight * (.138f - .056f * progress)).toPx()
                scaleX = coverScale
                scaleY = coverScale
                val compactRadius = designSize(7f)
                shape = RoundedCornerShape((9.dp + (compactRadius - 9.dp) * progress) / coverScale)
                clip = true
            }.sharedPlayerArtwork(
                track = track,
                fullBounds = with(density) {
                    Rect(
                        Offset(((pageWidth - fullCoverSize) / 2f).toPx(), (pageHeight * .138f).toPx()),
                        Size(fullCoverSize.toPx(), fullCoverSize.toPx()),
                    )
                },
                enabled = !detailVisible && fullCoverAtRest,
            ).then(if (queueVisible) Modifier.clearAndSetSemantics { } else Modifier),
            requestSize = 512,
        )
        // Keep the title clear of the favourite / more controls during the compact transition.
        // Long titles still marquee; they must never run underneath a tappable button.
        val titleWidth = pageWidth * .59f
        val baseTitleSize = if (PlayerTypography.isChinese(title)) 18.5f else 20.5f
        val titleFamily = PlayerTypography.familyFor(title, medium = true)
        val titleStyle = TextStyle(
            color = Color.White, fontSize = designText(baseTitleSize),
            fontFamily = titleFamily, fontWeight = FontWeight.Normal,
            fontSynthesis = FontSynthesis.None,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            letterSpacing = designText(-.25f), lineHeight = designText(25f)
        )
        BasicText(
            title,
            Modifier.width(titleWidth).graphicsLayer {
                val progress = transition.value.x.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = (pageWidth * (.067f + .243f * progress)).toPx()
                translationY = (pageHeight * (.570f - .470f * progress)).toPx()
                scaleX = 1f + (16f / baseTitleSize - 1f) * progress
                scaleY = scaleX
                alpha = mainChrome.value.coerceIn(0f, 1f) * (1f - 2f * transition.value.y).coerceIn(0f, 1f)
            }.then(
                if (marqueeEnabled && !detailVisible) Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                else Modifier
            ).then(if (detailVisible) Modifier.clearAndSetSemantics { } else Modifier),
            style = titleStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
        )
        val artistSize = if (PlayerTypography.isChinese(artist)) 18f else 20f
        BasicText(
            artist,
            Modifier.width(titleWidth).graphicsLayer {
                val progress = transition.value.x.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = (pageWidth * (.067f + .243f * progress)).toPx()
                translationY = (
                    pageHeight * (.600f - .500f * progress) + designSize(26f) * progress
                    ).toPx()
                scaleX = 1f + (15f / artistSize - 1f) * progress
                scaleY = scaleX
                alpha = .49f * mainChrome.value.coerceIn(0f, 1f) * (1f - 2f * transition.value.y).coerceIn(0f, 1f)
            }.then(if (detailVisible) Modifier.clearAndSetSemantics { } else Modifier),
            style = TextStyle(
                color = Color.White, fontSize = designText(artistSize), lineHeight = designText(25f),
                fontFamily = PlayerTypography.familyFor(artist), fontWeight = FontWeight.Normal,
                fontSynthesis = FontSynthesis.None, platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
        )
        if (showLyrics || compactMounted) {
            Column(
                Modifier.offset(
                    x = pageWidth * .255f,
                    y = compactTop + (compactCoverSize - designSize(44f)) / 2f
                ).width(pageWidth * .485f).graphicsLayer {
                    alpha = compactChrome.value.coerceIn(0f, 1f) *
                        (1f - 2f * transition.value.y).coerceIn(0f, 1f)
                    translationY = designSize(6f).toPx() * (1f - alpha)
                }.then(if (!showLyrics) Modifier.clearAndSetSemantics { } else Modifier)
            ) {
                BasicText(
                    title,
                    modifier = if (marqueeEnabled && showLyrics) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
                    style = TextStyle(
                        color = Color.White.copy(alpha = .94f),
                        fontSize = designText(17f), lineHeight = designText(22f),
                        fontFamily = PlayerTypography.familyFor(title),
                        fontSynthesis = FontSynthesis.None,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
                )
                BasicText(
                    artist,
                    style = TextStyle(
                        color = Color.White.copy(alpha = .56f),
                        fontSize = designText(17f), lineHeight = designText(22f),
                        fontFamily = PlayerTypography.familyFor(artist),
                        fontSynthesis = FontSynthesis.None,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
                )
            }
        }
        PlayerButton(
            PlayerIconType.Star, if (isFavorite) "取消收藏" else "收藏", onFavorite,
            Modifier.offset(pageWidth * .791f - 24.dp, pageHeight * .598f - 24.dp)
                .graphicsLayer {
                    alpha = mainChrome.value.coerceIn(0f, 1f) *
                        (1f - 2f * transition.value.y).coerceIn(0f, 1f)
                },
            iconSize = designSize(20f), discSize = pageWidth * .073f, filled = isFavorite,
            enabled = !detailVisible && favoritePresent
        )
        PlayerButton(
            PlayerIconType.More, "更多", onMore,
            Modifier.offset(pageWidth * .899f - 24.dp, pageHeight * .598f - 24.dp)
                .graphicsLayer {
                    alpha = mainChrome.value.coerceIn(0f, 1f) *
                        (1f - 2f * transition.value.y).coerceIn(0f, 1f)
                },
            iconSize = designSize(20f), discSize = pageWidth * .073f,
            enabled = !detailVisible && favoritePresent
        )
        if (showLyrics || compactMounted) {
            val compactButtonY = compactTop + compactCoverSize / 2f - 24.dp
            PlayerButton(
                PlayerIconType.Star, if (isFavorite) "取消收藏" else "收藏", onFavorite,
                Modifier.offset(pageWidth * .775f - 24.dp, compactButtonY)
                    .graphicsLayer {
                        alpha = compactChrome.value.coerceIn(0f, 1f) *
                            (1f - 2f * transition.value.y).coerceIn(0f, 1f)
                    },
                iconSize = designSize(16f), discSize = designSize(30f),
                filled = isFavorite, enabled = showLyrics && !queueMounted
            )
            PlayerButton(
                PlayerIconType.More, "更多", onMore,
                Modifier.offset(pageWidth * .905f - 24.dp, compactButtonY)
                    .graphicsLayer {
                        alpha = compactChrome.value.coerceIn(0f, 1f) *
                            (1f - 2f * transition.value.y).coerceIn(0f, 1f)
                    },
                iconSize = designSize(17f), discSize = designSize(30f),
                enabled = showLyrics && !queueMounted
            )
        }
        if (controlsMounted) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val controlProgress = controls.value.coerceIn(0f, 1f)
                alpha = controlProgress
                translationY = 64.dp.toPx() * (1f - controlProgress)
            }.then(if (!controlsEnabled) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
        PlayerTimeline(
            positionMs = positionMs,
            durationMs = durationMs,
            onSeek = onSeek,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            enabled = controlsEnabled,
            gesture = seekGesture,
        )
        if (qualityLabel != null) {
            PlayerQualityBadge(
                label = qualityLabel,
                description = audioQuality?.description ?: qualityLabel,
                lossless = audioQuality?.isLossless == true && qualityLabel != "DSD",
                pageWidth = pageWidth, pageHeight = pageHeight,
                enabled = controlsEnabled, onClick = onQuality,
            )
        }
        PlayerButton(
            PlayerIconType.Previous, "上一曲", onPrevious,
            Modifier.offset(pageWidth * .236f - 30.dp, pageHeight * .778f - 30.dp),
            iconSize = designSize(41f), touchSize = 60.dp, enabled = controlsEnabled,
            seekGesture = seekGesture
        )
        PlayerButton(
            if (isPlaying) PlayerIconType.Pause else PlayerIconType.Play,
            if (isPlaying) "暂停" else "播放", onTogglePlay,
            Modifier.offset(pageWidth * .5f - 34.dp, pageHeight * .778f - 34.dp),
            iconSize = designSize(44f), touchSize = 68.dp, enabled = controlsEnabled
        )
        PlayerButton(
            PlayerIconType.Next, "下一曲", onNext,
            Modifier.offset(pageWidth * .763f - 30.dp, pageHeight * .778f - 30.dp),
            iconSize = designSize(41f), touchSize = 60.dp, enabled = controlsEnabled,
            seekGesture = seekGesture
        )
        val volumeEndpoint = remember { intArrayOf(0) }
        val lowVolumeScale = remember { Animatable(1f) }
        val highVolumeScale = remember { Animatable(1f) }
        val volumeScope = rememberCoroutineScope()
        PlayerIcon(
            PlayerIconType.VolumeLow,
            Modifier.offset(pageWidth * .084f - designSize(11f), pageHeight * .886f - designSize(11f)).size(designSize(22f))
                .graphicsLayer { scaleX = lowVolumeScale.value; scaleY = scaleX },
            softWhite
        )
        PlayerSlider(
            value = volume, onValueChange = onVolumeChange, description = "音量",
            modifier = Modifier.offset(pageWidth * .134f, pageHeight * .886f - 22.dp)
                .width(pageWidth * .718f).height(44.dp),
            enabled = controlsEnabled, trackThickness = designSize(7f), gesture = seekGesture,
            onTouchStateChanged = { pressed, value ->
                val endpoint = when {
                    !pressed -> 0
                    value <= 0f -> -1
                    value >= 1f -> 1
                    else -> 0
                }
                if (endpoint != 0 && endpoint != volumeEndpoint[0]) {
                    val scale = if (endpoint < 0) lowVolumeScale else highVolumeScale
                    // A spring impulse returns itself to rest, including a tap-up at the endpoint.
                    // Retarget from the live scale/velocity; no reset, replay timer, or held enlargement.
                    volumeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        scale.animateTo(1f, spring(dampingRatio = .6f, stiffness = 700f),
                            initialVelocity = (scale.velocity.coerceAtLeast(0f) + 3.5f).coerceAtMost(6f))
                    }
                }
                volumeEndpoint[0] = endpoint
            },
        )
        PlayerIcon(
            PlayerIconType.VolumeHigh,
            Modifier.offset(pageWidth * .909f - designSize(11f), pageHeight * .886f - designSize(11f)).size(designSize(22f))
                .graphicsLayer { scaleX = highVolumeScale.value; scaleY = scaleX },
            softWhite
        )
        PlayerButton(
            PlayerIconType.Lyrics, "歌词", onLyrics,
            Modifier.offset(pageWidth * .212f - 24.dp, pageHeight * .94f - 24.dp),
            iconSize = designSize(23f), tint = if (lyricsVisible) Color.White else softWhite,
            enabled = controlsEnabled, selected = lyricsVisible
        )
        PlayerButton(
            PlayerIconType.AirPlay, "音频输出", onOutput,
            Modifier.offset(pageWidth * .5f - 24.dp, pageHeight * .94f - 24.dp),
            iconSize = designSize(24f), tint = softWhite, enabled = controlsEnabled
        )
        PlayerButton(
            PlayerIconType.Queue, "播放队列", onQueue,
            Modifier.offset(pageWidth * .79f - 24.dp, pageHeight * .94f - 24.dp),
            iconSize = designSize(24f), tint = if (queueVisible) Color.White else softWhite,
            enabled = controlsEnabled, selected = queueVisible
        )
        }
        }
    }
}

@Composable
private fun PlayerQualityBadge(
    label: String,
    description: String,
    lossless: Boolean,
    pageWidth: Dp,
    pageHeight: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    fun designSize(value: Float) = pageWidth * (value / 390f)
    val viewConfiguration = LocalViewConfiguration.current
    val timelineHalfTouch = maxOf(22.dp, viewConfiguration.minimumTouchTargetSize.height / 2f)
    // Preserve the original center where safe. Include minimum-touch expansion of the
    // timeline's 44dp layout; leave 2dp below it and 4dp above the pause button's hitbox.
    val top = maxOf(pageHeight * .687f - designSize(10f), pageHeight * .660f + timelineHalfTouch + 2.dp)
    val availableHeight = (pageHeight * .778f - 38.dp - top).coerceAtLeast(0.dp)
    val height = minOf(designSize(20f), availableHeight)
    if (height <= 0.dp) return
    val scale = (height / designSize(20f)).coerceAtMost(1f)
    fun badgeSize(value: Float) = designSize(value) * scale
    val maxWidth = pageWidth * .44f
    val tint = Color.White.copy(alpha = .58f)
    val menuAnchor = rememberMenuAnchor()
    val boundedTouch = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            // Compose's default minimum touch expansion must not cover the timeline.
            override val minimumTouchTargetSize: DpSize = DpSize.Zero
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides boundedTouch) {
        Box(
            Modifier.offset(x = (pageWidth - maxWidth) / 2f, y = top).width(maxWidth).height(height),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.widthIn(max = maxWidth).height(height).clipToBounds()
                    .clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .09f))
                    .then(menuAnchor.first)
                    .semantics(mergeDescendants = true) { contentDescription = description }
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看音频参数") {
                        menuAnchor.second()
                        onClick()
                    }.padding(horizontal = badgeSize(6f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (lossless) {
                    PlayerIcon(
                        PlayerIconType.Lossless,
                        Modifier.size(badgeSize(16f), badgeSize(13f)).clearAndSetSemantics { }, tint,
                    )
                    Spacer(Modifier.width(badgeSize(4f)))
                }
                BasicText(
                    label, Modifier.clearAndSetSemantics { }, maxLines = 1, softWrap = false,
                    style = TextStyle(
                        color = tint, fontSize = with(density) { badgeSize(11f).toSp() },
                        lineHeight = with(density) { badgeSize(14f).toSp() },
                        fontFamily = PlayerTypography.chinese, fontSynthesis = FontSynthesis.None,
                        fontWeight = FontWeight.Normal,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PlayerTimeline(
    positionMs: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    pageWidth: Dp,
    pageHeight: Dp,
    enabled: Boolean,
    gesture: PlayerSeekGesture,
) {
    val duration = durationMs.coerceAtLeast(0L)
    // Keep frame-by-frame snapshot reads out of PlayerScreen's composition scope.
    val position = positionMs().coerceIn(0L, duration)
    val timeStyle = TextStyle(
        color = Color.White.copy(alpha = .58f),
        fontSize = with(LocalDensity.current) { (pageWidth * (13f / 390f)).toSp() },
        fontFamily = PlayerTypography.latin,
        fontSynthesis = FontSynthesis.None,
        fontWeight = FontWeight.Normal,
    )
    PlayerSlider(
        value = if (duration > 0) position.toFloat() / duration else 0f,
        onValueChange = { onSeek((duration.toDouble() * it).toLong().coerceIn(0L, duration)) },
        description = "播放进度",
        modifier = Modifier.offset(pageWidth * .067f, pageHeight * .660f - 22.dp)
            .width(pageWidth * .866f).height(44.dp),
        enabled = enabled && duration > 0,
        trackThickness = pageWidth * (7f / 390f),
        gesture = gesture,
    )
    BasicText(
        playerTime(position),
        Modifier.offset(pageWidth * .087f, pageHeight * .679f),
        style = timeStyle,
    )
    Box(
        Modifier.offset(pageWidth * .74f, pageHeight * .679f)
            .width(pageWidth * .173f), contentAlignment = Alignment.CenterEnd
    ) {
        BasicText("−${playerTime(duration - position)}", style = timeStyle)
    }
}

@Composable
private fun PlayerLyricStage(
    pageWidth: Dp,
    pageHeight: Dp,
    lyricsVisible: Boolean,
    lyricReveal: Animatable<Float, AnimationVector1D>,
    controls: Animatable<Float, AnimationVector1D>,
    lyricContent: @Composable (Modifier, Float) -> Unit
) {
    val progress = lyricReveal.value.coerceIn(0f, 1f)
    if (lyricsVisible || progress > 0f) {
        val compactTop = pageHeight * .082f
        val compactCover = pageWidth * (52f / 390f)
        val stageTop = compactTop + compactCover + pageWidth * (16f / 390f)
        val stageBottom = pageHeight * (.97f - .33f * controls.value.coerceIn(0f, 1f))
        lyricContent(
            // Rows are placed by graphicsLayer translation, so scrolled-away rows still sit under
            // the title and the controls. Clipping makes hit-testing stop at the visible stage.
            Modifier.offset(y = stageTop)
                .size(pageWidth, (stageBottom - stageTop).coerceAtLeast(1.dp))
                .clipToBounds()
                .zIndex(if (lyricsVisible) 1f else 0f)
                .then(if (!lyricsVisible) Modifier.clearAndSetSemantics { } else Modifier)
                .pointerInput(lyricsVisible) {
                    // Outgoing lyrics keep drawing, but cannot seek or drag. The current
                    // stage is above this one and receives overlapping pointer hits first.
                    if (!lyricsVisible) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                },
            progress
        )
    }
}

@Composable
private fun PlayerButton(
    icon: PlayerIconType,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    touchSize: Dp = 48.dp,
    discSize: Dp = 0.dp,
    tint: Color = Color.White,
    filled: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
    seekGesture: PlayerSeekGesture? = null,
    discAlpha: () -> Float = { 1f }
) {
    val interaction = remember { MutableInteractionSource() }
    val menuAnchor = rememberMenuAnchor()
    val opensMenu = icon == PlayerIconType.More || icon == PlayerIconType.MoreVertical || icon == PlayerIconType.AirPlay
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) .87f else 1f,
        animationSpec = spring(dampingRatio = .66f, stiffness = 650f)
    )
    Box(
        modifier.size(touchSize).then(if (opensMenu) menuAnchor.first else Modifier)
            .semantics { contentDescription = description }
            .then(
                if (seekGesture != null) Modifier.playerSeekGesture(
                    gesture = seekGesture,
                    forward = icon == PlayerIconType.Next,
                    enabled = enabled,
                    interactionSource = interaction,
                    onClick = onClick,
                ) else if (enabled) Modifier.clickable(
                    interactionSource = interaction, indication = null, role = Role.Button, onClick = {
                        if (opensMenu) menuAnchor.second()
                        onClick()
                    }
                ) else Modifier.clearAndSetSemantics { }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(if (selected) 38.dp else if (discSize > 0.dp) discSize else iconSize)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = .18f)))
            } else if (discSize > 0.dp) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = discAlpha() }.clip(CircleShape).background(Color.White.copy(alpha = if (pressed) .22f else .12f)))
            }
            PlayerIcon(icon, Modifier.size(iconSize), tint, filled)
        }
    }
}

@Composable
private fun PlayerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackThickness: Dp = 3.5.dp,
    gesture: PlayerSeekGesture,
    onTouchStateChanged: (Boolean, Float) -> Unit = { _, _ -> },
) {
    val callback by rememberUpdatedState(onValueChange)
    val touchStateChanged by rememberUpdatedState(onTouchStateChanged)
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val externalValue = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    val shownValue = if (dragging) dragValue else externalValue
    val thicknessScale = animateFloatAsState(
        if (pressed && enabled) 1.3f else 1f,
        animationSpec = spring(dampingRatio = .85f, stiffness = 900f), label = "sliderPress",
    )
    Box(
        modifier.semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(shownValue, 0f..1f)
            setProgress { requested ->
                if (enabled && requested.isFinite()) {
                    gesture.guard.cancel()
                    callback(requested.coerceIn(0f, 1f))
                    true
                } else false
            }
        }.playerSliderGesture(
            gesture = gesture, enabled = enabled,
            onInteractionChanged = { isPressed, isDragging ->
                if (isPressed && !pressed) {
                    dragValue = externalValue
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                pressed = isPressed
                dragging = isDragging
                touchStateChanged(isPressed, if (isDragging) dragValue else externalValue)
            },
            onValueChange = { changed ->
                dragValue = changed
                callback(changed)
                touchStateChanged(pressed, changed)
            },
        ),
    ) {
      // Only the paint layer grows; the slider's touch bounds and surrounding layout stay fixed.
      Canvas(Modifier.fillMaxSize().graphicsLayer { scaleY = thicknessScale.value }) {
        val trackHeight = trackThickness.toPx().coerceAtMost(size.height)
        val trackTop = (size.height - trackHeight) / 2
        val corner = CornerRadius(trackHeight / 2)
        drawRoundRect(Color.White.copy(alpha = .28f), Offset(0f, trackTop), Size(size.width, trackHeight), corner)
        if (shownValue > 0f) {
            val clip = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        0f, trackTop, size.width, trackTop + trackHeight, corner
                    )
                )
            }
            clipPath(clip) {
                drawRect(
                    Color.White.copy(alpha = if (pressed) .85f else .5f),
                    Offset(0f, trackTop), Size(size.width * shownValue, trackHeight)
                )
            }
        }
      }
    }
}

private fun playerTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
