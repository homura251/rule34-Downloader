package com.homura251.rule34downloader.download

import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.domain.files.FileChecksum
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID

sealed interface SyncOutcome {
    data class Complete(val downloaded: Int, val failed: Int) : SyncOutcome
    data class Retry(val delayMillis: Long, val mode: SyncMode = SyncMode.FULL) : SyncOutcome
    data object NeedsAuthentication : SyncOutcome
    data object Blocked : SyncOutcome
    data object Skipped : SyncOutcome
}

fun interface SyncObserver { fun progress(completed: Int, total: Int, currentPostId: Long?) }

/** Discovery owns the scan checkpoint; verified file transfers can finish independently. */
class SyncEngine(
    private val repository: SyncRepository,
    private val catalog: CatalogGateway,
    private val downloader: MediaDownloader,
    private val reuse: FileReuse,
    private val observer: SyncObserver = SyncObserver { _, _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(id: String, runId: String = UUID.randomUUID().toString(), mode: SyncMode = SyncMode.FULL): SyncOutcome {
        val collection = repository.getCollection(id) ?: return SyncOutcome.Skipped
        if (collection.paused || collection.poolId != null || !repository.beginRun(id, runId)) return SyncOutcome.Skipped
        var scanProblem: SourceFailure? = null
        return try {
            downloader.cleanInterruptedFiles(id)
            repository.resetInterrupted(id, runId)
            if (mode == SyncMode.FULL) {
                try { discover(id, runId, collection.lastSeenPostId) }
                catch (e: SourceFailure) {
                    if (e.kind == FailureKind.AUTH) throw e
                    scanProblem = e
                }
            }
            val scanNotice = scanProblem?.let { "作品列表尚未扫描完成，正在处理已发现文件。${it.message}" }
            repository.setState(id, runId, SyncState.VERIFYING, scanNotice)
            for (record in repository.records(id)) {
                checkActive(id)
                if (record.localUri == null && record.status != DownloadStatus.DOWNLOADED) continue
                val file = reuse.lookup(record).file
                if (file != null) repository.markDownloaded(id, runId, record.postId, file)
                else repository.invalidateFile(id, runId, record.postId)
            }
            repository.setState(id, runId, SyncState.SYNCING, scanNotice)
            var downloaded = 0
            var blockedInARow = 0
            var metadataProblem: SourceFailure? = null
            val initial = repository.records(id)
            val total = initial.size
            var completed = initial.count { it.status == DownloadStatus.DOWNLOADED }
            for (queued in repository.queue(id)) {
                checkActive(id)
                var record = queued
                val existing = reuse.lookup(record)
                val existingFile = existing.file
                if (existingFile != null) {
                    repository.markDownloaded(id, runId, record.postId, existingFile)
                    completed++
                    blockedInARow = 0
                    observer.progress(completed, total, null)
                    continue
                }
                if (mode == SyncMode.FULL && (record.status == DownloadStatus.FAILED || !usable(record))) {
                    try {
                        val post = catalog.getPost(record.postId)
                        repository.repairMetadata(id, runId, post)
                        record = record.copy(fileUrl = post.fileUrl, md5 = post.md5)
                    } catch (e: SourceFailure) {
                        if (e.kind == FailureKind.AUTH) throw e
                        if (e.kind == FailureKind.NOT_FOUND) {
                            repository.markFailed(id, runId, record.postId, e.message)
                            continue
                        }
                        // Existing trustworthy metadata remains usable during an API outage.
                        if (!usable(record)) {
                            metadataProblem = metadataProblem ?: e
                            continue
                        }
                    }
                }
                if (!usable(record)) {
                    repository.markFailed(id, runId, record.postId,
                        "帖子 #${record.postId} 缺少有效的原文件地址或校验信息，请使用“重试扫描”补全；其他文件会继续下载。")
                    continue
                }
                repository.markDownloading(id, runId, record.postId, 0)
                observer.progress(completed, total, record.postId)
                try {
                    transferWithProgress(id, runId, record)
                    downloaded++
                    completed++
                    blockedInARow = 0
                    observer.progress(completed, total, null)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    checkActive(id)
                    if (e is SourceFailure && (e.retryable || e.kind == FailureKind.AUTH)) throw e
                    repository.markFailed(id, runId, record.postId,
                        listOfNotNull(existing.rejection, e.userMessage()).joinToString("；"))
                    if (e is SourceFailure && e.kind == FailureKind.BLOCKED) {
                        blockedInARow++
                        if (blockedInARow >= 3) throw SourceFailure(FailureKind.BLOCKED,
                            "原文件服务器连续拒绝了 3 个文件，已保留进度。可稍后重试失败文件或重新扫描获取最新文件信息。")
                    } else blockedInARow = 0
                }
            }
            checkActive(id)
            val scanFailure = scanProblem
            val informationFailure = metadataProblem
            when {
                scanFailure != null -> sourceFailure(id, runId, collection, scanFailure, mode, scanIncomplete = true,
                    prefix = "作品列表尚未扫描完成；已发现文件已处理。")
                informationFailure != null -> sourceFailure(id, runId, collection, informationFailure, mode,
                    prefix = "部分文件信息暂不可用，其他已发现文件已处理。")
                else -> {
                    val failed = repository.records(id).count { it.status == DownloadStatus.FAILED }
                    if (failed > 0 && repository.queue(id).isNotEmpty()) {
                        retry(id, runId, collection.retryAttempts, "部分文件下载失败，稍后重试；完整文件会保留。", null, mode)
                    } else {
                        val state = when {
                            failed > 0 -> SyncState.ERROR
                            mode == SyncMode.KNOWN_FILES -> SyncState.QUEUE_COMPLETE
                            else -> SyncState.COMPLETE
                        }
                        repository.setState(id, runId, state,
                            if (failed > 0) "$failed 个文件需要处理。可重试失败文件、下载已发现文件，或从头扫描；已有完整文件会保留。" else null)
                        SyncOutcome.Complete(downloaded, failed)
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { repository.setState(id, runId, SyncState.IDLE) }
            throw e
        } catch (e: SourceFailure) {
            val delay = maxOf(e.retryAfterMillis ?: 0, scanProblem?.retryAfterMillis ?: 0)
            val failure = if (delay > 0) SourceFailure(e.kind, e.message, delay) else e
            sourceFailure(id, runId, collection, failure, mode, scanIncomplete = scanProblem != null)
        } catch (e: Exception) {
            repository.setState(id, runId, SyncState.ERROR, e.userMessage())
            SyncOutcome.Blocked
        } finally {
            withContext(NonCancellable) { repository.endRun(id, runId) }
        }
    }

    private fun checksum(record: DownloadRecord) = FileChecksum.expectedForReuse(record.md5, record.verifiedMd5, record.fileUrl)
    private fun usable(record: DownloadRecord) = record.fileUrl.isNotBlank() && checksum(record) != null

    private suspend fun sourceFailure(id: String, runId: String, collection: CollectionRecord, failure: SourceFailure,
        mode: SyncMode, scanIncomplete: Boolean = false, prefix: String = ""): SyncOutcome {
        val message = prefix + failure.message
        return when {
            failure.kind == FailureKind.AUTH -> {
                repository.setState(id, runId, SyncState.WAITING_AUTH, message)
                SyncOutcome.NeedsAuthentication
            }
            failure.retryable || (failure.kind == FailureKind.BAD_RESPONSE && collection.retryAttempts < 2) ->
                retry(id, runId, collection.retryAttempts, message, failure.retryAfterMillis, mode,
                    if (failure.kind == FailureKind.NETWORK) SyncState.WAITING_NETWORK else SyncState.WAITING_RETRY)
            else -> {
                repository.setState(id, runId, if (scanIncomplete) SyncState.SCAN_INCOMPLETE else SyncState.BLOCKED,
                    "$message 可重试扫描、先下载已发现文件，或从头扫描；已下载文件不会因此被判为损坏。")
                SyncOutcome.Blocked
            }
        }
    }

    private suspend fun discover(id: String, runId: String, watermark: Long) {
        var checkpoint = repository.checkpoint(id)?.takeIf { it.lowerWatermark == watermark } ?: ScanCheckpoint(watermark)
        while (true) {
            checkActive(id)
            val page = catalog.searchPosts(id, checkpoint.lowerWatermark, checkpoint.beforeId)
            val ids = page.map { it.id }
            if (ids.zipWithNext().any { (a, b) -> a <= b } || (checkpoint.beforeId != null && ids.any { it >= checkpoint.beforeId!! }))
                throw SourceFailure(FailureKind.BAD_RESPONSE, "作品列表分页重复或顺序异常，已保留扫描进度。")
            val fresh = page.filter { it.id > checkpoint.lowerWatermark }
            val next = checkpoint.copy(beforeId = ids.minOrNull() ?: checkpoint.beforeId,
                maxSeenId = maxOf(checkpoint.maxSeenId, fresh.maxOfOrNull { it.id } ?: checkpoint.maxSeenId))
            repository.savePage(id, runId, fresh, next)
            if (page.isEmpty() || page.size < catalog.pageSize || ids.any { it <= checkpoint.lowerWatermark }) {
                checkActive(id)
                repository.commitScan(id, runId, next.maxSeenId)
                return
            }
            checkpoint = next
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
    private suspend fun retry(id: String, runId: String, attempts: Int, message: String, serverDelay: Long?, mode: SyncMode,
        state: SyncState = SyncState.WAITING_RETRY): SyncOutcome.Retry {
        val backoff = (30_000L * (1L shl attempts.coerceIn(0, 6))).coerceAtMost(1_800_000)
        val delay = maxOf(backoff, serverDelay ?: 0)
        repository.setState(id, runId, state, message, now() + delay)
        return SyncOutcome.Retry(delay, mode)
    }
}
