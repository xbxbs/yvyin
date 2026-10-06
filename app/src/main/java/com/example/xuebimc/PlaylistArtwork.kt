package com.example.xuebimc

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A cover-only Track shares the existing artwork decoder/cache, including its size limits. */
internal fun playlistCoverTrack(playlist: MusicPlaylist): Track? =
    playlist.customCoverUri?.let { cover ->
        Track(
            id = 0, uri = Uri.parse("playlist-cover:${playlist.id}"),
            title = playlist.title, artist = playlist.creator, album = "", durationMs = 0,
            artworkUri = Uri.parse(cover), isOnline = true,
        )
    } ?: playlist.entries.firstOrNull()?.track

@Composable
fun PlaylistArtwork(playlist: MusicPlaylist, modifier: Modifier = Modifier, requestSize: Int = 256) {
    val cover = remember(playlist.id, playlist.title, playlist.creator, playlist.customCoverUri, playlist.entries) {
        playlistCoverTrack(playlist)
    }
    val tiles = remember(playlist.entries) {
        playlist.entries.asSequence().map { it.track }.distinctBy { it.stableKey }.take(4).toList()
    }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF252326))) {
        if (playlist.customCoverUri != null || tiles.size < 4) {
            TrackArtwork(cover, Modifier.fillMaxSize(), requestSize)
        } else {
            Column(Modifier.fillMaxSize()) {
                repeat(2) { row ->
                    Row(Modifier.weight(1f)) {
                        repeat(2) { column ->
                            TrackArtwork(tiles[row * 2 + column], Modifier.weight(1f).fillMaxHeight(), requestSize / 2)
                        }
                    }
                }
            }
        }
    }
}

/** Only the detail header owns this field; the list and sticky controls stay black. */
@Composable
internal fun PlaylistHeaderBackdrop(playlist: MusicPlaylist, motionEnabled: Boolean, modifier: Modifier = Modifier) {
    val cover = remember(playlist.customCoverUri, playlist.entries, playlist.id) { playlistCoverTrack(playlist) }
    MusicBackdrop(
        cover,
        modifier.clip(RoundedCornerShape(bottomStart = 72.dp, bottomEnd = 72.dp)).drawWithCache {
            val fade = Brush.verticalGradient(0f to Color.Black.copy(alpha = .35f), .55f to Color.Transparent, 1f to Color.Black)
            onDrawWithContent { drawContent(); drawRect(fade) }
        },
        motionEnabled = motionEnabled,
    )
}
