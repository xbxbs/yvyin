package com.example.xuebimc

import com.example.xuebimc.PlaybackQueue.Advance
import com.example.xuebimc.PlaybackQueue.Origin
import com.example.xuebimc.PlaybackQueue.Repeat
import kotlin.random.Random

// Standalone JVM check: compile just PlaybackQueue.kt and this file with kotlinc, then run the jar.
private data class Song(
    val key: String,
    val genre: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val local: Boolean = true,
)

private typealias Queue = PlaybackQueue<Song>

private fun queue(tracks: List<Song> = emptyList(), startIndex: Int = 0): Queue =
    PlaybackQueue<Song>(
        keyOf = { it.key },
        tagsOf = { PlaybackQueue.Tags(it.genre, it.artist, it.album) },
        isLocal = { it.local },
        random = Random(17),
    ).apply { setContext(tracks, startIndex, "测试列表") }

private fun Queue.entries() = history + listOfNotNull(current) + manual + context

private fun Queue.checkIdentity() {
    val entries = entries()
    check(entries.map { it.id }.distinct().size == entries.size) { "An occurrence occupies two places" }
    check(manual.all { it.origin == Origin.Manual })
    check(context.none { it.origin == Origin.Manual })
    if (autoplayEnabled) check(!shuffled && repeat == Repeat.Off)
}

private fun manualInterruption() {
    val songs = List(500) { Song("context-$it") }
    val q = queue(songs, startIndex = 237)
    val savedContext = q.context
    val first = q.current!!
    q.enqueue(listOf(Song("next-1"), Song("next-2")), next = true)
    q.enqueue(listOf(Song("later-1"), Song("later-2")), next = false)
    q.enqueue(listOf(Song("new-next-1"), Song("new-next-2")), next = true)
    val manualIds = q.manual.map { it.id }
    val expected = listOf("new-next-1", "new-next-2", "next-1", "next-2", "later-1", "later-2")
    expected.forEach { key ->
        check(q.advance()!!.track.key == key)
        check(q.context == savedContext) { "Manual consumption moved the context cursor" }
        q.checkIdentity()
    }
    check(q.advance() == savedContext.first())
    check(q.history.map { it.id } == listOf(first.id) + manualIds)
    check(q.manual.isEmpty() && q.context == savedContext.drop(1))
    savedContext.drop(1).forEach { check(q.advance(Advance.Complete) == it) }
    check(q.advance() == null && q.current?.track == songs.last())
    check(q.contextSourceName == "测试列表")
}

private fun duplicateOccurrences() {
    val same = Song("same")
    val q = queue(listOf(same, same, same))
    q.enqueue(listOf(same, same), next = true)
    q.enqueue(listOf(same), next = false)
    q.checkIdentity()
    check(q.entries().size == 6)
    val removed = q.manual[1].id
    val selected = q.manual.last()
    q.remove(removed)
    check(q.playEntry(selected.id) == selected)
    check(q.history.size == 1 && q.manual.size == 1 && q.context.size == 2)
    val ids = q.entries().map { it.id }
    q.updateTrack(same.copy(artist = "updated"))
    check(q.entries().map { it.id } == ids)
    check(q.entries().all { it.track.artist == "updated" })
    check(q.entries().none { it.id == removed })
    q.checkIdentity()
}

private fun shuffleRestoration() {
    val q = queue(List(40) { Song("$it") })
    q.advance()
    q.enqueue(listOf(Song("manual-1"), Song("manual-2")), next = true)
    val active = q.current
    val history = q.history
    val manual = q.manual
    val original = q.context
    q.toggleShuffle()
    check(q.current == active && q.history == history && q.manual == manual)
    check(q.context.map { it.id }.toSet() == original.map { it.id }.toSet())
    check(q.context != original)
    repeat(2) { q.advance() }
    val consumed = q.advance()!!
    val removed = q.context[2]
    q.remove(removed.id)
    val activeAfter = q.current
    val historyAfter = q.history
    q.toggleShuffle()
    check(q.context == original.filterNot { it.id == consumed.id || it.id == removed.id })
    check(q.current == activeAfter && q.history == historyAfter)
    q.checkIdentity()
}

private fun historyNavigation() {
    val q = queue(listOf(Song("a"), Song("b"), Song("c")))
    val original = q.current!!
    q.enqueue(listOf(Song("m1"), Song("m2")), next = false)
    val firstManual = q.advance()!!
    val secondManual = q.advance()!!
    val nextContext = q.advance()!!
    check(q.history.map { it.id } == listOf(original.id, firstManual.id, secondManual.id))
    check(q.previous() == secondManual)
    check(q.previous() == firstManual)
    check(q.previous() == original)
    check(q.previous() == null)
    check(q.manual == listOf(firstManual, secondManual))
    check(q.context.first() == nextContext)
    check(q.advance() == firstManual && q.advance() == secondManual && q.advance() == nextContext)
    check(q.playEntry(original.id) == original)
    check(q.history.last() == nextContext)
    q.checkIdentity()
}

private fun removalAndMove() {
    val q = queue(List(6) { Song("$it") })
    q.enqueue(listOf(Song("m1"), Song("m2")), next = true)
    val active = q.current!!
    val before = q.entries()
    q.remove(active.id)
    q.move(active.id, q.manual.first().id)
    q.move(q.manual.first().id, active.id)
    q.move(q.context.first().id, "missing")
    check(q.entries() == before)
    val promoted = q.context[2]
    val resume = q.context.first()
    q.move(promoted.id, q.manual.first().id)
    check(q.manual.first().id == promoted.id && q.manual.first().origin == Origin.Manual)
    check(q.context.first() == resume && q.current == active)
    q.move(q.manual.first().id, q.context.last().id)
    check(q.manual.last().id == promoted.id) // Cross-layer drop cannot put manual after context.
    val movedWithinContext = q.context.last()
    q.move(movedWithinContext.id, q.context.first().id)
    check(q.context.first() == movedWithinContext)
    q.move(movedWithinContext.id, null)
    check(q.context.last() == movedWithinContext)
    q.remove(q.manual.first().id)
    val removedContext = q.context.last()
    q.remove(removedContext.id)
    q.cycleRepeat()
    while (q.manual.isNotEmpty() || q.context.isNotEmpty()) q.advance()
    q.advance()
    check((listOfNotNull(q.current) + q.context).none {
        it.track.key == promoted.track.key || it.track.key == removedContext.track.key
    })
    q.checkIdentity()
}

private fun clearUpcoming() {
    val q = queue(List(4) { Song("$it") })
    q.advance()
    q.enqueue(listOf(Song("manual")), next = false)
    q.cycleRepeat()
    val active = q.current
    val history = q.history
    q.clearUpcoming()
    q.toggleShuffle()
    q.toggleShuffle()
    check(q.current == active && q.history == history)
    check(q.manual.isEmpty() && q.context.isEmpty())
    check(q.advance(Advance.Complete) == null) { "Clear resurrected the old repeat template" }
    q.setAutoplayLibrary(listOf(Song("local")))
    q.toggleAutoplay()
    q.clearUpcoming()
    check(q.autoplayEnabled && q.advance()!!.track.key == "local")
}

private fun repeatModes() {
    val q = queue(listOf(Song("a"), Song("b")))
    q.enqueue(listOf(Song("manual")), next = true)
    val first = q.current
    q.cycleRepeat()
    q.cycleRepeat()
    check(q.repeat == Repeat.One)
    check(q.advance(Advance.Complete) == first && q.history.isEmpty())
    check(q.manual.size == 1)
    val manual = q.advance()!!
    check(manual.origin == Origin.Manual && q.history == listOf(first))
    check(q.advance(Advance.Complete) == manual && q.history.size == 1)
    q.cycleRepeat()
    check(q.advance()!!.track.key == "b")
    check(q.advance(Advance.Complete) == null)
    q.cycleRepeat()
    check(q.advance(Advance.Complete)!!.track.key == "a")
    check(q.current!!.id != first!!.id)
    check(q.context.single().track.key == "b" && q.manual.isEmpty())
    q.checkIdentity()
}

private fun autoplayTagsAndExclusion() {
    val seed = Song("seed", genre = " Jazz / Soul ", artist = "Band", album = "Album")
    val tail = seed.copy(key = "tail")
    val genre = Song("genre", genre = "jazz")
    val artistAlbum = Song("artist-album", artist = "Band", album = "Album")
    val album = Song("album", album = "Album")
    val unrelated = Song("unrelated")
    val online = seed.copy(key = "online", local = false)
    val q = queue(listOf(seed, tail))
    val pending = q.context
    q.setAutoplayLibrary(listOf(seed, tail, genre, genre, artistAlbum, album, unrelated, online))
    q.enqueue(listOf(Song("manual", genre = "Rock")), next = true)
    q.toggleShuffle()
    q.cycleRepeat()
    q.cycleRepeat()
    q.toggleAutoplay()
    q.toggleShuffle()
    q.cycleRepeat()
    check(q.autoplayEnabled && !q.shuffled && q.repeat == Repeat.Off)
    check(q.context == pending)
    check(q.advance()!!.origin == Origin.Manual)
    check(q.advance(Advance.Complete)!!.track == tail)
    check(q.advance(Advance.Complete)!!.track == genre) { "Genre must outrank artist + album" }
    check(q.current!!.sourceName == "本地标签续播")
    val recent = q.history.map { it.track.key }.toSet()
    check(q.advance()!!.track.key !in recent)
    repeat(18) {
        val last = q.current!!.track.key
        val selected = q.advance(Advance.Complete)!!
        check(selected.track.local && selected.track.key != last)
        check(selected.origin == Origin.Autoplay)
        q.checkIdentity()
    }

    val artistFirst = queue(listOf(seed))
    artistFirst.setAutoplayLibrary(listOf(album, artistAlbum))
    artistFirst.toggleAutoplay()
    check(artistFirst.advance()!!.track == artistAlbum)
    val albumOnly = queue(listOf(seed))
    albumOnly.setAutoplayLibrary(listOf(unrelated, album))
    albumOnly.toggleAutoplay()
    check(albumOnly.advance()!!.track == album)

    val noTags = queue(listOf(Song("one", artist = "<unknown>", album = "未知专辑")))
    noTags.setAutoplayLibrary(listOf(Song("two", artist = "<unknown>", album = "未知专辑")))
    noTags.toggleAutoplay()
    check(noTags.advance()!!.sourceName == "本地曲库续播")
    val single = queue(listOf(seed))
    single.setAutoplayLibrary(listOf(seed, online))
    single.toggleAutoplay()
    check(single.advance() == null) { "Do not recommend current or network tracks" }
}

private fun failureRecovery() {
    val bad = Song("bad")
    val q = queue(listOf(bad, bad, Song("last-bad")))
    q.enqueue(listOf(bad, Song("manual-bad"), bad), next = true)
    q.cycleRepeat()
    q.cycleRepeat()
    check(q.advance(Advance.Failure)!!.track.key == "manual-bad")
    check(q.advance(Advance.Failure)!!.track.key == "last-bad")
    check(q.advance(Advance.Failure) == null)
    q.cycleRepeat()
    q.cycleRepeat()
    repeat(5) { check(q.advance(Advance.Failure) == null) }
    q.resetFailures()
    check(q.advance()!!.track.key == "bad") // Explicit retry may try failed keys again.
    q.checkIdentity()

    val survivor = queue(listOf(bad, Song("good"), bad))
    survivor.cycleRepeat()
    check(survivor.advance(Advance.Failure)!!.track.key == "good")
    repeat(3) { check(survivor.advance(Advance.Complete)!!.track.key == "good") }

    val autoplay = queue(listOf(bad))
    autoplay.setAutoplayLibrary(listOf(bad, Song("b"), Song("c"), Song("online", local = false)))
    autoplay.toggleAutoplay()
    check(autoplay.advance(Advance.Failure) != null)
    check(autoplay.advance(Advance.Failure) != null)
    repeat(5) { check(autoplay.advance(Advance.Failure) == null) }
    autoplay.checkIdentity()
}

private fun emptyAndBoundaries() {
    val q = queue()
    check(q.advance() == null && q.previous() == null && q.playEntry("missing") == null)
    q.enqueue(listOf(Song("first"), Song("second")), next = true)
    check(q.advance()!!.origin == Origin.Manual && q.history.isEmpty())
    check(q.advance()!!.track.key == "second")
    check(q.advance() == null)
    q.setContext(emptyList())
    check(q.entries().isEmpty())
    q.setContext(listOf(Song("a"), Song("b")), startIndex = 99)
    check(q.current!!.track.key == "b" && q.history.isEmpty() && q.context.isEmpty())
    q.setContext(listOf(Song("a"), Song("b")), startIndex = -99)
    check(q.current!!.track.key == "a" && q.context.single().track.key == "b")
}

private fun mixedEventIdentity() {
    val random = Random(91)
    val songs = List(12) { Song("$it", genre = "genre-${it % 3}") }
    val q = queue(songs)
    q.setAutoplayLibrary(songs)
    repeat(600) {
        when (random.nextInt(13)) {
            0 -> q.enqueue(listOf(songs.random(random), songs.random(random)), next = random.nextBoolean())
            1 -> q.advance()
            2 -> q.advance(Advance.Complete)
            3 -> q.advance(Advance.Failure)
            4 -> q.previous()
            5 -> q.entries().randomOrNull(random)?.let { q.playEntry(it.id) }
            6 -> q.entries().randomOrNull(random)?.let {
                val active = q.current
                q.remove(it.id)
                check(q.current == active)
            }
            7 -> (q.manual + q.context).randomOrNull(random)?.let {
                val active = q.current
                val target = (q.manual + q.context).randomOrNull(random)?.id
                q.move(it.id, if (random.nextBoolean()) target else null)
                check(q.current == active)
            }
            8 -> q.toggleShuffle()
            9 -> q.cycleRepeat()
            10 -> q.toggleAutoplay()
            11 -> q.clearUpcoming()
            12 -> q.updateTrack(songs.random(random).copy(artist = "metadata"))
        }
        q.checkIdentity()
    }
}

fun main() {
    val scenarios = listOf(
        "500-track manual interruption" to ::manualInterruption,
        "duplicate occurrences and metadata" to ::duplicateOccurrences,
        "shuffle restoration" to ::shuffleRestoration,
        "history and previous" to ::historyNavigation,
        "remove and cross-source move" to ::removalAndMove,
        "clear without resurrection" to ::clearUpcoming,
        "repeat-one vs manual next; repeat-all" to ::repeatModes,
        "local autoplay tags and exclusions" to ::autoplayTagsAndExclusion,
        "bounded failure recovery" to ::failureRecovery,
        "empty queue and start boundaries" to ::emptyAndBoundaries,
        "600 mixed-event identity checks" to ::mixedEventIdentity,
    )
    scenarios.forEach { (name, scenario) ->
        try { scenario() } catch (failure: Throwable) { throw AssertionError(name, failure) }
    }
    println("PASS: ${scenarios.size} queue scenarios (including 500-track interruption and 600 mixed events)")
}
