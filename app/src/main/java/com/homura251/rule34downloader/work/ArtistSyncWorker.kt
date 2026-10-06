package com.homura251.rule34downloader.work

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.homura251.rule34downloader.data.DownloadRecord
import com.homura251.rule34downloader.data.DownloadStatus
import com.homura251.rule34downloader.data.Rule34Database
import com.homura251.rule34downloader.data.SyncState
import com.homura251.rule34downloader.network.ApiException
import com.homura251.rule34downloader.network.AuthException
import com.homura251.rule34downloader.network.CloudflareChallengeException
import com.homura251.rule34downloader.network.HtmlChallengeException
import com.homura251.rule34downloader.network.RetryableApiException
import com.homura251.rule34downloader.network.Rule34Client
import com.homura251.rule34downloader.network.Rule34HtmlClient
import com.homura251.rule34downloader.network.Rule34PoolClient
import com.homura251.rule34downloader.data.Rule34Post
import com.homura251.rule34downloader.storage.ExistingDownloads
import com.homura251.rule34downloader.storage.FileChecksum
import com.homura251.rule34downloader.storage.MediaStoreDownloader
import com.homura251.rule34downloader.storage.RetryableDownloadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext as currentCoroutineContext

class ArtistSyncWorker internal constructor(appContext: Context, workerParams: WorkerParameters,
    private val servicesFactory: SyncServicesFactory) : CoroutineWorker(appContext, workerParams) {
    constructor(appContext: Context, workerParams: WorkerParameters) : this(appContext, workerParams, ProductionSyncServices)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val artistTag = inputData.getString(KEY_ARTIST_TAG)?.trim().orEmpty()
        if (artistTag.isEmpty()) return@withContext Result.failure()
        val userInitiated = inputData.getBoolean(KEY_USER_INITIATED, false)
        // Also serialize tags that sanitize to the same physical folder.
        SyncControls.gate(MediaStoreDownloader.buildRelativePath(artistTag)).withLock { syncArtist(artistTag, userInitiated) }
    }

    private suspend fun syncArtist(artistTag: String, userInitiated: Boolean): Result {
        currentCoroutineContext.ensureActive()
        val database = Rule34Database.getInstance(applicationContext)
        val artist = database.getArtist(artistTag) ?: return Result.success()
        if (artist.paused) {
            database.finishPaused(artistTag)
            return Result.success()
        }
        val workContext = currentCoroutineContext
        val control = SyncControl { workContext.ensureActive() }
        SyncControls.register(artistTag, control)
        val cancellationWatcher = CoroutineScope(workContext).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { control.pause() }
        }
        val notifications = DownloadNotifications(applicationContext)
        return try {
            if (!database.setSyncState(artistTag, SyncState.SYNCING)) return Result.success()
            if (userInitiated) {
                try {
                    setForeground(notifications.foregroundInfo(artistTag, 0, 0))
                } catch (e: IllegalStateException) {
                    val denied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
                    if (!denied) throw e
                    // WorkManager can still run this request as ordinary background work.
                    // Do not fail the sync merely because Android denied FGS promotion.
                }
            }
            val services = servicesFactory.create(applicationContext, control)
            val html = services.html
            val api = services.api
            val downloader = services.downloader
            downloader.cleanInterruptedFiles(artistTag)
            database.resetInProgress(artistTag)
            val existing = ExistingDownloads(applicationContext, artistTag, control::checkActive)

            // Older anonymous records can have an empty MD5 and are skipped by the
            // normal "new posts only" cursor forever. Repair those records directly
            // from the authenticated API before old-file verification or queue retry.
            if (api != null) {
                val stale = database.getSavedRecords(artistTag).filter {
                    FileChecksum.expectedForReuse(it.md5, it.verifiedMd5, it.fileUrl) == null
                }
                for ((index, record) in stale.withIndex()) {
                    control.checkActive()
                    if (index > 0) delay(services.detailDelayMs)
                    database.upsertDiscoveredPosts(artistTag, listOf(api.getPost(record.postId)))
                }
            }

            // Replace membership only after the complete pool scan succeeds.
            val pool = artist.poolId?.let { Rule34PoolClient(html, control::checkActive).getPool(it) }
            if (pool != null) {
                control.checkActive()
                database.replacePoolMembership(artistTag, pool.title, pool.posts)
            }
            val reuseChecked = mutableSetOf<Long>()
            val reuseRejections = mutableMapOf<Long, String>()
            for (record in database.getSavedRecords(artistTag)) {
                control.checkActive()
                if (record.localUri == null && record.status != DownloadStatus.DOWNLOADED) continue
                reuseChecked += record.postId
                val lookup = existing.lookup(record, control::checkActive)
                val saved = lookup.match
                if (saved != null) {
                    database.markDownloaded(artistTag, record.postId, saved.uri.toString(), saved.bytes, saved.verifiedMd5)
                } else {
                    lookup.rejection?.let { reuseRejections[record.postId] = it }
                    database.invalidateSavedFile(artistTag, record.postId)
                }
            }
            val known = database.getSavedRecords(artistTag).associateBy { it.postId }.toMutableMap()
            var completed = known.values.count { it.status == DownloadStatus.DOWNLOADED }
            var downloaded = 0
            var failed = 0
            val attempted = mutableSetOf<Long>()

            suspend fun downloadRecord(record: DownloadRecord) {
                control.checkActive()
                if (!attempted.add(record.postId)) return
                val old = if (reuseChecked.add(record.postId)) {
                    val lookup = existing.lookup(record, control::checkActive)
                    lookup.rejection?.let { reuseRejections[record.postId] = it }
                    lookup.match
                } else null
                if (old != null) {
                    database.markDownloaded(artistTag, record.postId, old.uri.toString(), old.bytes, old.verifiedMd5)
                    known[record.postId] = record.copy(status = DownloadStatus.DOWNLOADED, localUri = old.uri.toString(),
                        bytesDownloaded = old.bytes, verifiedMd5 = old.verifiedMd5)
                    completed++
                    return
                }
                database.markDownloading(artistTag, record.postId, record.totalBytes)
                notifications.updateProgress(artistTag, completed, known.size, record.postId)
                var lastProgressUpdate = 0L
                try {
                    val result = downloader.download(record,
                        onHeaders = { total -> control.checkActive(); database.markDownloading(artistTag, record.postId, total) },
                        onProgress = { bytes, total ->
                            control.checkActive()
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS || bytes == total) {
                                database.updateDownloadProgress(artistTag, record.postId, bytes, total)
                                lastProgressUpdate = now
                            }
                        },
                    )
                    // If pause arrives after publication, retain the verified URI;
                    // the next run will recover it instead of downloading again.
                    database.markDownloaded(artistTag, record.postId, result.uri.toString(), result.bytesWritten, result.verifiedMd5)
                    known[record.postId] = record.copy(status = DownloadStatus.DOWNLOADED, localUri = result.uri.toString(),
                        bytesDownloaded = result.bytesWritten, verifiedMd5 = result.verifiedMd5)
                    downloaded++
                } catch (e: Exception) {
                    control.checkActive()
                    when (e) {
                        is RetryableDownloadException -> throw e
                        is CloudflareChallengeException -> throw HtmlChallengeException(e.message.orEmpty(), e)
                        else -> {
                            failed++
                            val downloadError = e.message ?: e.javaClass.simpleName
                            val reuseError = reuseRejections[record.postId]
                            database.markFailed(
                                artistTag,
                                record.postId,
                                if (reuseError == null) downloadError else "$reuseError；重新下载失败：$downloadError",
                            )
                        }
                    }
                }
                completed++
                database.setSyncState(artistTag, SyncState.SYNCING)
                notifications.updateProgress(artistTag, completed, known.size, null)
            }

            suspend fun saveAndDownload(post: Rule34Post) {
                control.checkActive()
                database.upsertDiscoveredPosts(artistTag, listOf(post))
                val previous = known[post.id]
                val record = if (previous == null) {
                    DownloadRecord(artistTag, post.id, post.fileUrl, post.md5, DownloadStatus.PENDING, 0, 0)
                } else {
                    previous.copy(
                        fileUrl = post.fileUrl,
                        md5 = if (FileChecksum.normalize(post.md5) != null) post.md5 else previous.md5,
                    )
                }
                known[post.id] = record
                if (previous == null || previous.status != DownloadStatus.DOWNLOADED) downloadRecord(record)
            }

            // Resume the persisted queue first, before another discovery scan.
            for (record in database.getDownloadQueue(artistTag)) downloadRecord(record)
            if (pool != null) {
                var details = 0
                for (member in pool.posts) {
                    control.checkActive()
                    if (details++ > 0) delay(services.detailDelayMs)
                    val post = api?.getPost(member.id) ?: html.getPostWithArtists(member.id).post
                    saveAndDownload(post.copy(previewUrl = member.previewUrl ?: post.previewUrl))
                }
            } else {
                var before: Long? = null
                var maxSeen = artist.lastSeenPostId
                var details = 0
                while (true) {
                    control.checkActive()
                    val posts = api?.searchPosts(artistTag, artist.lastSeenPostId, 0, beforePostId = before)
                    val page = if (api == null) html.getSearchPage(artistTag, artist.lastSeenPostId, before) else null
                    val ids = (page?.ids ?: posts!!.map { it.id }).distinct().sortedDescending()
                    if (before != null && ids.any { it >= before!! }) {
                        throw RetryableApiException("作品列表未遵守分页边界，本次扫描未标记完成。")
                    }
                    for (id in ids.filter { it > artist.lastSeenPostId }) {
                        control.checkActive()
                        maxSeen = maxOf(maxSeen, id)
                        val post = if (posts != null) posts.first { it.id == id } else {
                            if (details++ > 0) delay(services.detailDelayMs)
                            html.getPostWithArtists(id).post.let { it.copy(previewUrl = page!!.previews[id] ?: it.previewUrl) }
                        }
                        saveAndDownload(post)
                    }
                    // Only an exhausted ID range commits the watermark. Pause,
                    // metadata failure or an ignored cursor leaves it unchanged.
                    if (ids.isEmpty() || ids.size < (if (api == null) Rule34HtmlClient.POSTS_PER_PAGE else Rule34Client.MAX_POSTS_PER_PAGE) ||
                        ids.any { it <= artist.lastSeenPostId }) break
                    before = ids.min()
                    delay(services.pageDelayMs)
                }
                control.checkActive()
                if (maxSeen > artist.lastSeenPostId) database.updateLastSeenPostId(artistTag, maxSeen)
            }
            control.checkActive()
            database.setSyncState(artistTag, if (failed > 0) SyncState.ERROR else SyncState.COMPLETE,
                error = if (failed > 0) "$failed 个文件下载失败，将在下次同步重试。" else null, markSyncTime = true)
            if (!database.isPaused(artistTag)) notifications.showFinished(artistTag, downloaded, failed)
            if (failed > 0 && runAttemptCount < MAX_FILE_RETRY_RUNS) Result.retry() else Result.success()
        } catch (e: Exception) {
            when {
                control.paused || database.isPaused(artistTag) -> Result.success()
                e is CancellationException -> {
                    database.setSyncState(artistTag, SyncState.IDLE)
                    throw e
                }
                else -> {
                    database.setSyncState(artistTag, SyncState.ERROR, error = e.message ?: e.javaClass.simpleName, markSyncTime = true)
                    if (e is AuthException || (e is ApiException && e !is RetryableApiException)) Result.failure() else Result.retry()
                }
            }
        } finally {
            cancellationWatcher.cancel()
            database.resetInProgress(artistTag)
            SyncControls.unregister(artistTag, control)
            if (database.isPaused(artistTag)) {
                notifications.cancel(artistTag)
                database.finishPaused(artistTag)
            }
        }
    }

    companion object {
        const val KEY_ARTIST_TAG = "artist_tag"
        const val KEY_USER_INITIATED = "user_initiated"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val MAX_FILE_RETRY_RUNS = 3
    }
}
