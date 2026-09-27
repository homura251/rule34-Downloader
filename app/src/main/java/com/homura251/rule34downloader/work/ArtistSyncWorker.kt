package com.homura251.rule34downloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.homura251.rule34downloader.data.CredentialsStore
import com.homura251.rule34downloader.data.Rule34Database
import com.homura251.rule34downloader.data.SyncState
import com.homura251.rule34downloader.network.ApiException
import com.homura251.rule34downloader.network.AuthException
import com.homura251.rule34downloader.network.RetryableApiException
import com.homura251.rule34downloader.network.Rule34Client
import com.homura251.rule34downloader.network.Rule34HtmlClient
import com.homura251.rule34downloader.storage.MediaStoreDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import kotlin.coroutines.coroutineContext

class ArtistSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val artistTag = inputData.getString(KEY_ARTIST_TAG)?.trim().orEmpty()
        if (artistTag.isEmpty()) return@withContext Result.failure()

        val database = Rule34Database.getInstance(applicationContext)
        val artist = database.getArtist(artistTag) ?: return@withContext Result.success()
        val credentials = CredentialsStore(applicationContext).get()
        val apiClient = credentials?.let(::Rule34Client)
        val htmlClient = if (credentials == null) Rule34HtmlClient() else null
        val pageSize = if (apiClient != null) {
            Rule34Client.MAX_POSTS_PER_PAGE
        } else {
            Rule34HtmlClient.POSTS_PER_PAGE
        }

        val notifications = DownloadNotifications(applicationContext)
        val downloader = MediaStoreDownloader(applicationContext)
        database.setSyncState(artistTag, SyncState.SYNCING)
        setForeground(notifications.foregroundInfo(artistTag, 0, 0))

        try {
            var page = 0
            var maxSeen = artist.lastSeenPostId
            do {
                coroutineContext.ensureActive()
                val posts = apiClient?.searchPosts(
                    artistTag = artistTag,
                    afterPostId = artist.lastSeenPostId,
                    page = page,
                    limit = Rule34Client.MAX_POSTS_PER_PAGE,
                ) ?: htmlClient!!.searchPosts(
                    artistTag = artistTag,
                    afterPostId = artist.lastSeenPostId,
                    page = page,
                )

                database.insertDiscoveredPosts(artistTag, posts)
                posts.maxOfOrNull { it.id }?.let { maxSeen = maxOf(maxSeen, it) }
                page++
                if (posts.size < pageSize) break
                delay(if (apiClient != null) API_PAGE_DELAY_MS else HTML_PAGE_DELAY_MS)
            } while (true)

            if (maxSeen > artist.lastSeenPostId) {
                database.updateLastSeenPostId(artistTag, maxSeen)
            }

            val queue = database.getDownloadQueue(artistTag)
            var completed = 0
            var downloadedThisRun = 0
            var failed = 0
            notifications.updateProgress(artistTag, completed, queue.size, null)
            setForeground(notifications.foregroundInfo(artistTag, completed, queue.size))

            for (record in queue) {
                coroutineContext.ensureActive()
                database.markDownloading(artistTag, record.postId, record.totalBytes)
                notifications.updateProgress(artistTag, completed, queue.size, record.postId)

                var lastProgressUpdate = 0L
                try {
                    val result = downloader.download(
                        record = record,
                        onHeaders = { total ->
                            database.markDownloading(artistTag, record.postId, total)
                        },
                        onProgress = { bytes, total ->
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS || bytes == total) {
                                database.updateDownloadProgress(artistTag, record.postId, bytes, total)
                                lastProgressUpdate = now
                            }
                        },
                    )
                    database.markDownloaded(artistTag, record.postId, result.uri.toString())
                    if (!result.existed) downloadedThisRun++
                } catch (e: InterruptedIOException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    database.markFailed(
                        artistTag,
                        record.postId,
                        e.message ?: e.javaClass.simpleName,
                    )
                }
                completed++
                notifications.updateProgress(artistTag, completed, queue.size, null)
            }

            val finalState = if (failed > 0) SyncState.ERROR else SyncState.COMPLETE
            database.setSyncState(
                artistTag,
                finalState,
                error = if (failed > 0) "$failed 个文件下载失败，将在下次同步重试。" else null,
                markSyncTime = true,
            )
            notifications.showFinished(artistTag, downloadedThisRun, failed)

            if (failed > 0 && runAttemptCount < MAX_FILE_RETRY_RUNS) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: AuthException) {
            database.setSyncState(
                artistTag,
                SyncState.ERROR,
                error = e.message,
                markSyncTime = true,
            )
            Result.failure()
        } catch (e: RetryableApiException) {
            database.setSyncState(
                artistTag,
                SyncState.ERROR,
                error = e.message,
                markSyncTime = true,
            )
            Result.retry()
        } catch (e: ApiException) {
            database.setSyncState(
                artistTag,
                SyncState.ERROR,
                error = e.message,
                markSyncTime = true,
            )
            Result.failure()
        } catch (e: InterruptedIOException) {
            database.setSyncState(
                artistTag,
                SyncState.IDLE,
                error = "同步已取消。",
            )
            Result.failure()
        } catch (e: Exception) {
            database.setSyncState(
                artistTag,
                SyncState.ERROR,
                error = e.message ?: e.javaClass.simpleName,
                markSyncTime = true,
            )
            Result.retry()
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
