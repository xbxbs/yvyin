package com.example.xuebimc

import android.content.Context

/** Process-scoped player. Activity destruction does not own its lifetime. */
object PlaybackStore {
    private var controller: PlaybackController? = null

    @Synchronized
    fun get(context: Context): PlaybackController {
        val existing = controller
        if (existing != null && !existing.isReleased) return existing
        return PlaybackController(context.applicationContext).also { controller = it }
    }
}
