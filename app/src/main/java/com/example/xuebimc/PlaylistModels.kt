package com.example.xuebimc

/** An occurrence, not a song ID: the same recording can appear more than once. */
data class PlaylistEntry(val id: String, val track: Track, val addedAt: Long)

enum class PlaylistSort { Custom, Title, Artist, Album, Added }

data class MusicPlaylist(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val creator: String = "我",
    val subtitle: String = "",
    val favorite: Boolean = false,
    val customCoverUri: String? = null,
    val entries: List<PlaylistEntry> = emptyList(),
    val sort: PlaylistSort = PlaylistSort.Custom,
    val ascending: Boolean = true,
)
