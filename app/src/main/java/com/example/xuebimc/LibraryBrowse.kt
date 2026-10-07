package com.example.xuebimc

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class LibrarySort(val label: String) {
    Title("标题"), Artist("艺人"), Recent("最近添加"),
}

internal enum class LibraryBrowseKind(val label: String) {
    Artists("艺人"), Albums("专辑"), Folders("文件夹"),
}

internal data class LibraryBrowseGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val tracks: List<Track>,
)

internal data class LibraryIndex(
    val source: List<Track>,
    val sort: LibrarySort,
    val ordered: List<Track>,
    val recent: List<Track>,
    val groups: Map<LibraryBrowseKind, List<LibraryBrowseGroup>>,
)

internal fun libraryTitle(track: Track): String =
    track.title.ifBlank { track.displayName.ifBlank { "未命名歌曲" } }

private fun String.knownLibraryValue(): String? = trim().takeUnless {
    it.isEmpty() || it.equals("<unknown>", ignoreCase = true) || it == "未知"
}

internal fun libraryArtist(track: Track): String = track.artist.knownLibraryValue() ?: "未知艺人"
internal fun libraryAlbum(track: Track): String = track.album.knownLibraryValue() ?: "未知专辑"
internal fun librarySubtitle(track: Track): String = "${libraryArtist(track)} · ${libraryAlbum(track)}"
internal fun libraryArtistKey(track: Track): String = libraryArtist(track).lowercase(Locale.ROOT)

internal fun libraryAlbumKey(track: Track): String = if (track.albumId > 0) {
    "id:${track.albumId}"
} else {
    "name:${libraryAlbum(track).lowercase(Locale.ROOT)}\u0000${libraryArtistKey(track)}"
}

private fun libraryFolder(track: Track): String? {
    val path = track.relativePath?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    if (path != null) return path
    track.folderPath?.takeIf { it.isNotBlank() }?.let { return it }
    // A content URI is not a filesystem path. Do not invent a folder for imported documents.
    if (track.uri.scheme == "file") return track.uri.path?.substringBeforeLast('/', "")?.ifBlank { null }
    return null
}

internal fun libraryMatches(track: Track, terms: List<String>): Boolean = terms.all { term ->
    libraryTitle(track).contains(term, ignoreCase = true) ||
        libraryArtist(track).contains(term, ignoreCase = true) ||
        libraryAlbum(track).contains(term, ignoreCase = true) ||
        track.displayName.contains(term, ignoreCase = true)
}

/** Called on Dispatchers.Default: grouping and locale-aware sorting never block a keystroke. */
internal suspend fun buildLibraryIndex(tracks: List<Track>, sort: LibrarySort): LibraryIndex {
    val active = currentCoroutineContext()
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val titleOrder = Comparator<Track> { a, b ->
        active.ensureActive()
        collator.compare(libraryTitle(a), libraryTitle(b)).takeIf { it != 0 }
            ?: a.stableKey.compareTo(b.stableKey)
    }
    // MediaStore's real DATE_ADDED and explicitly recorded imports/downloads only.
    // Old imports have unknown time: sort them last and never imply they were just added.
    val recentOrder = Comparator<Track> { a, b ->
        active.ensureActive()
        compareLibraryAddedAt(a.addedAtMs, b.addedAtMs).takeIf { it != 0 }
            ?: titleOrder.compare(a, b)
    }
    val recent = tracks.filter { it.addedAtMs > 0L }.sortedWith(recentOrder)
    val ordered = when (sort) {
        LibrarySort.Title -> tracks.sortedWith(titleOrder)
        LibrarySort.Artist -> tracks.sortedWith(Comparator { a, b ->
            active.ensureActive()
            collator.compare(libraryArtist(a), libraryArtist(b)).takeIf { it != 0 }
                ?: titleOrder.compare(a, b)
        })
        LibrarySort.Recent -> tracks.sortedWith(recentOrder)
    }
    val groups = LibraryBrowseKind.entries.associateWith { kind ->
        ordered.groupBy { track ->
            active.ensureActive()
            when (kind) {
                LibraryBrowseKind.Artists -> libraryArtistKey(track)
                LibraryBrowseKind.Albums -> libraryAlbumKey(track)
                LibraryBrowseKind.Folders -> libraryFolder(track).orEmpty()
            }
        }.map { (key, members) ->
            active.ensureActive()
            val first = members.first()
            val title = when (kind) {
                LibraryBrowseKind.Artists -> libraryArtist(first)
                LibraryBrowseKind.Albums -> libraryAlbum(first)
                LibraryBrowseKind.Folders -> key.ifEmpty { "位置未提供" }
            }
            val detail = when (kind) {
                LibraryBrowseKind.Artists -> "${members.map(::libraryAlbumKey).distinct().size} 张专辑"
                LibraryBrowseKind.Albums -> if (members.all { libraryArtistKey(it) == libraryArtistKey(first) }) {
                    libraryArtist(first)
                } else "多位艺人"
                LibraryBrowseKind.Folders -> if (key.isEmpty()) "系统未提供文件夹信息" else "本地文件夹"
            }
            LibraryBrowseGroup(key, title, "$detail · ${members.size} 首", members)
        }.sortedWith(Comparator { a, b ->
            active.ensureActive()
            collator.compare(a.title, b.title)
        })
    }
    return LibraryIndex(tracks, sort, ordered, recent.take(12), groups)
}

/** React to Remove animations while open, as well as when returning from system settings. */
@Composable
internal fun rememberLibraryMotionAllowed(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context, lifecycle) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { allowed = ValueAnimator.areAnimatorsEnabled() }
        }
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = ValueAnimator.areAnimatorsEnabled()
        }
        val registered = runCatching {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }.isSuccess
        lifecycle.addObserver(listener)
        onDispose {
            if (registered) resolver.unregisterContentObserver(observer)
            lifecycle.removeObserver(listener)
        }
    }
    return allowed
}
