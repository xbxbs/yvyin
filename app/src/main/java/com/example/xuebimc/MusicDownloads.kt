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
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
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

    enum class State { WAITING, COPYING, RETRY, COMPLETE, CANCELLED, FAILED, CONFLICT }

    /** No playback URLs/headers are saved here. A COMPLETE record means the SAF copy was verified. */
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
    ) {
        internal val retryable: Boolean
            get() = state in setOf(State.WAITING, State.COPYING, State.RETRY)
    }

    fun enqueue(context: Context, playableTrack: Track, fileName: String): Long {
        val app = context.applicationContext
        val spec = MusicDownloadRules.fileSpec(fileName, playableTrack.mimeType,
            playableTrack.uri.lastPathSegment.orEmpty())
        val prefs = app.getSharedPreferences(AppPreferences.PREF_NAME, Context.MODE_PRIVATE)
        val (tree, folder) = synchronized(prefs) {
            prefs.getString(AppPreferences.KEY_DOWNLOAD_TREE_URI, null)?.trim().orEmpty() to
                prefs.getString(AppPreferences.KEY_DOWNLOAD_FOLDER_NAME, "所选目录").orEmpty()
        }
        val request = DownloadManager.Request(playableTrack.uri)
            .setTitle(playableTrack.title).setMimeType(spec.mimeType)
        // These are only the resolver's playback/download headers, never artwork/API headers.
        playableTrack.requestHeaders.forEach { (key, value) -> request.addRequestHeader(key, value) }
        val manager = manager(app)
        val token = UUID.randomUUID().toString()
        val name = spec.uniqueName(token)
        if (tree.isBlank()) {
            request.setDescription("余音 · 在线下载")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "余音/$name")
            @Suppress("DEPRECATION")
            request.allowScanningByMediaScanner()
            return manager.enqueue(request)
        }

        // Fail before starting a download if the chosen tree has lost its persistent grant.
        requireTreeGrant(app, Uri.parse(tree))
        val base = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("下载临时目录不可用；未更换下载目录")
        val staging = File(base, STAGING)
        if (!staging.isDirectory && !staging.mkdirs()) throw IOException("无法创建下载临时目录")
        val source = File(staging, "$token.${spec.extension}")
        request.setDescription("余音 · 下载到临时区后复制至所选目录")
            // DownloadManager finishing the staging download is NOT completion of the SAF copy.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "$STAGING/${source.name}")
        val task = PendingDownload(token, -1, tree, folder, name, spec.mimeType, Uri.fromFile(source).toString())
        synchronized(lock) {
            // Persist the immutable target BEFORE enqueue. Recover the narrow enqueue/ID-commit
            // crash window by matching this unique staging URI against this app's DM rows.
            save(app, task)
            if (!scheduleRecovery(app)) throw IOException("无法启用下载恢复任务；下载尚未开始")
            val id = manager.enqueue(request)
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
        if (records(app).none { it.retryable }) return@synchronized true
        val recovery = scheduleRecovery(app)
        val quick = scheduleQuick(app)
        recovery || quick
    }

    /** Inspect retained failures without exposing signed URLs/headers or claiming DM == SAF success. */
    fun pending(context: Context): List<PendingDownload> = synchronized(lock) {
        records(context.applicationContext).filter { it.state != State.COMPLETE }
    }

    fun isDownloadComplete(context: Context, downloadId: Long): Boolean = runCatching {
        val task = synchronized(lock) { records(context.applicationContext).firstOrNull { it.downloadId == downloadId } }
        if (task != null) task.state == State.COMPLETE
        else rows(context.applicationContext, downloadId).firstOrNull()?.status == DownloadManager.STATUS_SUCCESSFUL
    }.getOrDefault(false)

    fun writeTextCompanion(context: Context, text: String, fileName: String, mime: String): Boolean = runCatching {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(AppPreferences.PREF_NAME, Context.MODE_PRIVATE)
        val tree = prefs.getString(AppPreferences.KEY_DOWNLOAD_TREE_URI, null)?.takeIf { it.isNotBlank() }
        val uri: Uri = (if (tree == null && android.os.Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/余音")
            }
            app.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        } else if (tree != null) {
            val parent = parentDirectory(app, Uri.parse(tree), CancellationSignal())
            DocumentsContract.createDocument(app.contentResolver, parent, mime, fileName)
        } else null) ?: throw IOException("无法创建附加文件")
        app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("无法写入附加文件")
        true
    }.getOrDefault(false)

    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)
    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)
    private fun preferences(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun records(context: Context): List<PendingDownload> =
        preferences(context).all.filterKeys { it.startsWith(PREFIX) }.values.map { value ->
            val json = JSONObject(value as String)
            PendingDownload(json.getString("token"), json.getLong("id"), json.getString("tree"),
                json.getString("folder"), json.getString("name"), json.getString("mime"),
                json.getString("staging"), json.optString("document").takeIf { it.isNotEmpty() },
                State.valueOf(json.getString("state")), json.optString("error").takeIf { it.isNotEmpty() })
        }

    private fun save(context: Context, task: PendingDownload) = synchronized(lock) {
        val json = JSONObject().put("token", task.token).put("id", task.downloadId)
            .put("tree", task.treeUri).put("folder", task.folderName).put("name", task.fileName)
            .put("mime", task.mimeType).put("staging", task.stagingUri)
            .put("document", task.documentUri.orEmpty()).put("state", task.state.name)
            .put("error", task.error.orEmpty())
        if (!preferences(context).edit().putString(PREFIX + task.token, json.toString()).commit()) {
            throw IOException("无法保存下载进度；临时文件保留")
        }
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

    private data class DownloadRow(val id: Long, val uri: String?, val status: Int, val total: Long, val reason: Int)

    private fun rows(context: Context, id: Long? = null): List<DownloadRow> {
        val query = DownloadManager.Query().apply { if (id != null) setFilterById(id) }
        return manager(context).query(query)?.use { cursor ->
            val result = mutableListOf<DownloadRow>()
            while (cursor.moveToNext()) {
                result += DownloadRow(cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
            }
            result
        } ?: throw IOException("暂时无法读取系统下载任务")
    }

    private fun owns(task: PendingDownload, row: DownloadRow): Boolean =
        MusicDownloadRules.owns(task.downloadId, task.stagingUri, row.id, row.uri)

    /** Receiver calls this on a short-lived background worker, never trusts extras beyond the ID.
     * DM's normal query is scoped to our UID; also require our record AND exact staging destination.
     */
    internal fun onDownloadBroadcast(context: Context, id: Long) {
        val tasks = synchronized(lock) { records(context).filter { it.retryable && (it.downloadId == id || it.downloadId == -1L) } }
        if (tasks.isEmpty()) return
        val row = rows(context, id).singleOrNull() ?: return
        if (row.status == DownloadManager.STATUS_SUCCESSFUL && tasks.any { owns(it, row) }) scheduleQuick(context)
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
                    val row = if (task.downloadId < 0) rows(context).firstOrNull { owns(task, it) }
                        else rows(context, task.downloadId).singleOrNull()
                    if (row != null && task.downloadId < 0) {
                        task = task.copy(downloadId = row.id)
                        save(context, task)
                    }
                    row
                }
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
                if (row.status != DownloadManager.STATUS_SUCCESSFUL) continue
                val source = File(requireNotNull(Uri.parse(task.stagingUri).path))
                val size = source.length()
                if (!source.isFile || size <= 0 || (row.total >= 0 && size != row.total)) {
                    throw IOException("下载临时文件缺失或大小不符；未报告完成")
                }
                val parent = parentDirectory(context, Uri.parse(task.treeUri), signal)
                checkRunning()
                if (task.documentUri == null) {
                    // Never find/open a user's existing file by display name. createDocument must
                    // allocate a NEW document (and the UUID also avoids ordinary name collisions).
                    val document = DocumentsContract.createDocument(context.contentResolver, parent,
                        task.mimeType, task.fileName) ?: throw IOException("无法在原目录创建文件")
                    if (document.authority != parent.authority || document == parent) {
                        throw IOException("目录提供方返回了无效的目标文件")
                    }
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
                        MusicDownloadRules.copy(sourceInput, {
                            val descriptor = context.contentResolver.openFileDescriptor(destination, "rwt", signal)
                                ?: throw IOException("无法写入目标文件")
                            ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
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
                requireTreeGrant(context, Uri.parse(task.treeUri))
                save(context, task.copy(state = State.COMPLETE, error = null))
                // Intentionally retain staging even after success. No DM.remove/deleteDocument,
                // no deletion of user files, no expiring URL retained for an implicit re-download.
            } catch (error: Exception) {
                // Provider/network exception text can contain credentials: persist only our own text.
                val message = if (signal.isCanceled || error is InterruptedIOException || Thread.currentThread().isInterrupted)
                    "复制中断，等待恢复；临时文件保留"
                else "原目录暂不可用、授权不足或复制失败；临时文件保留，可重试原目录"
                save(context, task.copy(state = State.RETRY, error = message))
                checkRunning()
            }
        }
        synchronized(lock) {
            if (records(context).none { it.retryable }) scheduler(context).cancel(RECOVERY_JOB)
        }
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
