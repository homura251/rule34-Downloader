package com.homura251.rule34downloader.download

import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.domain.files.FileIntegrityException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SyncEngineTest {
    private val repo = MemoryRepository()
    private val searched = mutableListOf<Long?>()
    private var search: suspend (Long?) -> List<Rule34Post> = { emptyList() }
    private val catalog = object : CatalogGateway {
        override val pageSize = 2
        override suspend fun validate(credentials: ApiCredentials) = Unit
        override suspend fun getPost(postId: Long) = post(postId)
        override suspend fun resolveArtistTags(post: Rule34Post) = emptyList<Rule34Tag>()
        override suspend fun searchPosts(tag: String, afterPostId: Long, beforePostId: Long?): List<Rule34Post> {
            searched += beforePostId; return search(beforePostId)
        }
    }
    private val transferred = mutableListOf<Long>()
    private var transfer: suspend (DownloadRecord, suspend (SavedFile) -> Unit) -> SavedFile = { record, publish ->
        saved(record.postId).also { publish(it) }
    }
    private val downloader = object : MediaDownloader {
        override suspend fun cleanInterruptedFiles(collectionId: String) = Unit
        override suspend fun download(record: DownloadRecord, onHeaders: (Long) -> Unit, onProgress: (Long, Long) -> Unit,
            onPublished: suspend (SavedFile) -> Unit): SavedFile {
            transferred += record.postId; onHeaders(10); onProgress(5, 10)
            return transfer(record, onPublished)
        }
    }
    private var reuseFile: SavedFile? = null
    private val reuse = object : FileReuse { override suspend fun lookup(record: DownloadRecord) = ReuseResult(reuseFile) }
    private fun engine() = SyncEngine(repo, catalog, downloader, reuse, now = { 10_000 })

    @Test fun failedPageRetainsCheckpointWithoutAdvancingWatermark() = runTest {
        search = { before -> if (before == null) listOf(post(5), post(4)) else throw SourceFailure(FailureKind.NETWORK, "offline") }
        assertTrue(engine().run("artist") is SyncOutcome.Retry)
        assertEquals(0, repo.collection.lastSeenPostId)
        assertEquals(ScanCheckpoint(0, 4, 5), repo.scan)
        assertEquals(2, repo.rows.size)
        assertTrue(transferred.isEmpty())
        searched.clear()
        search = { listOf(post(3)) }
        assertEquals(SyncOutcome.Complete(3, 0), engine().run("artist"))
        assertEquals(listOf(4L), searched)
        assertEquals(5, repo.collection.lastSeenPostId)
        assertNull(repo.scan)
        assertEquals(setOf(3L, 4L, 5L), transferred.toSet())
    }

    @Test fun ignoredPaginationCannotMarkScanComplete() = runTest {
        search = { listOf(post(5), post(4)) }
        assertEquals(SyncOutcome.Blocked, engine().run("artist"))
        assertEquals(0, repo.collection.lastSeenPostId)
        assertEquals(SyncState.BLOCKED, repo.collection.syncState)
        assertTrue(transferred.isEmpty())
    }
    @Test fun unorderedPageCannotAdvanceWatermarkOrSkipHiddenPosts() = runTest {
        search = { listOf(post(4), post(5)) }
        assertEquals(SyncOutcome.Blocked, engine().run("artist"))
        assertEquals(0L, repo.collection.lastSeenPostId)
        assertTrue(repo.rows.isEmpty())
    }

    @Test fun transferFailureDoesNotRollBackDiscoveredMetadataAndStopsAfterThreeAttempts() = runTest {
        search = { before -> if (before == null && repo.collection.lastSeenPostId == 0L) listOf(post(1)) else emptyList() }
        transfer = { _, _ -> throw FileIntegrityException("MD5 校验失败") }
        repeat(2) { assertTrue(engine().run("artist") is SyncOutcome.Retry) }
        assertEquals(SyncOutcome.Complete(0, 1), engine().run("artist"))
        assertEquals(1, repo.collection.lastSeenPostId)
        assertNull(repo.scan)
        assertEquals(3, repo.rows.getValue(1).attempts)
        assertEquals(SyncState.ERROR, repo.collection.syncState)
        engine().run("artist")
        assertEquals(3, transferred.size)
        assertEquals("MD5 校验失败", repo.rows.getValue(1).error)
        repo.resetFailures("artist")
        transfer = { r, publish -> saved(r.postId).also { publish(it) } }
        assertEquals(SyncOutcome.Complete(1, 0), engine().run("artist"))
    }

    @Test fun verifiedExistingUriIsRetainedWithoutHttpTransfer() = runTest {
        repo.seed(post(1), DownloadStatus.DOWNLOADED, "content://old/1")
        reuseFile = SavedFile("content://old/1", 10, HASH)
        assertEquals(SyncOutcome.Complete(0, 0), engine().run("artist"))
        assertTrue(transferred.isEmpty())
        assertEquals("content://old/1", repo.rows.getValue(1).localUri)
    }

    @Test fun cancellationResetsPartialFileButKeepsUserPausedIntent() = runTest {
        repo.seed(post(1))
        val started = CompletableDeferred<Unit>()
        transfer = { _, _ -> started.complete(Unit); awaitCancellation() }
        val job = launch { engine().run("artist") }
        started.await(); repo.requestPause("artist"); job.cancelAndJoin()
        assertEquals(SyncState.PAUSED, repo.collection.syncState)
        assertTrue(repo.collection.paused)
        assertEquals(DownloadStatus.PENDING, repo.rows.getValue(1).status)
        assertNull(repo.rows.getValue(1).localUri)
        assertNull(repo.lease)
    }

    @Test fun pauseAtPublicationBoundaryStillPersistsVerifiedUri() = runTest {
        repo.seed(post(1))
        transfer = { record, publish ->
            repo.requestPause("artist")
            publish(saved(record.postId))
            throw CancellationException("pause after publication")
        }
        try { engine().run("artist"); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(SyncState.PAUSED, repo.collection.syncState)
        assertEquals(DownloadStatus.DOWNLOADED, repo.rows.getValue(1).status)
        assertEquals("content://new/1", repo.rows.getValue(1).localUri)
        assertEquals(HASH, repo.rows.getValue(1).verifiedMd5)
    }

    @Test fun serverRetryAfterWinsOverExponentialBackoff() = runTest {
        repo.collection = repo.collection.copy(retryAttempts = 2)
        search = { throw SourceFailure(FailureKind.RATE_LIMIT, "slow down", 240_000) }
        assertEquals(SyncOutcome.Retry(240_000), engine().run("artist"))
        assertEquals(250_000L, repo.nextRetry)
        assertEquals(0, repo.collection.lastSeenPostId)
    }

    @Test fun authenticationFailureLeavesQueueAndFilesIntact() = runTest {
        repo.seed(post(1), DownloadStatus.DOWNLOADED, "content://old/1")
        search = { throw SourceFailure(FailureKind.AUTH, "expired") }
        assertEquals(SyncOutcome.NeedsAuthentication, engine().run("artist"))
        assertEquals(SyncState.WAITING_AUTH, repo.collection.syncState)
        assertEquals("content://old/1", repo.rows.getValue(1).localUri)
        assertTrue(transferred.isEmpty())
    }
    @Test fun mediaNetworkFailureDoesNotConsumePermanentFileAttempts() = runTest {
        repo.seed(post(1))
        transfer = { _, _ -> throw SourceFailure(FailureKind.NETWORK, "transfer disconnected") }
        assertEquals(SyncOutcome.Retry(30_000), engine().run("artist"))
        assertEquals(SyncState.WAITING_NETWORK, repo.collection.syncState)
        assertEquals(0, repo.rows.getValue(1).attempts)
        assertEquals(DownloadStatus.PENDING, repo.rows.getValue(1).status)
        assertNull(repo.rows.getValue(1).localUri)
    }
    @Test fun pausedAndArchivedCollectionsNeverUseCatalog() = runTest {
        repo.collection = repo.collection.copy(paused = true)
        assertEquals(SyncOutcome.Skipped, engine().run("artist"))
        repo.collection = repo.collection.copy(paused = false, poolId = 7)
        assertEquals(SyncOutcome.Skipped, engine().run("artist"))
        assertTrue(searched.isEmpty())
    }
    companion object {
        const val HASH = "0123456789abcdef0123456789abcdef"
        fun post(id: Long) = Rule34Post(id, "https://wimg.rule34.xxx/$HASH.png", HASH, listOf("artist"))
        fun saved(id: Long) = SavedFile("content://new/$id", 10, HASH)
    }
}

/** Behavioural fake; Room transactions and stale lease rejection are tested against SQLite separately. */
private class MemoryRepository : SyncRepository {
    var collection = CollectionRecord("artist", 0, 0)
    var lease: String? = null
    var scan: ScanCheckpoint? = null
    var nextRetry: Long? = null
    val rows = linkedMapOf<Long, DownloadRecord>()
    fun seed(p: Rule34Post, status: DownloadStatus = DownloadStatus.PENDING, uri: String? = null) {
        rows[p.id] = DownloadRecord("artist", p.id, p.fileUrl, p.md5, status, 0, 0, uri)
    }
    override val changes = flowOf(Unit)
    override fun observeCollections() = flowOf(emptyList<CollectionSummary>())
    override suspend fun getCollection(id: String) = collection
    override suspend fun getCollections() = listOf(collection)
    override suspend fun addCollection(tag: String, sourcePostId: Long) = false
    override suspend fun removeCollection(id: String) = Unit
    override suspend fun gallery(query: GalleryQuery, offset: Int, limit: Int) = GalleryPage(emptyList(), 0)
    override suspend fun beginRun(id: String, runId: String): Boolean { lease = runId; return true }
    override suspend fun setState(id: String, runId: String, state: SyncState, error: String?, nextRetryAt: Long?) {
        if (lease == runId && !collection.paused) {
            collection = collection.copy(syncState = state, retryAttempts = if (state == SyncState.WAITING_RETRY) collection.retryAttempts + 1 else collection.retryAttempts)
            nextRetry = nextRetryAt
        }
    }
    override suspend fun endRun(id: String, runId: String) {
        if (lease != runId) return
        resetInterrupted(id, runId); lease = null
        if (collection.paused) collection = collection.copy(syncState = SyncState.PAUSED)
    }
    override suspend fun requestPause(id: String) { collection = collection.copy(paused = true, syncState = SyncState.PAUSING) }
    override suspend fun finishPause(id: String) = Unit
    override suspend fun resume(id: String): Boolean { collection = collection.copy(paused = false); return true }
    override suspend fun suspendForAuthentication() = Unit
    override suspend fun resetInterrupted(id: String, runId: String) {
        rows.replaceAll { _, r -> if (r.status == DownloadStatus.DOWNLOADING) r.copy(status = DownloadStatus.PENDING, bytesDownloaded = 0) else r }
    }
    override suspend fun checkpoint(id: String) = scan
    override suspend fun savePage(id: String, runId: String, posts: List<Rule34Post>, checkpoint: ScanCheckpoint) {
        posts.filterNot { rows.containsKey(it.id) }.forEach { seed(it) }; scan = checkpoint
    }
    override suspend fun commitScan(id: String, runId: String, watermark: Long) { collection = collection.copy(lastSeenPostId = watermark); scan = null }
    override suspend fun repairMetadata(id: String, runId: String, post: Rule34Post) { rows[post.id] = rows.getValue(post.id).copy(md5 = post.md5) }
    override suspend fun records(id: String) = rows.values.sortedBy { it.postId }
    override suspend fun queue(id: String) = records(id).filter { it.status != DownloadStatus.DOWNLOADED && it.attempts < 3 }
    override suspend fun markDownloading(id: String, runId: String, postId: Long, total: Long) { rows[postId] = rows.getValue(postId).copy(status = DownloadStatus.DOWNLOADING) }
    override suspend fun progress(id: String, runId: String, postId: Long, bytes: Long, total: Long) {
        val r = rows.getValue(postId)
        if (r.status == DownloadStatus.DOWNLOADING) rows[postId] = r.copy(bytesDownloaded = bytes, totalBytes = total)
    }
    override suspend fun markDownloaded(id: String, runId: String, postId: Long, file: SavedFile) {
        rows[postId] = rows.getValue(postId).copy(status = DownloadStatus.DOWNLOADED, localUri = file.uri, bytesDownloaded = file.bytes, verifiedMd5 = file.verifiedMd5, attempts = 0)
    }
    override suspend fun invalidateFile(id: String, runId: String, postId: Long) { rows[postId] = rows.getValue(postId).copy(status = DownloadStatus.PENDING, localUri = null) }
    override suspend fun markFailed(id: String, runId: String, postId: Long, error: String) {
        val r = rows.getValue(postId); rows[postId] = r.copy(status = DownloadStatus.FAILED, error = error, attempts = r.attempts + 1)
    }
    override suspend fun resetFailures(id: String) { rows.replaceAll { _, r -> r.copy(attempts = 0) } }
    override suspend fun markQueued(id: String) = Unit
    override suspend fun recoverInterruptedRuns() = Unit
}
