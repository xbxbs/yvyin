package com.example.xuebimc

// Standalone JVM check: compile LibraryFileFacts.kt + PlaybackQueue.kt + this file.
private data class FileFactSong(val key: String, val addedAtMs: Long = 0L)

private fun fileFacts() {
    check(mediaAddedAtMs(1_700_000_123L) == 1_700_000_123_000L)
    check(mediaAddedAtMs(0L) == 0L)
    check(mediaAddedAtMs(-1L) == 0L)
    check(mediaAddedAtMs(Long.MAX_VALUE) == 0L)
    check(localFileParent("/storage/emulated/0/Music/唱片/song.flac") == "/storage/emulated/0/Music/唱片")
    check(localFileParent("/song.flac") == "/")
    check(localFileParent("content://files/42") == null)
    check(localFileParent("Music/song.flac") == null)
    check(localFileParent("/Music/") == null)
    val authority = "com.android.externalstorage.documents"
    check(externalStorageDocumentParent(authority, "primary:Music/唱片/song.flac") == "内部存储/Music/唱片")
    check(externalStorageDocumentParent(authority, "ABCD-1234:Music/song.flac") == "ABCD-1234:/Music")
    check(externalStorageDocumentParent(authority, "primary:song.flac") == "内部存储")
    check(externalStorageDocumentParent("cloud.provider", "primary:Music/song.flac") == null)
    check(externalStorageDocumentParent(authority, "opaque-id") == null)
    check(externalStorageDocumentParent(authority, "primary:") == null)
    check(externalStorageDocumentParent(authority, "primary:../Music/song.flac") == null)
    check(externalStorageDocumentParent(authority, "primary:Music//song.flac") == null)
}

private fun realAddedTimeOrder() {
    val tracks = listOf(
        FileFactSong("unknown-b"), FileFactSong("media-large-id", 100L),
        FileFactSong("new-import", 500L), FileFactSong("unknown-a", -1L),
        FileFactSong("media-small-id", 300L), FileFactSong("tie-a", 300L),
    )
    val order = Comparator<FileFactSong> { a, b ->
        compareLibraryAddedAt(a.addedAtMs, b.addedAtMs).takeIf { it != 0 } ?: a.key.compareTo(b.key)
    }
    check(tracks.sortedWith(order).map { it.key } == listOf(
        "new-import", "media-small-id", "tie-a", "media-large-id", "unknown-a", "unknown-b",
    ))
    check(tracks.filter { it.addedAtMs > 0L }.sortedWith(order).none { it.key.startsWith("unknown") })
    check(tracks.reversed().sortedWith(order) == tracks.sortedWith(order))
}

private fun deleteNonCurrentOccurrences() {
    val queue = PlaybackQueue<String>({ it })
    queue.setContext(listOf("history", "deleted", "active", "deleted", "last"))
    queue.advance()
    queue.advance()
    queue.enqueue(listOf("deleted", "manual", "deleted"), next = false)
    val active = queue.current
    val keptManual = queue.manual.filter { it.track != "deleted" }
    val keptContext = queue.context.filter { it.track != "deleted" }
    queue.cycleRepeat()
    check(!queue.removeTrack("deleted"))
    check(queue.current == active)
    check(queue.history.map { it.track } == listOf("history"))
    check(queue.manual == keptManual && queue.context == keptContext)
    repeat(12) { check(queue.advance()?.track != "deleted") }
}

private fun deleteCurrentOccurrences() {
    val queue = PlaybackQueue<String>({ it })
    queue.setContext(listOf("deleted", "context", "deleted"))
    queue.enqueue(listOf("deleted", "manual", "deleted", "later"), next = true)
    val nextOccurrence = queue.manual[1]
    val contextOccurrence = queue.context.first()
    check(queue.removeTrack("deleted"))
    check(queue.current == nextOccurrence)
    check(queue.history.isEmpty())
    check(queue.manual.map { it.track } == listOf("later"))
    check(queue.context == listOf(contextOccurrence))
    check(queue.advance()?.track == "later")
    check(queue.advance() == contextOccurrence)
    check(queue.previous()?.track == "later")
    check(queue.previous()?.track == "manual")
    check(queue.previous() == null)
}

private fun deleteFinalTrack() {
    val queue = PlaybackQueue<String>({ it })
    queue.setContext(listOf("deleted", "deleted"))
    queue.enqueue(listOf("deleted"), next = true)
    queue.setAutoplayLibrary(listOf("deleted"))
    queue.cycleRepeat()
    queue.cycleRepeat()
    check(queue.removeTrack("deleted"))
    check(queue.current == null && queue.manual.isEmpty() && queue.context.isEmpty() && queue.history.isEmpty())
    check(queue.advance() == null)
    queue.toggleAutoplay()
    check(queue.advance() == null)
}

private fun deleteAutoplayCandidate() {
    val queue = PlaybackQueue<String>({ it })
    queue.setContext(listOf("deleted"))
    queue.setAutoplayLibrary(listOf("deleted", "remaining"))
    queue.toggleAutoplay()
    check(queue.removeTrack("deleted"))
    check(queue.current?.track == "remaining")
    check(queue.advance() == null)
    check(queue.history.isEmpty())
    check(!queue.removeTrack("absent"))
    check(queue.current?.track == "remaining")
}

private fun deleteCurrentWithRepeatAll() {
    val queue = PlaybackQueue<String>({ it })
    queue.setContext(listOf("earlier", "deleted"), startIndex = 1)
    queue.cycleRepeat()
    check(queue.removeTrack("deleted"))
    check(queue.current?.track == "earlier")
    repeat(5) { check(queue.advance()?.track == "earlier") }
    check(queue.history.none { it.track == "deleted" })
}

private fun deleteAliasesAtomically() {
    val queue = PlaybackQueue<String>({ it })
    queue.setContext(listOf("media-alias", "saf-alias", "context"))
    queue.enqueue(listOf("saf-alias", "manual"), next = true)
    queue.setAutoplayLibrary(listOf("media-alias", "saf-alias", "context", "manual"))
    val expectedNext = queue.manual.last()
    check(queue.removeTracks(setOf("media-alias", "saf-alias")))
    check(queue.current == expectedNext)
    check(queue.history.isEmpty())
    check(queue.manual.isEmpty() && queue.context.map { it.track } == listOf("context"))
    queue.toggleAutoplay()
    repeat(6) { check(queue.advance()?.track in setOf("context", "manual")) }
}

fun main() {
    fileFacts()
    realAddedTimeOrder()
    deleteNonCurrentOccurrences()
    deleteCurrentOccurrences()
    deleteFinalTrack()
    deleteAutoplayCandidate()
    deleteCurrentWithRepeatAll()
    deleteAliasesAtomically()
    println("Library file facts / real added time / deletion queue: 8 scenarios passed")
}
