package com.example.xuebimc

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Repository calls enqueue on Dispatchers.IO, after resolving/validating the playback URL. */
object MusicDownloads {
    private const val STORE = "music_download_tasks_v1"
    private const val PREFIX = "task_"
    private const val QUICK_JOB = 0x59594401
    private const val RECOVERY_JOB = 0x59594402
    private const val STAGING = "music-download-staging"
    private val lock = Any()
    const val ACTION_LIBRARY_CHANGED = "com.example.xuebimc.DOWNLOAD_LIBRARY_CHANGED"
    private val revision = MutableStateFlow(0L)
    val updates = revision.asStateFlow()

    enum class State { WAITING, DOWNLOADING, EMBEDDING, COPYING, RETRY, COMPLETE, COMPLETE_WITH_WARNINGS, CANCELLED, FAILED, CONFLICT }

    data class Options(val embedCover: Boolean = true, val embedLyrics: Boolean = true, val qualityLabel: String = "")

    /** No playback URLs/headers are saved here. Completion means the final published bytes were verified. */
    data class PendingDownload(
        val token: String,
        val downloadId: Long,
        val treeUri: String,
        val folderName: String,
        val fileName: String,
        val mimeType: String,
        val stagingUri: String,
        val documentUri: String? = null,
        val state: State = State.WAITING,
        val error: String? = null,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val qualityLabel: String = "",
        val createdAt: Long = 0,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = -1,
        val processedBytes: Long = 0,
        val outputBytes: Long = -1,
        // Existing v1 records did not request embedded tags. Do not silently change their files.
        val embedCover: Boolean = false,
        val embedLyrics: Boolean = false,
        val warning: String? = null,
        internal val lyrics: String = "",
        internal val coverPath: String? = null,
        internal val preparedUri: String? = null,
        internal val legacyDirect: Boolean = false,
        internal val stagingCleaned: Boolean = false,
    ) {
        internal val retryable: Boolean
            get() = state in setOf(State.WAITING, State.DOWNLOADING, State.EMBEDDING, State.COPYING, State.RETRY)
        val finished: Boolean get() = state == State.COMPLETE || state == State.COMPLETE_WITH_WARNINGS
    }

    fun enqueue(context: Context, playableTrack: Track, fileName: String, options: Options = Options()): Long {
        val app = context.applicationContext
        val spec = MusicDownloadRules.fileSpec(fileName, playableTrack.mimeType,
            playableTrack.uri.lastPathSegment.orEmpty())
        val prefs = app.getSharedPreferences(AppPreferences.PREF_NAME, Context.MODE_PRIVATE)
        val (tree, folder) = synchronized(prefs) {
            prefs.getString(AppPreferences.KEY_DOWNLOAD_TREE_URI, null)?.trim().orEmpty() to
                prefs.getString(AppPreferences.KEY_DOWNLOAD_FOLDER_NAME, "所选目录").orEmpty()
        }
        val token = UUID.randomUUID().toString()
        val name = spec.uniqueName(token)
        // Legacy Android needs a user-granted SAF directory; do not finish into an inaccessible folder.
        if (tree.isBlank() && Build.VERSION.SDK_INT < 29) throw IOException("此 Android 版本请先在设置中选择下载文件夹")
        if (tree.isNotBlank()) requireTreeGrant(app, Uri.parse(tree))
        val base = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("下载临时目录不可用；未更换下载目录")
        val staging = File(base, STAGING)
        if (!staging.isDirectory && !staging.mkdirs()) throw IOException("无法创建下载临时目录")
        val source = File(staging, "$token.${spec.extension}")
        val cover = File(staging, "$token.cover")
        val hasCover = options.embedCover && MusicMetadataEmbedder.cacheCover(app, playableTrack.artworkUri, cover)
        val lyricsText = if (options.embedLyrics) playableTrack.lines.joinToString("\n") { line ->
            val time = line.startMs.coerceAtLeast(0L)
            "[${time / 60_000}:${((time % 60_000) / 1000).toString().padStart(2, '0')}.${(time % 1000).toString().padStart(3, '0')}]${line.text}"
        } else ""
        val lyrics = lyricsText.takeIf { it.length <= 512 * 1024 }.orEmpty()
        val request = DownloadManager.Request(playableTrack.uri)
            .setTitle(playableTrack.title).setMimeType(spec.mimeType)
            .setDescription("余音 · 下载后内嵌标签并保存到${if (tree.isBlank()) "Music/余音" else folder}")
            // DownloadManager completion is staging only, never final publication.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "$STAGING/${source.name}")
        playableTrack.requestHeaders.forEach { (key, value) -> request.addRequestHeader(key, value) }
        val task = PendingDownload(token, -1, tree, if (tree.isBlank()) "Music/余音" else folder,
            name, spec.mimeType, Uri.fromFile(source).toString(), title = playableTrack.title,
            artist = playableTrack.artist, album = playableTrack.album, qualityLabel = options.qualityLabel,
            createdAt = System.currentTimeMillis(), embedCover = options.embedCover, embedLyrics = options.embedLyrics,
            lyrics = lyrics, coverPath = cover.path.takeIf { hasCover },
            warning = "歌词超过内嵌大小限制，未截断或写入歌词".takeIf { lyricsText.length > 512 * 1024 })
        synchronized(lock) {
            // Persist the immutable target BEFORE enqueue. Recover the narrow enqueue/ID-commit
            // crash window by matching this unique staging URI against this app's DM rows.
            save(app, task)
            if (!scheduleRecovery(app)) {
                save(app, task.copy(state = State.FAILED, error = "系统拒绝后台恢复任务；下载尚未开始"))
                throw IOException("无法启用下载恢复任务；下载尚未开始")
            }
            val id = try { manager(app).enqueue(request) } catch (error: Exception) {
                save(app, task.copy(state = State.FAILED, error = "系统未接受下载任务；未开始下载"))
                throw IOException("系统未接受下载任务；请检查网络和存储设置", error)
            }
            save(app, task.copy(downloadId = id))
            scheduleQuick(app)
            return id
        }
    }

    /** Safe for Main to call at startup. No provider access or copying happens on its thread.
     * Returns false if Android refused scheduling; pending records/files are still retained.
     */
    fun retryPending(context: Context): Boolean = synchronized(lock) {
        val app = context.applicationContext
        if (records(app).none { it.retryable || needsCleanup(it) }) return@synchronized true
        val recovery = scheduleRecovery(app)
        val quick = scheduleQuick(app)
        recovery || quick
    }

    /** Inspect retained failures without exposing signed URLs/headers or claiming DM == SAF success. */
    fun pending(context: Context): List<PendingDownload> = synchronized(lock) {
        records(context.applicationContext).filterNot { it.finished }
    }

    /** Query on IO. Download progress is actual system byte counts, never an invented timer. */
    fun history(context: Context): List<PendingDownload> = synchronized(lock) {
        val app = context.applicationContext
        val downloads = rows(app).associateBy { it.id }
        importLegacyDownloads(app, downloads.values)
        records(app).map { task ->
            val row = downloads[task.downloadId]
            if (row == null && task.retryable && task.downloadId >= 0) {
                task.copy(state = State.CANCELLED, error = "系统下载任务已移除；未重新请求源地址").also { save(app, it) }
            } else if (row == null || task.finished || !task.retryable || !owns(task, row)) task else {
                val state = when (row.status) {
                    DownloadManager.STATUS_FAILED -> State.FAILED
                    DownloadManager.STATUS_RUNNING -> State.DOWNLOADING
                    DownloadManager.STATUS_SUCCESSFUL -> if (task.legacyDirect) State.COMPLETE else task.state
                    else -> if (task.state == State.DOWNLOADING) State.WAITING else task.state
                }
                val problem = when (row.status) {
                    DownloadManager.STATUS_FAILED -> "系统下载失败（${row.reason}）；请重新发起下载"
                    DownloadManager.STATUS_PAUSED -> "系统下载已暂停，等待网络或系统调度"
                    else -> task.error.takeIf { state == State.RETRY }
                }
                val next = task.copy(state = state, downloadedBytes = row.downloaded.coerceAtLeast(0),
                    totalBytes = row.total, error = problem,
                    documentUri = if (task.legacyDirect && state == State.COMPLETE)
                        manager(app).getUriForDownloadedFile(task.downloadId)?.toString() else task.documentUri)
                if (next != task) save(app, next)
                if (next.finished && !task.finished) runCatching { refreshMediaLibrary(app, next) }
                if (row.status == DownloadManager.STATUS_SUCCESSFUL && !next.finished) scheduleQuick(app)
                next
            }
        }.sortedByDescending { it.createdAt }
    }

    /** Resume only an existing successful staged download; never replay an expiring source URL. */
    fun retry(context: Context, token: String): Boolean = synchronized(lock) {
        val app = context.applicationContext
        val task = records(app).firstOrNull { it.token == token } ?: return@synchronized false
        if (task.finished || task.legacyDirect || task.state == State.CONFLICT) return@synchronized false
        val row = rows(app, task.downloadId).singleOrNull() ?: return@synchronized false
        if (!owns(task, row) || row.status == DownloadManager.STATUS_FAILED) return@synchronized false
        save(app, task.copy(state = State.WAITING, error = null))
        val recovery = scheduleRecovery(app)
        val quick = scheduleQuick(app)
        recovery || quick
    }

    fun isDownloadComplete(context: Context, downloadId: Long): Boolean = runCatching {
        val task = synchronized(lock) { records(context.applicationContext).firstOrNull { it.downloadId == downloadId } }
        if (task != null) task.finished
        else rows(context.applicationContext, downloadId).firstOrNull()?.status == DownloadManager.STATUS_SUCCESSFUL
    }.getOrDefault(false)

    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)
    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)
    private fun preferences(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun records(context: Context): List<PendingDownload> =
        preferences(context).all.filterKeys { it.startsWith(PREFIX) }.values.mapNotNull { value -> runCatching {
            val json = JSONObject(value as String)
            PendingDownload(json.getString("token"), json.getLong("id"), json.getString("tree"),
                json.getString("folder"), json.getString("name"), json.getString("mime"),
                json.getString("staging"), json.optString("document").takeIf { it.isNotEmpty() },
                State.valueOf(json.getString("state")), json.optString("error").takeIf { it.isNotEmpty() },
                title = json.optString("title", json.optString("name")), artist = json.optString("artist"),
                album = json.optString("album"), qualityLabel = json.optString("quality"),
                createdAt = json.optLong("created"), downloadedBytes = json.optLong("downloaded"),
                totalBytes = json.optLong("total", -1), processedBytes = json.optLong("processed"),
                outputBytes = json.optLong("output", -1), embedCover = json.optBoolean("embedCover", false),
                embedLyrics = json.optBoolean("embedLyrics", false), warning = json.optString("warning").ifEmpty { null },
                lyrics = json.optString("lyrics"), coverPath = json.optString("coverPath").ifEmpty { null },
                preparedUri = json.optString("prepared").ifEmpty { null }, legacyDirect = json.optBoolean("legacy", false),
                stagingCleaned = json.optBoolean("cleaned", false))
        }.getOrNull() }

    private fun save(context: Context, task: PendingDownload) = synchronized(lock) {
        val json = JSONObject().put("token", task.token).put("id", task.downloadId)
            .put("tree", task.treeUri).put("folder", task.folderName).put("name", task.fileName)
            .put("mime", task.mimeType).put("staging", task.stagingUri)
            .put("document", task.documentUri.orEmpty()).put("state", task.state.name)
            .put("error", task.error.orEmpty())
            .put("title", task.title).put("artist", task.artist).put("album", task.album)
            .put("quality", task.qualityLabel).put("created", task.createdAt)
            .put("downloaded", task.downloadedBytes).put("total", task.totalBytes)
            .put("processed", task.processedBytes).put("output", task.outputBytes)
            .put("embedCover", task.embedCover).put("embedLyrics", task.embedLyrics)
            .put("warning", task.warning.orEmpty()).put("lyrics", task.lyrics)
            .put("coverPath", task.coverPath.orEmpty()).put("prepared", task.preparedUri.orEmpty())
            .put("legacy", task.legacyDirect).put("cleaned", task.stagingCleaned)
        if (!preferences(context).edit().putString(PREFIX + task.token, json.toString()).commit()) {
            throw IOException("无法保存下载进度；临时文件保留")
        }
        revision.value += 1
    }

    private fun scheduleRecovery(context: Context): Boolean = runCatching {
        val jobs = scheduler(context)
        jobs.getPendingJob(RECOVERY_JOB) != null || jobs.schedule(
            JobInfo.Builder(RECOVERY_JOB, ComponentName(context, MusicDownloadCopyService::class.java))
                .setPersisted(true).setPeriodic(15 * 60 * 1000L).build()
        ) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)

    private fun scheduleQuick(context: Context): Boolean = runCatching {
        val jobs = scheduler(context)
        jobs.getPendingJob(QUICK_JOB) != null || jobs.schedule(
            JobInfo.Builder(QUICK_JOB, ComponentName(context, MusicDownloadCopyService::class.java))
                .setMinimumLatency(0).setOverrideDeadline(30_000L)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        ) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)

    private data class DownloadRow(val id: Long, val uri: String?, val status: Int, val total: Long,
        val reason: Int, val downloaded: Long, val title: String, val mimeType: String, val modifiedAt: Long)

    private fun rows(context: Context, id: Long? = null): List<DownloadRow> {
        val query = DownloadManager.Query().apply { if (id != null) setFilterById(id) }
        return manager(context).query(query)?.use { cursor ->
            val result = mutableListOf<DownloadRow>()
            while (cursor.moveToNext()) {
                result += DownloadRow(cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty(),
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)).orEmpty(),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)))
            }
            result
        } ?: throw IOException("暂时无法读取系统下载任务")
    }

    private fun owns(task: PendingDownload, row: DownloadRow): Boolean =
        MusicDownloadRules.owns(task.downloadId, task.stagingUri, row.id, row.uri)

    /** Before v2 the default directory used DM directly and had no app history. Import, never edit. */
    private fun importLegacyDownloads(context: Context, downloads: Collection<DownloadRow>) {
        val known = records(context).map { it.downloadId }.toSet()
        @Suppress("DEPRECATION")
        val legacyFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "余音").absolutePath + "/"
        for (row in downloads) {
            if (row.id in known || !row.mimeType.startsWith("audio/")) continue
            val uri = row.uri?.let(Uri::parse) ?: continue
            if (uri.scheme != "file" || !uri.path.orEmpty().startsWith(legacyFolder)) continue
            val state = when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> State.COMPLETE
                DownloadManager.STATUS_FAILED -> State.FAILED
                DownloadManager.STATUS_RUNNING -> State.DOWNLOADING
                else -> State.WAITING
            }
            val output = if (row.status == DownloadManager.STATUS_SUCCESSFUL)
                manager(context).getUriForDownloadedFile(row.id)?.toString() else null
            save(context, PendingDownload("legacy-${row.id}", row.id, "", "Music/余音",
                uri.lastPathSegment.orEmpty(), row.mimeType, row.uri, documentUri = output,
                state = state, title = row.title, createdAt = row.modifiedAt, downloadedBytes = row.downloaded,
                totalBytes = row.total, outputBytes = row.total, legacyDirect = true,
                warning = "旧版下载记录；原文件保持不变"))
        }
    }

    /** Receiver calls this on a short-lived background worker, never trusts extras beyond the ID.
     * DM's normal query is scoped to our UID; also require our record AND exact staging destination.
     */
    internal fun onDownloadBroadcast(context: Context, id: Long) {
        val tasks = synchronized(lock) { records(context).filter { it.retryable && (it.downloadId == id || it.downloadId == -1L) } }
        if (tasks.isEmpty()) return
        val row = rows(context, id).singleOrNull() ?: return
        if (tasks.any { owns(it, row) }) scheduleQuick(context)
    }

    private fun requireTreeGrant(context: Context, tree: Uri) {
        if (tree.scheme != "content" || !DocumentsContract.isTreeUri(tree) ||
            context.contentResolver.persistedUriPermissions.none {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            }) throw IOException("所选目录缺少持久读写授权；请重新授权原目录，临时文件保留")
    }

    private fun parentDirectory(context: Context, tree: Uri, signal: CancellationSignal): Uri {
        requireTreeGrant(context, tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
        val writable = context.contentResolver.query(parent, projection, null, null, null, signal)?.use {
            it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR &&
                it.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        } == true
        if (!writable) throw IOException("原下载目录不可写或已移除；临时文件保留")
        return parent
    }

    internal fun copyPending(context: Context, signal: CancellationSignal, checkRunning: () -> Unit) {
        val tasks = synchronized(lock) { records(context).filter { it.retryable } }
        for (snapshot in tasks) {
            checkRunning()
            var task = snapshot
            try {
                // Serialise the ID reconciliation with enqueue's write/submit/write sequence.
                val row = synchronized(lock) {
                    task = records(context).first { it.token == snapshot.token }
                    if (!task.retryable) return@synchronized null
                    val row = if (task.downloadId < 0) rows(context).firstOrNull { owns(task, it) }
                        else rows(context, task.downloadId).singleOrNull()
                    if (row != null && task.downloadId < 0) {
                        task = task.copy(downloadId = row.id)
                        save(context, task)
                    }
                    row
                }
                if (!task.retryable) continue
                if (row == null) {
                    save(context, task.copy(state = State.CANCELLED,
                        error = "系统下载任务不存在、未提交或已取消；不自动重新下载，已有文件保留"))
                    continue
                }
                if (!owns(task, row)) {
                    save(context, task.copy(state = State.CONFLICT, error = "无法确认系统下载任务归属；未触碰任何文件"))
                    continue
                }
                if (row.status == DownloadManager.STATUS_FAILED) {
                    save(context, task.copy(state = State.FAILED,
                        error = "系统下载失败（${row.reason}）；不自动重新下载，已有文件保留"))
                    continue
                }
                task = task.copy(downloadedBytes = row.downloaded.coerceAtLeast(0), totalBytes = row.total)
                if (row.status != DownloadManager.STATUS_SUCCESSFUL) {
                    save(context, task.copy(state = if (row.status == DownloadManager.STATUS_RUNNING) State.DOWNLOADING else State.WAITING))
                    continue
                }
                if (task.legacyDirect) {
                    val complete = task.copy(state = State.COMPLETE, error = null,
                        documentUri = manager(context).getUriForDownloadedFile(row.id)?.toString())
                    save(context, complete)
                    runCatching { refreshMediaLibrary(context, complete) }
                    continue
                }
                val original = File(requireNotNull(Uri.parse(task.stagingUri).path))
                if (!original.isFile || original.length() <= 0 || (row.total >= 0 && original.length() != row.total)) {
                    throw IOException("下载临时文件缺失或大小不符；未报告完成")
                }
                if (task.preparedUri == null) {
                    task = task.copy(state = State.EMBEDDING, error = null)
                    save(context, task)
                    val result = if (task.createdAt == 0L && !task.embedCover && !task.embedLyrics) {
                        // v1 could queue artwork companions too. Finish those already-queued files unchanged.
                        val oldFormat = MusicDownloadRules.fileSpec(task.fileName, task.mimeType, original.name)
                        MusicMetadataEmbedder.Result(original,
                            MusicMetadataEmbedder.Format(oldFormat.extension, oldFormat.mimeType), null)
                    } else MusicMetadataEmbedder.prepare(original, task.token,
                        MusicMetadataEmbedder.Metadata(task.title, task.artist, task.album, task.lyrics,
                            task.coverPath?.let(::File), task.embedCover, task.embedLyrics), checkRunning)
                    task = task.copy(preparedUri = Uri.fromFile(result.file).toString(), outputBytes = result.file.length(),
                        fileName = task.fileName.substringBeforeLast('.') + "." + result.format.extension,
                        mimeType = result.format.mimeType, warning = listOfNotNull(task.warning, result.warning)
                            .takeIf { it.isNotEmpty() }?.joinToString("；"), state = State.COPYING)
                    save(context, task) // tag writes are checkpointed BEFORE creating a final destination
                }
                val source = File(requireNotNull(Uri.parse(task.preparedUri).path))
                val size = source.length()
                if (!source.isFile || size <= 0 || size != task.outputBytes) throw IOException("内嵌后的临时文件不完整")
                checkRunning()
                if (task.documentUri == null) {
                    val document = createDestination(context, task, signal)
                    task = task.copy(documentUri = document.toString(), state = State.COPYING, error = null)
                    save(context, task) // checkpoint before the first byte is written
                }
                val destination = Uri.parse(task.documentUri)
                val input = {
                    val descriptor = context.contentResolver.openFileDescriptor(destination, "r", signal)
                        ?: throw IOException("无法校验目标文件")
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor) as InputStream
                }
                val sourceInput = { source.inputStream() as InputStream }
                when (MusicDownloadRules.compare(sourceInput, input, size, checkRunning)) {
                    MusicDownloadRules.CopyMatch.DIFFERENT -> {
                        save(context, task.copy(state = State.CONFLICT,
                            error = "目标副本内容已改变；为避免覆盖而停止，临时文件保留"))
                        continue
                    }
                    MusicDownloadRules.CopyMatch.PREFIX -> {
                        // Only rewrite our own persisted URI, after proving its bytes are an exact
                        // prefix of this source. Modified/unrelated documents are never truncated.
                        var progressAt = 0L
                        MusicDownloadRules.copy(sourceInput, {
                            val descriptor = context.contentResolver.openFileDescriptor(destination, "rwt", signal)
                                ?: throw IOException("无法写入目标文件")
                            object : FilterOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                                private var copied = 0L
                                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                                    out.write(bytes, offset, count)
                                    copied += count
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - progressAt >= 500 || copied == size) {
                                        progressAt = now
                                        task = task.copy(state = State.COPYING, processedBytes = copied)
                                        save(context, task)
                                    }
                                }
                            }
                        }, size, checkRunning)
                        if (MusicDownloadRules.compare(sourceInput, input, size, checkRunning) != MusicDownloadRules.CopyMatch.COMPLETE) {
                            throw IOException("目标副本校验失败；临时文件保留")
                        }
                    }
                    MusicDownloadRules.CopyMatch.COMPLETE -> Unit // killed after close, before commit
                }
                checkRunning()
                val current = rows(context, task.downloadId).singleOrNull()
                if (current == null || !owns(task, current) || current.status != DownloadManager.STATUS_SUCCESSFUL) {
                    throw IOException("系统下载任务已取消或发生变化；未报告完成")
                }
                if (task.treeUri.isNotBlank()) requireTreeGrant(context, Uri.parse(task.treeUri))
                else if (Build.VERSION.SDK_INT >= 29) {
                    val changed = context.contentResolver.update(destination, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                    if (changed != 1) throw IOException("无法将下载文件发布到媒体库")
                }
                task = task.copy(state = if (task.warning.isNullOrBlank()) State.COMPLETE else State.COMPLETE_WITH_WARNINGS,
                    processedBytes = size, error = null)
                registerInLibrary(context, task)
                save(context, task)
                runCatching { refreshMediaLibrary(context, task) }
                cleanupStaging(context, task)
            } catch (error: Exception) {
                if (error is InvalidMusicDownloadException) {
                    save(context, task.copy(state = State.FAILED, error = "下载内容不是可识别的音频文件；未发布到媒体库"))
                    continue
                }
                // Provider/network exception text can contain credentials: persist only our own text.
                val message = if (signal.isCanceled || error is InterruptedIOException || Thread.currentThread().isInterrupted)
                    "复制中断，等待恢复；临时文件保留"
                else "原目录暂不可用、授权不足或复制失败；临时文件保留，可重试原目录"
                save(context, task.copy(state = State.RETRY, error = message))
                checkRunning()
            }
        }
        // A crash after the completion checkpoint must not retain two large audio copies forever.
        synchronized(lock) { records(context).filter(::needsCleanup) }.forEach { cleanupStaging(context, it) }
        synchronized(lock) {
            if (records(context).none { it.retryable || needsCleanup(it) }) scheduler(context).cancel(RECOVERY_JOB)
        }
    }

    private fun registerInLibrary(context: Context, task: PendingDownload) {
        if (!task.mimeType.startsWith("audio/")) return
        val document = task.documentUri?.takeIf { Uri.parse(it).scheme == "content" } ?: return
        val library = context.getSharedPreferences("local_library", Context.MODE_PRIVATE)
        synchronized(library) {
            val current = library.getStringSet("imported_uris", emptySet()).orEmpty().toSet()
            val editor = library.edit().putStringSet("imported_uris", current + document)
            if (!library.contains("imported_at:$document")) editor.putLong("imported_at:$document", System.currentTimeMillis())
            if (!editor.commit()) {
                throw IOException("音频已写入，资料库登记失败；可重试登记")
            }
        }
    }

    private fun needsCleanup(task: PendingDownload): Boolean =
        task.finished && !task.legacyDirect && task.createdAt > 0 && !task.stagingCleaned

    /** Delete only app-created, token-owned staging paths AFTER the completion record is durable. */
    private fun cleanupStaging(context: Context, task: PendingDownload) {
        if (!needsCleanup(task)) return
        runCatching {
            require(task.token.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            val staging = File(base, STAGING).canonicalFile
            val candidates = listOfNotNull(task.stagingUri, task.preparedUri).map { value ->
                val uri = Uri.parse(value)
                require(uri.scheme == "file")
                File(requireNotNull(uri.path))
            } + listOf(File(staging, "${task.token}.cover"),
                File(staging, "${task.token}.prepared.${task.fileName.substringAfterLast('.')}"))
            val owned = candidates.distinctBy { it.absolutePath }.map { file ->
                require(file.parentFile?.canonicalFile == staging && file.canonicalFile.parentFile == staging)
                require(file.canonicalFile.name == file.name) // never follow a link to another task's file
                require(file.name.matches(Regex(Regex.escape(task.token) + "\\.(prepared\\.)?[a-zA-Z0-9]{1,8}")))
                file
            }
            val deleted = owned.map { !it.exists() || (it.isFile && it.delete()) }.all { it }
            if (deleted) synchronized(lock) {
                records(context).firstOrNull { it.token == task.token && it.finished }?.let {
                    save(context, it.copy(stagingCleaned = true))
                }
            }
        } // Cleanup failure does not undo a verified, published download. Recovery retries it later.
    }

    private fun createDestination(context: Context, task: PendingDownload, signal: CancellationSignal): Uri {
        if (task.treeUri.isNotBlank()) {
            val parent = parentDirectory(context, Uri.parse(task.treeUri), signal)
            // Allocate a new document, never look up and overwrite a user's file with the same name.
            val document = DocumentsContract.createDocument(context.contentResolver, parent, task.mimeType, task.fileName)
                ?: throw IOException("无法在原目录创建文件")
            if (document.authority != parent.authority || document == parent) throw IOException("无效目标文件")
            return document
        }
        if (Build.VERSION.SDK_INT < 29) throw IOException("请在设置中选择下载文件夹")
        return context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, task.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, task.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/余音")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Audio.Media.TITLE, task.title)
            put(MediaStore.Audio.Media.ARTIST, task.artist)
            put(MediaStore.Audio.Media.ALBUM, task.album)
        }) ?: throw IOException("无法创建媒体库文件")
    }

    private fun refreshMediaLibrary(context: Context, task: PendingDownload) {
        val destination = task.documentUri?.let(Uri::parse) ?: return
        fun publish() {
            synchronized(lock) { revision.value += 1 }
            context.sendBroadcast(Intent(ACTION_LIBRARY_CHANGED).setPackage(context.packageName))
        }
        val path = runCatching {
            when {
                task.legacyDirect -> Uri.parse(task.stagingUri).path
                destination.scheme == "file" -> destination.path
                task.treeUri.isBlank() -> {
                    @Suppress("DEPRECATION")
                    context.contentResolver.query(destination, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }
                destination.authority == "com.android.externalstorage.documents" -> {
                    val documentId = DocumentsContract.getDocumentId(destination)
                    val volume = documentId.substringBefore(':')
                    val relative = documentId.substringAfter(':', "")
                    @Suppress("DEPRECATION")
                    val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory() else File("/storage", volume)
                    val file = File(base, relative)
                    file.canonicalPath.takeIf { it.startsWith(base.canonicalPath + "/") }
                }
                else -> null // A cloud/virtual document is not necessarily addressable by MediaScanner.
            }
        }.getOrNull()
        if (path != null) MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(task.mimeType)) { _, uri ->
            if (task.legacyDirect && uri != null) synchronized(lock) {
                records(context).firstOrNull { it.token == task.token }?.let { save(context, it.copy(documentUri = uri.toString())) }
            }
            publish() // notify again after asynchronous MediaStore extraction has finished
        }
        publish() // Also refresh while the library is currently visible or the provider has no path.
    }
}

/** Exported only to accept DownloadManager's system broadcast. It cannot initiate arbitrary copies. */
class MusicDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val result = goAsync()
        val app = context.applicationContext
        receipts.execute {
            try { MusicDownloads.onDownloadBroadcast(app, id) }
            catch (_: Exception) { /* Persisted recovery job/Main.retryPending will reconcile. */ }
            finally { result.finish() }
        }
    }

    companion object {
        private val receipts = Executors.newSingleThreadExecutor()
    }
}

/** A separate serial worker prevents periodic/quick jobs (including stopped runs) writing together. */
class MusicDownloadCopyService : JobService() {
    private class Run {
        val stopped = AtomicBoolean(false)
        val signal = CancellationSignal()
        var future: Future<*>? = null
        fun checkRunning() {
            if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Stopped")
        }
        fun stop() {
            stopped.set(true)
            future?.cancel(true)
            signal.cancel()
        }
    }

    private val runs = mutableMapOf<Int, Run>() // only accessed on the service main thread
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        val run = Run()
        runs.put(params.jobId, run)?.stop()
        run.future = copies.submit {
            var retry = false
            try { MusicDownloads.copyPending(applicationContext, run.signal, run::checkRunning) }
            catch (_: Exception) { retry = true }
            finally {
                main.post {
                    if (runs[params.jobId] === run) {
                        runs.remove(params.jobId)
                        jobFinished(params, retry)
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runs.remove(params.jobId)?.stop()
        return true
    }

    override fun onDestroy() {
        runs.values.forEach { it.stop() }
        runs.clear()
        super.onDestroy()
    }

    companion object {
        private val copies = Executors.newSingleThreadExecutor()
    }
}

/** Android-free rules used by the standalone JVM check in tools/MusicDownloadsCheck.kt. */
internal object MusicDownloadRules {
    data class FileSpec(val stem: String, val extension: String, val mimeType: String) {
        fun uniqueName(token: String) = "$stem-$token.$extension"
    }

    private val formats = mapOf(
        "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/aac" to "aac",
        "audio/flac" to "flac", "audio/ogg" to "ogg", "audio/opus" to "opus",
        "audio/wav" to "wav", "audio/aiff" to "aiff", "audio/amr" to "amr",
        "audio/3gpp" to "3gp", "audio/webm" to "webm", "video/mp4" to "mp4",
        "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp",
    )
    private val aliases = mapOf(
        "audio/mp3" to "audio/mpeg", "audio/x-mpeg" to "audio/mpeg", "audio/x-flac" to "audio/flac",
        "audio/x-m4a" to "audio/mp4", "audio/m4a" to "audio/mp4", "audio/x-aac" to "audio/aac",
        "audio/aacp" to "audio/aac", "application/ogg" to "audio/ogg", "audio/x-wav" to "audio/wav",
        "audio/vnd.wave" to "audio/wav", "audio/x-aiff" to "audio/aiff",
    )

    fun fileSpec(fileName: String, mimeType: String?, urlName: String): FileSpec {
        val declared = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val canonical = aliases[declared] ?: declared
        fun mimeForName(name: String): String? {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return formats.entries.firstOrNull { it.value == extension }?.key
        }
        val mime = if (canonical.isEmpty() || canonical in setOf("application/octet-stream", "binary/octet-stream"))
            mimeForName(urlName) ?: mimeForName(fileName)
        else canonical.takeIf { it in formats }
        if (mime == null) throw IOException("无法确定下载文件格式；未猜测为 MP3")
        var stem = Normalizer.normalize(fileName, Normalizer.Form.NFC)
            .replace(Regex("[\\p{Cc}\\p{Cf}/\\\\:*?\"<>|]"), "_").trim().trim('.')
        if (stem.substringAfterLast('.', "").matches(Regex("[A-Za-z0-9]{1,8}")) && '.' in stem) {
            stem = stem.substringBeforeLast('.')
        }
        // Bound UTF-8 bytes, not UTF-16 characters: Chinese/emoji names must also fit FAT/SAF limits.
        val safe = StringBuilder()
        var bytes = 0
        val codePoints = stem.codePoints().iterator()
        while (codePoints.hasNext()) {
            val point = codePoints.nextInt()
            val character = if (point in 0xD800..0xDFFF) "_" else String(Character.toChars(point))
            val count = character.toByteArray(Charsets.UTF_8).size
            if (bytes + count > 160) break
            safe.append(character)
            bytes += count
        }
        return FileSpec(safe.toString().trim().trim('.').ifBlank { "音乐" }, formats.getValue(mime), mime)
    }

    fun owns(recordedId: Long, expectedUri: String, actualId: Long, actualUri: String?): Boolean =
        actualId >= 0 && (recordedId == -1L || recordedId == actualId) && expectedUri == actualUri

    enum class CopyMatch { PREFIX, COMPLETE, DIFFERENT }

    /** Allows crash recovery without overwriting a modified target or duplicating a complete copy. */
    fun compare(source: () -> InputStream, target: () -> InputStream, expected: Long,
        checkRunning: () -> Unit): CopyMatch = source().use { original ->
        target().use { copy ->
            val left = ByteArray(64 * 1024)
            val right = ByteArray(left.size)
            var total = 0L
            while (true) {
                checkRunning()
                val count = copy.read(right)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > expected) return CopyMatch.DIFFERENT
                var read = 0
                while (read < count) {
                    checkRunning()
                    val amount = original.read(left, read, count - read)
                    if (amount < 0) return CopyMatch.DIFFERENT
                    read += amount
                }
                for (index in 0 until count) if (left[index] != right[index]) return CopyMatch.DIFFERENT
            }
            checkRunning()
            if (total == expected && original.read() == -1) CopyMatch.COMPLETE
            else if (total < expected) CopyMatch.PREFIX else CopyMatch.DIFFERENT
        }
    }

    /** Closing/flushing the provider is part of copying; neither an early EOF nor close error succeeds. */
    fun copy(source: () -> InputStream, target: () -> OutputStream, expected: Long, checkRunning: () -> Unit) {
        require(expected > 0)
        source().use { input ->
            target().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    checkRunning()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > expected) throw IOException("Source grew")
                    output.write(buffer, 0, count)
                }
                if (total != expected) throw IOException("Source truncated")
                checkRunning()
                output.flush()
            }
        }
        checkRunning()
    }
}
