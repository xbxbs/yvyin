package com.example.xuebimc

import kotlin.random.Random

/** Pure, event-driven scheduler. No Android, decoder, persistence or frame-clock dependencies. */
internal class PlaybackQueue<T>(
    private val keyOf: (T) -> String,
    private val tagsOf: (T) -> Tags = { Tags() },
    private val isLocal: (T) -> Boolean = { true },
    private val random: Random = Random.Default,
) {
    enum class Origin { Context, Manual, Autoplay }
    enum class Repeat { Off, All, One }
    enum class Advance { Next, Complete, Failure }

    data class Tags(val genre: String? = null, val artist: String? = null, val album: String? = null)

    data class Entry<T>(
        val id: String,
        val track: T,
        val origin: Origin,
        val sourceName: String,
        internal val contextPosition: Int? = null,
    )

    private data class ContextItem<T>(val track: T, val position: Int)
    private val sessionId = random.nextLong().toString(16)
    private var serial = 0L
    // A repeat template, never used to restore shuffle. Removed/promoted items leave this too.
    private var contextCycle = emptyList<ContextItem<T>>()
    private var autoplayLibrary = emptyList<T>()
    private val failedKeys = mutableSetOf<String>()

    var history: List<Entry<T>> = emptyList()
        private set
    var current: Entry<T>? = null
        private set
    var manual: List<Entry<T>> = emptyList()
        private set
    var context: List<Entry<T>> = emptyList()
        private set
    var contextSourceName: String = "资料库"
        private set
    var shuffled: Boolean = false
        private set
    var repeat: Repeat = Repeat.Off
        private set
    var autoplayEnabled: Boolean = false
        private set

    private fun entry(track: T, origin: Origin, source: String, position: Int? = null) =
        Entry("$sessionId-${++serial}", track, origin, source, position)

    fun setContext(tracks: List<T>, startIndex: Int = 0, sourceName: String = "资料库") {
        history = emptyList()
        manual = emptyList()
        context = emptyList()
        current = null
        shuffled = false
        failedKeys.clear()
        contextSourceName = sourceName
        contextCycle = tracks.mapIndexed { index, track -> ContextItem(track, index) }
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(tracks.indices)
        current = entry(tracks[index], Origin.Context, sourceName, index)
        context = tracks.drop(index + 1).mapIndexed { offset, track ->
            entry(track, Origin.Context, sourceName, index + offset + 1)
        }
    }

    fun enqueue(tracks: List<T>, next: Boolean) {
        val entries = tracks.map { entry(it, Origin.Manual, "手动添加") }
        manual = if (next) entries + manual else manual + entries
    }

    /** Completion alone honors repeat-one. Failure never retries a known-bad song. */
    fun advance(reason: Advance = Advance.Next): Entry<T>? {
        val active = current
        if (reason == Advance.Failure && active != null) failedKeys.add(keyOf(active.track))
        if (reason == Advance.Complete && repeat == Repeat.One && !autoplayEnabled &&
            active != null && keyOf(active.track) !in failedKeys
        ) return active

        var upcoming = takeUpcoming()
        if (upcoming == null && !autoplayEnabled && repeat == Repeat.All) {
            context = contextCycle.filter { keyOf(it.track) !in failedKeys }.map {
                entry(it.track, Origin.Context, contextSourceName, it.position)
            }.let { if (shuffled) it.shuffled(random) else it }
            upcoming = takeUpcoming()
        }
        if (upcoming == null && autoplayEnabled) upcoming = autoplayEntry()
        if (upcoming == null) return null
        if (active != null) history = history + active
        current = upcoming
        return upcoming
    }

    private fun takeUpcoming(): Entry<T>? {
        val manualIndex = manual.indexOfFirst { keyOf(it.track) !in failedKeys }
        if (manualIndex >= 0) return manual[manualIndex].also { manual = manual.drop(manualIndex + 1) }
        manual = emptyList()
        val contextIndex = context.indexOfFirst { keyOf(it.track) !in failedKeys }
        if (contextIndex >= 0) return context[contextIndex].also { context = context.drop(contextIndex + 1) }
        context = emptyList()
        return null
    }

    fun resetFailures() { failedKeys.clear() }

    /** Actual history only: unplayed items before a context's startIndex are not history. */
    fun previous(): Entry<T>? {
        val previous = history.lastOrNull() ?: return null
        current?.let { active ->
            if (active.origin == Origin.Manual) manual = listOf(active) + manual
            else context = listOf(active) + context
        }
        history = history.dropLast(1)
        current = previous
        return previous
    }

    /** Play precisely this occurrence now, retaining all other pending occurrences. */
    fun playEntry(id: String): Entry<T>? {
        if (current?.id == id) return current
        val selected = manual.firstOrNull { it.id == id }
            ?: context.firstOrNull { it.id == id }
            ?: history.firstOrNull { it.id == id }
            ?: return null
        manual = manual.filterNot { it.id == id }
        context = context.filterNot { it.id == id }
        history = history.filterNot { it.id == id }
        current?.let { history = history + it }
        current = selected
        return selected
    }

    /** Neither removal nor dragging is allowed to change the current occurrence. */
    fun remove(id: String) {
        if (current?.id == id) return
        context.firstOrNull { it.id == id }?.let(::forgetContextItem)
        history = history.filterNot { it.id == id }
        manual = manual.filterNot { it.id == id }
        context = context.filterNot { it.id == id }
    }

    /**
     * A deleted file is different from removing one queue occurrence: forget every occurrence,
     * repeat template and autoplay candidate. Returns whether the active track was removed.
     * Remaining occurrence IDs/order survive; a removed active track advances without history.
     */
    fun removeTrack(key: String): Boolean = removeTracks(setOf(key))

    /** One deletion may have several URI aliases: purge all of them before choosing a next item. */
    fun removeTracks(keys: Set<String>): Boolean {
        val removedCurrent = current?.track?.let(keyOf) in keys
        history = history.filterNot { keyOf(it.track) in keys }
        manual = manual.filterNot { keyOf(it.track) in keys }
        context = context.filterNot { keyOf(it.track) in keys }
        contextCycle = contextCycle.filterNot { keyOf(it.track) in keys }
        autoplayLibrary = autoplayLibrary.filterNot { keyOf(it) in keys }
        failedKeys.removeAll(keys)
        if (removedCurrent) {
            current = null
            advance()
        }
        return removedCurrent
    }

    private fun forgetContextItem(item: Entry<T>) {
        val position = item.contextPosition ?: return
        contextCycle = contextCycle.filterNot { it.position == position }
    }

    /** Cross-source drags become manual. A manual item dropped toward context stays ahead of it. */
    fun move(id: String, beforeId: String?) {
        if (id == beforeId || current?.id == id) return
        val fromContext = context.firstOrNull { it.id == id }
        val item = fromContext ?: manual.firstOrNull { it.id == id } ?: return
        val target = if (beforeId == null) null else
            manual.firstOrNull { it.id == beforeId } ?: context.firstOrNull { it.id == beforeId } ?: return
        val withinContext = fromContext != null && (target == null ||
            (context.any { it.id == target.id } && target.origin == item.origin &&
                target.sourceName == item.sourceName))
        if (withinContext) {
            val rest = context.filterNot { it.id == id }.toMutableList()
            val index = target?.let { to -> rest.indexOfFirst { it.id == to.id } } ?: rest.size
            rest.add(index, item)
            context = rest
        } else {
            forgetContextItem(item)
            context = context.filterNot { it.id == id }
            val rest = manual.filterNot { it.id == id }.toMutableList()
            val index = target?.let { to -> rest.indexOfFirst { it.id == to.id } }
                ?.takeIf { it >= 0 } ?: rest.size
            rest.add(index, item.copy(origin = Origin.Manual, sourceName = "手动添加", contextPosition = null))
            manual = rest
        }
    }

    fun toggleShuffle() {
        if (autoplayEnabled) return
        shuffled = !shuffled
        context = if (shuffled) context.shuffled(random) else context.sortedBy { it.contextPosition ?: Int.MAX_VALUE }
    }

    fun cycleRepeat() {
        if (autoplayEnabled) return
        repeat = when (repeat) {
            Repeat.Off -> Repeat.All
            Repeat.All -> Repeat.One
            Repeat.One -> Repeat.Off
        }
    }

    fun toggleAutoplay() {
        autoplayEnabled = !autoplayEnabled
        if (autoplayEnabled) {
            if (shuffled) context = context.sortedBy { it.contextPosition ?: Int.MAX_VALUE }
            shuffled = false
            repeat = Repeat.Off
        }
    }

    fun setAutoplayLibrary(tracks: List<T>) {
        autoplayLibrary = tracks.filter(isLocal).distinctBy(keyOf)
    }

    private fun autoplayEntry(): Entry<T>? {
        val activeKey = current?.track?.let(keyOf)
        val available = autoplayLibrary.filter { keyOf(it) != activeKey && keyOf(it) !in failedKeys }
        if (available.isEmpty()) return null
        val recent = history.takeLast(40).map { keyOf(it.track) }
        val recentKeys = recent.toSet()
        var candidates = available.filter { keyOf(it) !in recentKeys }
        if (candidates.isEmpty()) {
            // A small library may exhaust the exclusion window. Relax oldest-first, never current.
            val lastSeen = recent.withIndex().associate { it.value to it.index }
            val oldest = available.minOf { lastSeen[keyOf(it)] ?: -1 }
            candidates = available.filter { (lastSeen[keyOf(it)] ?: -1) == oldest }
        }
        val seed = current?.track?.let(tagsOf) ?: Tags()
        val scored = candidates.map { it to tagScore(seed, tagsOf(it)) }
        val bestScore = scored.maxOf { it.second }
        val selected = scored.filter { it.second == bestScore }.random(random).first
        val source = if (bestScore > 0) "本地标签续播" else "本地曲库续播"
        return entry(selected, Origin.Autoplay, source)
    }

    private fun tagScore(seed: Tags, candidate: Tags): Int {
        fun normalize(value: String?): String? = value?.trim()?.lowercase()?.takeIf {
            it.isNotEmpty() && it !in unknownTags
        }
        fun same(left: String?, right: String?): Boolean =
            normalize(left)?.let { it == normalize(right) } == true
        fun genres(value: String?): Set<String> = value.orEmpty()
            .split(',', ';', '/', '|', '、', '，', '；').mapNotNull(::normalize).toSet()
        val sharedGenre = genres(seed.genre).intersect(genres(candidate.genre)).isNotEmpty()
        return (if (sharedGenre) 4 else 0) + (if (same(seed.artist, candidate.artist)) 2 else 0) +
            (if (same(seed.album, candidate.album)) 1 else 0)
    }

    fun clearUpcoming() {
        manual = emptyList()
        context = emptyList()
        contextCycle = emptyList()
    }

    fun updateTrack(track: T) {
        val key = keyOf(track)
        fun replace(item: Entry<T>) = if (keyOf(item.track) == key) item.copy(track = track) else item
        history = history.map(::replace)
        current = current?.let(::replace)
        manual = manual.map(::replace)
        context = context.map(::replace)
        contextCycle = contextCycle.map { if (keyOf(it.track) == key) it.copy(track = track) else it }
        autoplayLibrary = autoplayLibrary.map { if (keyOf(it) == key) track else it }
    }

    private companion object {
        val unknownTags = setOf(
            "<unknown>", "unknown", "unknown artist", "unknown album", "未知", "未知艺术家",
            "未知歌手", "未知专辑", "未知流派", "暂无", "null",
        )
    }
}
