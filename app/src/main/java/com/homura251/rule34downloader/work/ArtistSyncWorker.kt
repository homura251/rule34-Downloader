package com.homura251.rule34downloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.homura251.rule34downloader.data.CredentialsStore
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
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.network.Rule34PoolClient
import com.homura251.rule34downloader.data.Rule34Post
import com.homura251.rule34downloader.storage.ExistingDownloads
import com.homura251.rule34downloader.storage.MediaStoreDownloader
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

class ArtistSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val artistTag = inputData.getString(KEY_ARTIST_TAG)?.trim().orEmpty()
        if (artistTag.isEmpty()) return@withContext Result.failure()
        // Also serialize tags that sanitize to the same physical folder.
        SyncControls.gate(MediaStoreDownloader.buildRelativePath(artistTag)).withLock { syncArtist(artistTag) }
    }

    private suspend fun syncArtist(artistTag: String): Result {
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
            setForeground(notifications.foregroundInfo(artistTag, 0, 0))
            val network = Rule34Network.get(applicationContext)
            val http = network.client.newBuilder()
                .addInterceptor(control.interceptor).build()
            val credentials = CredentialsStore(applicationContext).get()
            val api = credentials?.let { Rule34Client(it, control::checkActive) { connection -> control.register(connection::disconnect) } }
            val html = network.htmlClient(http, control::checkActive)
            val pageSize = if (api != null) Rule34Client.MAX_POSTS_PER_PAGE else Rule34HtmlClient.POSTS_PER_PAGE
            val downloader = MediaStoreDownloader(applicationContext, http, control::checkActive, control::register)
            downloader.cleanInterruptedFiles(artistTag)
            database.resetInProgress(artistTag)
            val existing = ExistingDownloads(applicationContext, artistTag, control::checkActive)
            // Repair revoked/unreadable URIs and check completions from older versions.
            for (record in database.getSavedRecords(artistTag)) {
                control.checkActive()
                if (record.localUri == null && record.status != DownloadStatus.DOWNLOADED) continue
                val saved = existing.find(record, control::checkActive)
                if (saved != null) database.markDownloaded(artistTag, record.postId, saved.uri.toString(), saved.bytes, saved.verifiedMd5)
                else database.invalidateSavedFile(artistTag, record.postId)
            }
            fun saveDiscovered(posts: List<Rule34Post>) {
                control.checkActive()
                database.insertDiscoveredPosts(artistTag, posts)
                // Associate old files as each page is discovered so the gallery can
                // immediately use local originals, even during a large initial scan.
                for (post in posts) {
                    control.checkActive()
                    val record = DownloadRecord(artistTag, post.id, post.fileUrl, post.md5, DownloadStatus.PENDING, 0, 0)
                    existing.find(record, control::checkActive)?.let {
                        database.markDownloaded(artistTag, post.id, it.uri.toString(), it.bytes, it.verifiedMd5)
                    }
                }
            }
            if (artist.poolId != null) {
                // Pools can add old posts or change their reading order. Refresh all
                // membership pages, then fetch metadata only for unknown post IDs.
                val pool = Rule34PoolClient(html, control::checkActive).getPool(artist.poolId)
                control.checkActive()
                database.replacePoolMembership(artistTag, pool.title, pool.posts)
                val known = database.getKnownPostIds(artistTag)
                for ((index, member) in pool.posts.filterNot { it.id in known }.withIndex()) {
                    control.checkActive()
                    if (index > 0) delay(if (api != null) API_PAGE_DELAY_MS else HTML_PAGE_DELAY_MS)
                    val post = api?.getPost(member.id) ?: html.getPostWithArtists(member.id).post
                    saveDiscovered(listOf(post.copy(previewUrl = member.previewUrl ?: post.previewUrl)))
                }
            } else {
                var page = 0
                var maxSeen = artist.lastSeenPostId
                while (true) {
                    control.checkActive()
                    val posts = api?.searchPosts(artistTag, artist.lastSeenPostId, page, pageSize)
                        ?: html.searchPosts(artistTag, artist.lastSeenPostId, page)
                    saveDiscovered(posts)
                    posts.maxOfOrNull { it.id }?.let { maxSeen = maxOf(maxSeen, it) }
                    page++
                    if (posts.size < pageSize) break
                    delay(if (api != null) API_PAGE_DELAY_MS else HTML_PAGE_DELAY_MS)
                }
                control.checkActive()
                // Commit the discovery watermark only after every page has been saved.
                if (maxSeen > artist.lastSeenPostId) database.updateLastSeenPostId(artistTag, maxSeen)
            }
            val queue = database.getDownloadQueue(artistTag)
            var completed = 0
            var downloaded = 0
            var failed = 0
            setForeground(notifications.foregroundInfo(artistTag, 0, queue.size))
            for (record in queue) {
                control.checkActive()
                val old = existing.find(record, control::checkActive)
                if (old != null) {
                    database.markDownloaded(artistTag, record.postId, old.uri.toString(), old.bytes, old.verifiedMd5)
                    completed++
                    continue
                }
                database.markDownloading(artistTag, record.postId, record.totalBytes)
                notifications.updateProgress(artistTag, completed, queue.size, record.postId)
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
                    database.markDownloaded(artistTag, record.postId, result.uri.toString(), result.bytesWritten, result.verifiedMd5)
                    downloaded++
                } catch (e: Exception) {
                    control.checkActive() // Pause/cancellation must never count as a failed file.
                    if (e is CloudflareChallengeException) {
                        database.markFailed(artistTag, record.postId, e.message.orEmpty())
                        throw HtmlChallengeException(e.message.orEmpty(), e)
                    }
                    failed++
                    database.markFailed(artistTag, record.postId, e.message ?: e.javaClass.simpleName)
                }
                completed++
                notifications.updateProgress(artistTag, completed, queue.size, null)
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
        private const val API_PAGE_DELAY_MS = 250L
        private const val HTML_PAGE_DELAY_MS = 1_000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val MAX_FILE_RETRY_RUNS = 3
    }
}
