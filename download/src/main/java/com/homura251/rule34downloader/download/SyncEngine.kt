package com.homura251.rule34downloader.download

import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.domain.files.FileChecksum
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID

sealed interface SyncOutcome {
    data class Complete(val downloaded: Int, val failed: Int) : SyncOutcome
    data class Retry(val delayMillis: Long) : SyncOutcome
    data object NeedsAuthentication : SyncOutcome
    data object Blocked : SyncOutcome
    data object Skipped : SyncOutcome
}

fun interface SyncObserver { fun progress(completed: Int, total: Int, currentPostId: Long?) }

/** No Android, WorkManager, HTTP-client or database implementation is visible to the engine. */
class SyncEngine(
    private val repository: SyncRepository,
    private val catalog: CatalogGateway,
    private val downloader: MediaDownloader,
    private val reuse: FileReuse,
    private val observer: SyncObserver = SyncObserver { _, _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(id: String, runId: String = UUID.randomUUID().toString()): SyncOutcome {
        val collection = repository.getCollection(id) ?: return SyncOutcome.Skipped
        if (collection.paused || collection.poolId != null || !repository.beginRun(id, runId)) return SyncOutcome.Skipped
        return try {
            downloader.cleanInterruptedFiles(id)
            repository.resetInterrupted(id, runId)
            discover(id, runId, collection.lastSeenPostId)
            repository.setState(id, runId, SyncState.VERIFYING)
            repairLegacyMetadata(id, runId)
            for (record in repository.records(id)) {
                checkActive(id)
                if (record.localUri == null && record.status != DownloadStatus.DOWNLOADED) continue
                val result = reuse.lookup(record)
                val file = result.file
                if (file != null) repository.markDownloaded(id, runId, record.postId, file)
                else repository.invalidateFile(id, runId, record.postId)
            }
            repository.setState(id, runId, SyncState.SYNCING)
            var downloaded = 0
            val initial = repository.records(id)
            val total = initial.size
            var completed = initial.count { it.status == DownloadStatus.DOWNLOADED }
            for (record in repository.queue(id)) {
                checkActive(id)
                val existing = reuse.lookup(record)
                val existingFile = existing.file
                if (existingFile != null) {
                    repository.markDownloaded(id, runId, record.postId, existingFile)
                    completed++
                    continue
                }
                repository.markDownloading(id, runId, record.postId, 0)
                observer.progress(completed, total, record.postId)
                try {
                    val saved = transferWithProgress(id, runId, record)
                    // Persist a published, verified URI even when pause arrives at the publication boundary.
                    withContext(NonCancellable) { repository.markDownloaded(id, runId, record.postId, saved) }
                    downloaded++
                    completed++
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    checkActive(id)
                    if (e is SourceFailure && (e.retryable || e.kind == FailureKind.AUTH)) throw e
                    repository.markFailed(id, runId, record.postId,
                        listOfNotNull(existing.rejection, e.userMessage()).joinToString("；"))
                    if (e is SourceFailure && e.kind == FailureKind.BLOCKED) throw e
                }
            }
            checkActive(id)
            val failed = repository.records(id).count { it.status == DownloadStatus.FAILED }
            if (failed > 0 && repository.queue(id).isNotEmpty()) {
                retry(id, runId, collection.retryAttempts, "部分文件下载失败，稍后自动重试。", null)
            } else {
                repository.setState(id, runId, if (failed > 0) SyncState.ERROR else SyncState.COMPLETE,
                    if (failed > 0) "$failed 个文件下载失败，可在任务页重试。" else null)
                SyncOutcome.Complete(downloaded, failed)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { repository.setState(id, runId, SyncState.IDLE) }
            throw e
        } catch (e: SourceFailure) {
            when {
                e.kind == FailureKind.AUTH -> { repository.setState(id, runId, SyncState.WAITING_AUTH, e.message); SyncOutcome.NeedsAuthentication }
                e.retryable -> retry(id, runId, collection.retryAttempts, e.message, e.retryAfterMillis,
                    if (e.kind == FailureKind.NETWORK) SyncState.WAITING_NETWORK else SyncState.WAITING_RETRY)
                else -> { repository.setState(id, runId, SyncState.BLOCKED, e.message); SyncOutcome.Blocked }
            }
        } catch (e: Exception) {
            repository.setState(id, runId, SyncState.ERROR, e.userMessage())
            SyncOutcome.Blocked
        } finally {
            withContext(NonCancellable) { repository.endRun(id, runId) }
        }
    }

    private suspend fun discover(id: String, runId: String, watermark: Long) {
        var checkpoint = repository.checkpoint(id)?.takeIf { it.lowerWatermark == watermark } ?: ScanCheckpoint(watermark)
        while (true) {
            checkActive(id)
            val page = catalog.searchPosts(id, checkpoint.lowerWatermark, checkpoint.beforeId)
            val ids = page.map { it.id }
            if (ids.zipWithNext().any { (a, b) -> a <= b } || (checkpoint.beforeId != null && ids.any { it >= checkpoint.beforeId!! }))
                throw SourceFailure(FailureKind.BAD_RESPONSE, "API 分页未前进，本次扫描未标记完成。")
            val fresh = page.filter { it.id > checkpoint.lowerWatermark }
            val next = checkpoint.copy(beforeId = ids.minOrNull() ?: checkpoint.beforeId,
                maxSeenId = maxOf(checkpoint.maxSeenId, fresh.maxOfOrNull { it.id } ?: checkpoint.maxSeenId))
            // Metadata, queue entries and the page checkpoint are committed together.
            repository.savePage(id, runId, fresh, next)
            if (page.isEmpty() || page.size < catalog.pageSize || ids.any { it <= checkpoint.lowerWatermark }) {
                checkActive(id)
                repository.commitScan(id, runId, next.maxSeenId)
                return
            }
            checkpoint = next
        }
    }

    private suspend fun repairLegacyMetadata(id: String, runId: String) {
        for (record in repository.records(id)) {
            checkActive(id)
            if (FileChecksum.expectedForReuse(record.md5, record.verifiedMd5, record.fileUrl) != null) continue
            try { repository.repairMetadata(id, runId, catalog.getPost(record.postId)) }
            catch (e: SourceFailure) {
                if (e.kind != FailureKind.NOT_FOUND) throw e
                repository.markFailed(id, runId, record.postId, e.message)
            }
        }
    }

    private suspend fun transferWithProgress(id: String, runId: String, record: DownloadRecord): SavedFile = coroutineScope {
        val updates = Channel<Pair<Long, Long>>(Channel.CONFLATED)
        val reporter = launch {
            for ((bytes, total) in updates) repository.progress(id, runId, record.postId, bytes, total)
        }
        try {
            var lastAt = 0L
            downloader.download(record, { total -> updates.trySend(0L to total) }, { bytes, total ->
                val at = now()
                if (at - lastAt >= 500 || bytes == total) { lastAt = at; updates.trySend(bytes to total) }
            }, { saved -> withContext(NonCancellable) { repository.markDownloaded(id, runId, record.postId, saved) } })
        } finally {
            updates.close()
            withContext(NonCancellable) { reporter.join() }
        }
    }

    private suspend fun checkActive(id: String) {
        currentCoroutineContext().ensureActive()
        if (repository.getCollection(id)?.paused != false) throw CancellationException("Task paused or removed")
    }
    private suspend fun retry(id: String, runId: String, attempts: Int, message: String, serverDelay: Long?, state: SyncState = SyncState.WAITING_RETRY): SyncOutcome.Retry {
        val backoff = (30_000L * (1L shl attempts.coerceIn(0, 6))).coerceAtMost(1_800_000)
        val delay = maxOf(backoff, serverDelay ?: 0)
        repository.setState(id, runId, state, message, now() + delay)
        return SyncOutcome.Retry(delay)
    }
}
