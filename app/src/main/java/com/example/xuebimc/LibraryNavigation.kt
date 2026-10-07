package com.example.xuebimc

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

/** A local-library destination; use a new id for each navigation action. */
public data class LibraryOpenRequest(val id: Long, val track: Track, val artist: Boolean)

/** Library landing and all-songs are distinct, state-preserving pages, not one endless feed. */
@Composable
fun LibraryScreen(
    tracks: List<Track>, currentTrack: Track?, isPlaying: Boolean, loading: Boolean,
    listeningStats: ListeningStatsSnapshot = ListeningStatsSnapshot(),
    hasPermission: Boolean, error: String?, onRequestPermission: () -> Unit,
    onRefresh: () -> Unit, onSelect: (Track) -> Unit, onOpenPlayer: () -> Unit,
    onTogglePlayback: () -> Unit, onImport: () -> Unit, onOnline: () -> Unit = {},
    onPlayList: (List<Track>) -> Unit = { it.firstOrNull()?.let(onSelect) },
    onNext: () -> Unit = {}, onPlayNext: (Track) -> Unit = {}, onAddToQueue: (Track) -> Unit = {},
    bottomInset: Dp = 0.dp, isActive: Boolean = true,
    onSelectFromList: (Track, List<Track>) -> Unit = { track, _ -> onSelect(track) },
    onOverlayVisibilityChange: (Boolean) -> Unit = {},
    onShuffleList: (List<Track>) -> Unit = { onPlayList(it.shuffled()) },
    onPlaylists: () -> Unit = {}, playlistCount: Int = 0,
    onDownloads: () -> Unit = {},
    onDeleteTrack: (Track) -> Unit = {},
    onAddToPlaylist: (List<Track>) -> Unit = {},
    backdrop: HazeState? = null,
    backdropVisible: Boolean = true,
    onScrollDirection: (Boolean) -> Unit = {},
    openRequest: LibraryOpenRequest? = null,
) {
    var songsVisible by rememberSaveable { mutableStateOf(false) }
    var lastOpenRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    val progress = remember { Animatable(if (songsVisible) 1f else 0f) }
    val songsPresent by remember { derivedStateOf { songsVisible || progress.value > .001f } }
    val songsCovered by remember { derivedStateOf { progress.value >= .999f } }
    LaunchedEffect(openRequest?.id) {
        val request = openRequest ?: return@LaunchedEffect
        if (lastOpenRequestId != request.id) {
            lastOpenRequestId = request.id
            songsVisible = false
        }
    }
    LaunchedEffect(songsVisible) {
        progress.animateTo(if (songsVisible) 1f else 0f, spring(dampingRatio = 1f, stiffness = 420f))
    }
    BackHandler(isActive && songsVisible) { songsVisible = false }
    @Composable fun Page(songs: Boolean) {
        LibrarySurface(
            tracks, currentTrack, isPlaying, listeningStats, loading, hasPermission, error,
            onRequestPermission, onRefresh, onSelect, onOpenPlayer, onTogglePlayback, onImport,
            onOnline, onPlayList, onNext, onPlayNext, onAddToQueue, bottomInset,
            isActive = isActive && if (songs) songsVisible else !songsPresent,
            onSelectFromList = onSelectFromList, onOverlayVisibilityChange = onOverlayVisibilityChange,
            onShuffleList = onShuffleList, showSongsOnly = songs, onSongs = { songsVisible = true },
            onRootBack = { songsVisible = false }, onPlaylists = onPlaylists, playlistCount = playlistCount,
            onDownloads = onDownloads,
            onDeleteTrack = onDeleteTrack,
            onAddToPlaylist = onAddToPlaylist,
            backdrop = backdrop,
            backdropVisible = backdropVisible && (songs || !songsCovered),
            onScrollDirection = onScrollDirection,
            openRequest = if (songs) null else openRequest,
        )
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = -size.width * .22f * progress.value
            alpha = 1f - .18f * progress.value
        }) { Page(false) }
        if (songsPresent) Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * (1f - progress.value)
        }.pointerInput(Unit) { detectTapGestures {} }) { Page(true) }
    }
}
