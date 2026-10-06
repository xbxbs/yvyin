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

/** Library landing and all-songs are distinct, state-preserving pages, not one endless feed. */
@Composable
fun LibraryScreen(
    tracks: List<Track>, currentTrack: Track?, isPlaying: Boolean, loading: Boolean,
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
    onAddToPlaylist: (List<Track>) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    var songsVisible by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (songsVisible) 1f else 0f) }
    val songsPresent by remember { derivedStateOf { songsVisible || progress.value > .001f } }
    LaunchedEffect(songsVisible) {
        progress.animateTo(if (songsVisible) 1f else 0f, spring(dampingRatio = 1f, stiffness = 420f))
    }
    BackHandler(isActive && songsVisible) { songsVisible = false }
    @Composable fun Page(songs: Boolean) {
        LibrarySurface(
            tracks, currentTrack, isPlaying, loading, hasPermission, error,
            onRequestPermission, onRefresh, onSelect, onOpenPlayer, onTogglePlayback, onImport,
            onOnline, onPlayList, onNext, onPlayNext, onAddToQueue, bottomInset,
            isActive = isActive && if (songs) songsVisible else !songsPresent,
            onSelectFromList = onSelectFromList, onOverlayVisibilityChange = onOverlayVisibilityChange,
            onShuffleList = onShuffleList, showSongsOnly = songs, onSongs = { songsVisible = true },
            onRootBack = { songsVisible = false }, onPlaylists = onPlaylists, playlistCount = playlistCount,
            onAddToPlaylist = onAddToPlaylist,
            onOpenSettings = onOpenSettings,
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
