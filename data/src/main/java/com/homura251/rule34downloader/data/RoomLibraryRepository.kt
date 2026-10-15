package com.homura251.rule34downloader.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.homura251.rule34downloader.data.room.*
import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.domain.files.FileChecksum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLibraryRepository(private val database: LibraryDatabase) : SyncRepository {
    private val dao = database.libraryDao()
    override val changes: Flow<Unit> = database.invalidationTracker.createFlow(
        "collections", "posts", "collection_posts", "download_tasks", "local_files").map { Unit }

    override fun observeCollections() = dao.summaries(SimpleSQLiteQuery("""
        SELECT c.id, c.source_post_id, c.last_seen_post_id, c.state, c.current_post_id, c.last_error,
            c.last_sync_at, c.created_at, c.pool_id, c.display_name, c.next_retry_at, c.sync_mode,
            COALESCE(SUM(t.status = 'DOWNLOADED'), 0) AS downloaded_count,
            COALESCE(SUM(t.status IN ('PENDING', 'DOWNLOADING')), 0) AS pending_count,
            COALESCE(SUM(t.status = 'FAILED'), 0) AS failed_count,
            COALESCE(MAX(CASE WHEN t.status = 'DOWNLOADING' THEN t.bytes_downloaded ELSE 0 END), 0) AS current_bytes,
            COALESCE(MAX(CASE WHEN t.status = 'DOWNLOADING' THEN t.total_bytes ELSE 0 END), 0) AS current_total,
            (SELECT COALESCE(f.uri, p.preview_url) FROM collection_posts m JOIN posts p ON p.id = m.post_id
                LEFT JOIN local_files f ON f.collection_id = m.collection_id AND f.post_id = m.post_id
                WHERE m.collection_id = c.id ORDER BY m.post_id DESC LIMIT 1) AS cover_url
        FROM collections c LEFT JOIN download_tasks t ON t.collection_id = c.id
        LEFT JOIN collection_posts m ON m.collection_id = t.collection_id AND m.post_id = t.post_id
        WHERE c.pool_id IS NULL OR t.post_id IS NULL OR m.position IS NOT NULL
        GROUP BY c.id ORDER BY c.created_at DESC
    """.trimIndent())).map { rows -> rows.map { row ->
        CollectionSummary(row.id, row.source_post_id, row.last_seen_post_id, state(row.state), row.current_post_id,
            row.last_error, row.last_sync_at, row.created_at, row.downloaded_count, row.pending_count,
            row.failed_count, row.current_bytes, row.current_total, row.pool_id, row.display_name,
            row.cover_url, row.next_retry_at, mode(row.sync_mode))
    } }

    override suspend fun getCollection(id: String) = dao.collection(id)?.domain()
    override suspend fun getCollections() = dao.collections().map { it.domain() }
    override suspend fun addCollection(tag: String, sourcePostId: Long) =
        dao.insertCollection(CollectionEntity(tag, sourcePostId, createdAt = System.currentTimeMillis())) != -1L
    override suspend fun removeCollection(id: String) = dao.removeCollection(id)

    override suspend fun gallery(query: GalleryQuery, offset: Int, limit: Int): GalleryPage {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        query.collectionId?.let { where += "m.collection_id = ?"; args.add(it) }
        where += "(c.pool_id IS NULL OR m.position IS NOT NULL)"
        val path = "LOWER(SUBSTR(p.file_url, 1, INSTR(p.file_url || '?', '?') - 1))"
        val video = listOf("mp4", "webm", "m4v", "mov").joinToString(" OR ") { "$path GLOB '*.$it'" }
        when (query.filter) {
            GalleryFilter.DOWNLOADED -> where += "t.status = 'DOWNLOADED' AND f.uri IS NOT NULL"
            GalleryFilter.FAILED -> where += "t.status = 'FAILED'"
            GalleryFilter.IMAGES -> where += "NOT ($video)"
            GalleryFilter.VIDEOS -> where += "($video)"
            GalleryFilter.ALL -> Unit
        }
        val from = """FROM collection_posts m JOIN collections c ON c.id = m.collection_id
            JOIN posts p ON p.id = m.post_id JOIN download_tasks t ON t.collection_id = m.collection_id AND t.post_id = m.post_id
            LEFT JOIN local_files f ON f.collection_id = m.collection_id AND f.post_id = m.post_id
            WHERE ${where.joinToString(" AND ")}"""
        val collection = query.collectionId?.let { dao.collection(it) }
        val global = query.collectionId == null
        val count = dao.count(SimpleSQLiteQuery("SELECT COUNT(${if (global) "DISTINCT p.id" else "*"}) $from", args.toTypedArray()))
        val group = if (global) "GROUP BY p.id" else ""
        // Prefer the downloaded local copy when a post belongs to multiple collections.
        val local = if (global) "MAX(f.uri)" else "f.uri"
        val id = if (global) "COALESCE(MIN(CASE WHEN f.uri IS NOT NULL THEN m.collection_id END), MIN(m.collection_id))" else "m.collection_id"
        val status = if (global) "CASE WHEN MAX(t.status = 'DOWNLOADED') = 1 THEN 'DOWNLOADED' ELSE MAX(t.status) END" else "t.status"
        val error = if (global) "CASE WHEN MAX(t.status = 'DOWNLOADED') = 1 THEN NULL ELSE MAX(t.error) END" else "t.error"
        val order = if (collection?.poolId != null) "m.position ASC" else "p.id DESC"
        val rows = dao.gallery(SimpleSQLiteQuery("""SELECT $id AS collection_id, p.id AS post_id, p.file_url,
            COALESCE(m.preview_url, p.preview_url) AS preview_url, $local AS local_uri, $status AS status,
            $error AS error $from $group ORDER BY $order LIMIT ? OFFSET ?""", (args + limit.coerceIn(1, 200) + offset.coerceAtLeast(0)).toTypedArray()))
        return GalleryPage(rows.map { GalleryPost(it.collection_id, it.post_id, it.file_url, it.preview_url,
            it.local_uri, DownloadStatus.valueOf(it.status), it.error) }, count,
            collection?.displayName ?: query.collectionId, collection?.poolId)
    }

    override suspend fun beginRun(id: String, runId: String) = dao.beginRun(id, runId) > 0
    override suspend fun setState(id: String, runId: String, state: SyncState, error: String?, nextRetryAt: Long?) =
        dao.state(id, runId, state.name, error?.take(500), nextRetryAt, System.currentTimeMillis())
    override suspend fun requestPause(id: String) = dao.pause(id)
    override suspend fun finishPause(id: String) = dao.finishPause(id)
    override suspend fun resume(id: String) = dao.resume(id) > 0
    override suspend fun suspendForAuthentication() = database.withTransaction { dao.recoverApiTasks(); dao.suspendForAuthentication() }
    override suspend fun setSyncMode(id: String, mode: SyncMode) = dao.setSyncMode(id, mode.name)
    override suspend fun endRun(id: String, runId: String) = guarded(id, runId) { dao.resetInterrupted(id); dao.endRun(id, runId) }
    override suspend fun resetInterrupted(id: String, runId: String) = guarded(id, runId) { dao.resetInterrupted(id) }
    override suspend fun checkpoint(id: String) = dao.checkpoint(id)?.let { ScanCheckpoint(it.lowerWatermark, it.beforeId, it.maxSeenId) }

    override suspend fun savePage(id: String, runId: String, posts: List<Rule34Post>, checkpoint: ScanCheckpoint) = guarded(id, runId) {
        posts.forEach {
            savePost(it)
            dao.membership(MembershipEntity(id, it.id))
            dao.task(TaskEntity(id, it.id, status = if (it.availabilityError == null) "PENDING" else "FAILED",
                error = it.availabilityError, updatedAt = System.currentTimeMillis()))
        }
        dao.checkpoint(CheckpointEntity(id, checkpoint.lowerWatermark, checkpoint.beforeId, checkpoint.maxSeenId))
    }
    override suspend fun commitScan(id: String, runId: String, watermark: Long) = guarded(id, runId) {
        dao.commitWatermark(id, runId, watermark); dao.clearCheckpoint(id)
    }
    override suspend fun restartScan(id: String): Boolean = database.withTransaction {
        if (dao.restartScan(id) == 0) false else {
            dao.clearCheckpoint(id)
            dao.resetFailures(id)
            true
        }
    }
    override suspend fun repairMetadata(id: String, runId: String, post: Rule34Post) = guarded(id, runId) { savePost(post) }
    private suspend fun savePost(post: Rule34Post) {
        val old = dao.post(post.id)
        dao.post(PostEntity(post.id, post.fileUrl.ifBlank { old?.fileUrl.orEmpty() }, post.previewUrl ?: old?.previewUrl,
            FileChecksum.normalize(post.md5) ?: old?.md5.orEmpty(), post.width ?: old?.width,
            post.height ?: old?.height, post.tags.joinToString(" ").ifBlank { old?.tags.orEmpty() }))
    }

    override suspend fun records(id: String): List<DownloadRecord> = dao.records(SimpleSQLiteQuery("""
        SELECT t.collection_id, t.post_id, p.file_url, p.md5, t.status, t.bytes_downloaded, t.total_bytes,
            f.uri AS local_uri, f.verified_md5, t.error, t.attempts
        FROM download_tasks t JOIN posts p ON p.id = t.post_id
        JOIN collection_posts m ON m.collection_id = t.collection_id AND m.post_id = t.post_id
        JOIN collections c ON c.id = t.collection_id
        LEFT JOIN local_files f ON f.collection_id = t.collection_id AND f.post_id = t.post_id
        WHERE t.collection_id = ? AND (c.pool_id IS NULL OR m.position IS NOT NULL)
        ORDER BY CASE WHEN c.pool_id IS NOT NULL THEN m.position ELSE t.post_id END
    """.trimIndent(), arrayOf(id))).map { DownloadRecord(it.collection_id, it.post_id, it.file_url, it.md5,
        DownloadStatus.valueOf(it.status), it.bytes_downloaded, it.total_bytes, it.local_uri, it.verified_md5, it.error, it.attempts) }
    override suspend fun queue(id: String) = records(id).filter { it.status != DownloadStatus.DOWNLOADED && it.attempts < 3 }
    override suspend fun markDownloading(id: String, runId: String, postId: Long, total: Long) = guarded(id, runId) {
        dao.markDownloading(id, postId, total.coerceAtLeast(0), System.currentTimeMillis()); dao.currentPost(id, runId, postId)
    }
    override suspend fun progress(id: String, runId: String, postId: Long, bytes: Long, total: Long) = guarded(id, runId) {
        dao.progress(id, postId, bytes.coerceAtLeast(0), total, System.currentTimeMillis())
    }
    override suspend fun markDownloaded(id: String, runId: String, postId: Long, file: SavedFile) = guarded(id, runId) {
        require(file.bytes > 0 && FileChecksum.normalize(file.verifiedMd5) != null)
        dao.file(LocalFileEntity(id, postId, file.uri, file.bytes, file.verifiedMd5)); dao.downloaded(id, postId, file.bytes, System.currentTimeMillis())
    }
    override suspend fun invalidateFile(id: String, runId: String, postId: Long) = guarded(id, runId) {
        val post = dao.post(postId)
        val verified = dao.localFile(id, postId)?.verifiedMd5?.let(FileChecksum::normalize)
        if (post != null && FileChecksum.normalize(post.md5) == null && verified != null)
            dao.post(post.copy(md5 = verified))
        dao.removeFile(id, postId)
        dao.invalidate(id, postId)
    }
    override suspend fun markFailed(id: String, runId: String, postId: Long, error: String) = guarded(id, runId) { dao.failed(id, postId, error.take(500), System.currentTimeMillis()) }
    override suspend fun resetFailures(id: String) = dao.resetFailures(id)
    override suspend fun markQueued(id: String) = dao.markQueued(id)
    override suspend fun recoverInterruptedRuns() = database.withTransaction { dao.recoverTasks(); dao.recoverCollections() }

    private suspend fun guarded(id: String, runId: String, action: suspend () -> Unit) {
        database.withTransaction { if (dao.ownsRun(id, runId) > 0) action() }
    }
    private fun CollectionEntity.domain() = CollectionRecord(id, sourcePostId, lastSeenPostId, userPaused, poolId, displayName, state(state), retryAttempts, mode(syncMode))
    private fun mode(value: String) = SyncMode.entries.firstOrNull { it.name == value } ?: SyncMode.FULL
    private fun state(value: String) = runCatching { SyncState.valueOf(value) }.getOrDefault(SyncState.IDLE)
    companion object {
        fun create(context: android.content.Context) = RoomLibraryRepository(LibraryDatabase.open(context))
    }
}
