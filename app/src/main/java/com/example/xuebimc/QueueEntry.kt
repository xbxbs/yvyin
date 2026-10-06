package com.example.xuebimc

enum class QueueOrigin { Context, Manual, Autoplay }

/** Stable occurrence identity survives reordering and allows intentional duplicate songs. */
data class QueueEntry(
    val id: String,
    val track: Track,
    val origin: QueueOrigin,
    val sourceName: String,
)
