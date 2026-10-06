package com.example.xuebimc

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Local playlist metadata store. A malformed existing file is never replaced: loading then
 * remains unready and mutations are refused until the user has dealt with that file.
 */
class PlaylistRepository(context: Context) {
    private val store = AtomicFile(File(context.applicationContext.filesDir, "playlists.json"))
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _playlists = MutableStateFlow<List<MusicPlaylist>>(emptyList())
    val playlists: StateFlow<List<MusicPlaylist>> = _playlists.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        scope.launch { load() }
    }

    suspend fun create(title: String, tracks: List<Track> = emptyList()): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_ready.value) {
                _error.value = "播放列表尚未成功加载，已拒绝写入以保护现有数据。"
                return@withLock ""
            }
            val now = System.currentTimeMillis()
            val playlist = MusicPlaylist(
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = now,
                updatedAt = now,
                entries = tracks.map { PlaylistEntry(UUID.randomUUID().toString(), it, now) },
            )
            val next = _playlists.value + playlist
            if (writeStore(next)) {
                _playlists.value = next
                _error.value = null
                playlist.id
            } else {
                ""
            }
        }
    }

    suspend fun updateInfo(id: String, title: String, subtitle: String, creator: String) {
        change(id) { it.copy(title = title, subtitle = subtitle, creator = creator) }
    }

    suspend fun toggleFavorite(id: String) {
        change(id) { it.copy(favorite = !it.favorite) }
    }

    suspend fun setCustomCover(id: String, uri: String?) {
        change(id) { it.copy(customCoverUri = uri) }
    }

    suspend fun setSort(id: String, sort: PlaylistSort, ascending: Boolean) {
        change(id) { it.copy(sort = sort, ascending = ascending) }
    }

    suspend fun addTracks(id: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        change(id) { playlist ->
            val now = System.currentTimeMillis()
            // Each supplied occurrence gets an entry UUID; equal tracks are intentionally retained.
            playlist.copy(entries = playlist.entries + tracks.map {
                PlaylistEntry(UUID.randomUUID().toString(), it, now)
            })
        }
    }

    suspend fun removeEntries(id: String, entryIds: Set<String>) {
        if (entryIds.isEmpty()) return
        change(id) { it.copy(entries = it.entries.filterNot { entry -> entry.id in entryIds }) }
    }

    suspend fun reorder(id: String, entryIds: List<String>) {
        change(id) { playlist ->
            val byId = playlist.entries.associateBy { it.id }
            val seen = HashSet<String>()
            val ordered = entryIds.mapNotNull { entryId ->
                if (seen.add(entryId)) byId[entryId] else null
            }
            // Unknown and omitted IDs cannot accidentally delete entries.
            playlist.copy(entries = ordered + playlist.entries.filterNot { it.id in seen })
        }
    }

    suspend fun transfer(sourceId: String, targetId: String, entryIds: Set<String>, move: Boolean = true) {
        if (sourceId == targetId || entryIds.isEmpty()) return
        mutate { current ->
            val sourceIndex = current.indexOfFirst { it.id == sourceId }
            val targetIndex = current.indexOfFirst { it.id == targetId }
            if (sourceIndex < 0 || targetIndex < 0) return@mutate current
            val source = current[sourceIndex]
            val selected = source.entries.filter { it.id in entryIds }
            if (selected.isEmpty()) return@mutate current
            val target = current[targetIndex]
            val now = System.currentTimeMillis()
            val changedSource = if (move) source.copy(
                entries = source.entries.filterNot { it.id in entryIds },
                updatedAt = now,
            ) else source
            // A transfer creates fresh occurrences in the destination. Repeated copy operations
            // must never reuse an existing lazy-list key or falsify the destination's added time.
            val changedTarget = target.copy(entries = target.entries + selected.map {
                it.copy(id = UUID.randomUUID().toString(), addedAt = now)
            }, updatedAt = now)
            current.mapIndexed { index, playlist ->
                when (index) {
                    sourceIndex -> changedSource
                    targetIndex -> changedTarget
                    else -> playlist
                }
            }
        }
    }

    suspend fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val hasCommittedData = store.baseFile.exists() || File(store.baseFile.path + ".bak").exists()
                val loaded = if (hasCommittedData) parseStore(store.openRead().bufferedReader().use { it.readText() }) else emptyList()
                _playlists.value = loaded
                _error.value = null
                _ready.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _ready.value = false
                _error.value = "无法读取播放列表存储；为保护现有数据已禁止写入：${failure.message ?: failure.javaClass.simpleName}"
            }
        }
    }

    private suspend fun change(id: String, transform: (MusicPlaylist) -> MusicPlaylist) {
        mutate { current ->
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) current else current.toMutableList().also { list ->
                list[index] = transform(list[index]).copy(updatedAt = System.currentTimeMillis())
            }
        }
    }

    private suspend fun mutate(transform: (List<MusicPlaylist>) -> List<MusicPlaylist>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!_ready.value) {
                    _error.value = "播放列表尚未成功加载，已拒绝写入以保护现有数据。"
                    return@withLock
                }
                val next = transform(_playlists.value)
                if (next === _playlists.value || next == _playlists.value) {
                    _error.value = null
                    return@withLock
                }
                if (writeStore(next)) {
                    _playlists.value = next
                    _error.value = null
                }
            }
        }

    }

    private fun writeStore(playlists: List<MusicPlaylist>): Boolean {
        var output: FileOutputStream? = null
        try {
            output = store.startWrite()
            output.write(serializeStore(playlists).toByteArray(Charsets.UTF_8))
            store.finishWrite(output)
            return true
        } catch (cancelled: CancellationException) {
            output?.let(store::failWrite)
            throw cancelled
        } catch (failure: Exception) {
            output?.let(store::failWrite)
            _error.value = "无法保存播放列表：${failure.message ?: failure.javaClass.simpleName}"
            return false
        }
    }

    private fun serializeStore(playlists: List<MusicPlaylist>): String = JSONObject().apply {
        put("version", 1)
        put("playlists", JSONArray().apply { playlists.forEach { put(it.toJson()) } })
    }.toString()

    private fun parseStore(text: String): List<MusicPlaylist> {
        val root = JSONObject(text)
        require(root.optInt("version", -1) == 1) { "不支持此歌单数据版本，已保留原文件" }
        val array = root.getJSONArray("playlists")
        return List(array.length()) { index -> array.getJSONObject(index).toPlaylist() }.also { playlists ->
            require(playlists.map { it.id }.distinct().size == playlists.size) { "歌单标识重复，已保留原文件" }
            require(playlists.all { list -> list.entries.map { it.id }.distinct().size == list.entries.size }) {
                "歌单条目标识重复，已保留原文件"
            }
        }
    }
}

private fun MusicPlaylist.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    put("creator", creator)
    put("subtitle", subtitle)
    put("favorite", favorite)
    putNullable("customCoverUri", customCoverUri)
    put("sort", sort.name)
    put("ascending", ascending)
    put("entries", JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("id", entry.id)
                put("addedAt", entry.addedAt)
                put("track", TrackJson.trackToJson(entry.track))
            })
        }
    })
}

private fun JSONObject.toPlaylist(): MusicPlaylist {
    val entries = getJSONArray("entries")
    return MusicPlaylist(
        id = getString("id"),
        title = optString("title", ""),
        createdAt = optLong("createdAt", 0L),
        updatedAt = optLong("updatedAt", 0L),
        creator = optString("creator", "我"),
        subtitle = optString("subtitle", ""),
        favorite = optBoolean("favorite", false),
        customCoverUri = nullableString("customCoverUri"),
        entries = List(entries.length()) { index ->
            entries.getJSONObject(index).let { entry ->
                PlaylistEntry(entry.getString("id"), TrackJson.fromJson(entry.getJSONObject("track")), entry.optLong("addedAt", 0L))
            }
        },
        sort = nullableString("sort")?.let { runCatching { PlaylistSort.valueOf(it) }.getOrNull() } ?: PlaylistSort.Custom,
        ascending = optBoolean("ascending", true),
    )
}
