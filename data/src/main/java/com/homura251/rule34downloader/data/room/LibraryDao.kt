package com.homura251.rule34downloader.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM collections WHERE id = :id") suspend fun collection(id: String): CollectionEntity?
    @Query("SELECT * FROM collections ORDER BY created_at") suspend fun collections(): List<CollectionEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCollection(entity: CollectionEntity): Long
    @Query("DELETE FROM collections WHERE id = :id") suspend fun removeCollection(id: String)
    @Query("SELECT COUNT(*) FROM collections WHERE id = :id AND run_id = :runId") suspend fun ownsRun(id: String, runId: String): Int
    @Query("UPDATE collections SET run_id = :runId, state = 'DISCOVERING', next_retry_at = NULL, last_error = NULL WHERE id = :id AND user_paused = 0 AND pool_id IS NULL AND run_id IS NULL")
    suspend fun beginRun(id: String, runId: String): Int
    @Query("UPDATE collections SET state = :state, last_error = :error, next_retry_at = :nextRetryAt, retry_attempts = CASE WHEN :state IN ('WAITING_RETRY', 'WAITING_NETWORK') THEN retry_attempts + 1 WHEN :state = 'COMPLETE' THEN 0 ELSE retry_attempts END, last_sync_at = CASE WHEN :state IN ('COMPLETE', 'ERROR') THEN :now ELSE last_sync_at END WHERE id = :id AND run_id = :runId AND user_paused = 0")
    suspend fun state(id: String, runId: String, state: String, error: String?, nextRetryAt: Long?, now: Long)
    @Query("UPDATE collections SET run_id = NULL, current_post_id = NULL, state = CASE WHEN user_paused = 1 THEN 'PAUSED' ELSE state END WHERE id = :id AND run_id = :runId")
    suspend fun endRun(id: String, runId: String)
    @Query("UPDATE collections SET user_paused = 1, state = 'PAUSING', next_retry_at = NULL WHERE id = :id AND pool_id IS NULL") suspend fun pause(id: String)
    @Query("UPDATE collections SET state = 'PAUSED', current_post_id = NULL WHERE id = :id AND user_paused = 1 AND run_id IS NULL") suspend fun finishPause(id: String)
    @Query("UPDATE collections SET user_paused = 0, state = 'IDLE', last_error = NULL, next_retry_at = NULL WHERE id = :id AND user_paused = 1 AND state = 'PAUSED'") suspend fun resume(id: String): Int
    @Query("UPDATE collections SET run_id = NULL, state = 'WAITING_AUTH', current_post_id = NULL, next_retry_at = NULL WHERE pool_id IS NULL AND user_paused = 0 AND sync_mode = 'FULL'") suspend fun suspendForAuthentication()
    @Query("UPDATE download_tasks SET status = 'PENDING', bytes_downloaded = 0 WHERE status = 'DOWNLOADING' AND collection_id IN (SELECT id FROM collections WHERE sync_mode = 'FULL')")
    suspend fun recoverApiTasks()
    @Query("UPDATE collections SET sync_mode = :mode WHERE id = :id AND pool_id IS NULL")
    suspend fun setSyncMode(id: String, mode: String)
    @Query("UPDATE download_tasks SET status = 'PENDING', bytes_downloaded = 0 WHERE collection_id = :id AND status = 'DOWNLOADING'") suspend fun resetInterrupted(id: String)
    @Query("SELECT * FROM sync_checkpoints WHERE collection_id = :id") suspend fun checkpoint(id: String): CheckpointEntity?
    @Upsert suspend fun checkpoint(entity: CheckpointEntity)
    @Query("DELETE FROM sync_checkpoints WHERE collection_id = :id") suspend fun clearCheckpoint(id: String)
    @Query("UPDATE collections SET last_seen_post_id = MAX(last_seen_post_id, :watermark) WHERE id = :id AND run_id = :runId") suspend fun commitWatermark(id: String, runId: String, watermark: Long)
    @Query("SELECT * FROM posts WHERE id = :id") suspend fun post(id: Long): PostEntity?
    @Query("SELECT * FROM local_files WHERE collection_id = :id AND post_id = :postId")
    suspend fun localFile(id: String, postId: Long): LocalFileEntity?
    @Query("UPDATE collections SET last_seen_post_id = 0, state = 'IDLE', last_error = NULL, next_retry_at = NULL, retry_attempts = 0 WHERE id = :id AND run_id IS NULL AND user_paused = 0 AND pool_id IS NULL")
    suspend fun restartScan(id: String): Int
    @Upsert suspend fun post(entity: PostEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun membership(entity: MembershipEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun task(entity: TaskEntity)
    @Upsert suspend fun file(entity: LocalFileEntity)
    @Query("DELETE FROM local_files WHERE collection_id = :id AND post_id = :postId") suspend fun removeFile(id: String, postId: Long)
    @Query("UPDATE download_tasks SET status = 'DOWNLOADING', bytes_downloaded = 0, total_bytes = :total, error = NULL, updated_at = :now WHERE collection_id = :id AND post_id = :postId")
    suspend fun markDownloading(id: String, postId: Long, total: Long, now: Long)
    @Query("UPDATE collections SET current_post_id = :postId WHERE id = :id AND run_id = :runId") suspend fun currentPost(id: String, runId: String, postId: Long)
    @Query("UPDATE download_tasks SET bytes_downloaded = :bytes, total_bytes = CASE WHEN :total > 0 THEN :total ELSE total_bytes END, updated_at = :now WHERE collection_id = :id AND post_id = :postId AND status = 'DOWNLOADING'")
    suspend fun progress(id: String, postId: Long, bytes: Long, total: Long, now: Long)
    @Query("UPDATE download_tasks SET status = 'DOWNLOADED', bytes_downloaded = :bytes, total_bytes = :bytes, error = NULL, attempts = 0, updated_at = :now WHERE collection_id = :id AND post_id = :postId")
    suspend fun downloaded(id: String, postId: Long, bytes: Long, now: Long)
    @Query("UPDATE download_tasks SET status = 'PENDING', bytes_downloaded = 0, total_bytes = 0 WHERE collection_id = :id AND post_id = :postId") suspend fun invalidate(id: String, postId: Long)
    @Query("UPDATE download_tasks SET status = 'FAILED', attempts = attempts + :countAttempt, error = :error, updated_at = :now WHERE collection_id = :id AND post_id = :postId")
    suspend fun failed(id: String, postId: Long, error: String, now: Long, countAttempt: Int)
    @Query("UPDATE download_tasks SET attempts = 0 WHERE collection_id = :id AND status = 'FAILED'") suspend fun resetFailures(id: String)
    @Query("UPDATE download_tasks SET attempts = 0 WHERE collection_id = :id AND post_id = :postId AND status = 'FAILED'")
    suspend fun retryFile(id: String, postId: Long)
    @Query("UPDATE collections SET state = 'QUEUED', last_error = NULL, next_retry_at = NULL WHERE id = :id AND user_paused = 0 AND pool_id IS NULL AND run_id IS NULL") suspend fun markQueued(id: String)
    @Query("UPDATE collections SET run_id = NULL, current_post_id = NULL, state = CASE WHEN pool_id IS NOT NULL THEN 'ARCHIVED' WHEN user_paused = 1 THEN 'PAUSED' WHEN state IN ('DISCOVERING', 'VERIFYING', 'SYNCING', 'PAUSING') THEN 'IDLE' ELSE state END") suspend fun recoverCollections()
    @Query("UPDATE download_tasks SET status = 'PENDING', bytes_downloaded = 0 WHERE status = 'DOWNLOADING'") suspend fun recoverTasks()

    @RawQuery(observedEntities = [CollectionEntity::class, PostEntity::class, TaskEntity::class, MembershipEntity::class, LocalFileEntity::class])
    fun summaries(query: SupportSQLiteQuery): Flow<List<SummaryRow>>
    @RawQuery suspend fun gallery(query: SupportSQLiteQuery): List<GalleryRow>
    @RawQuery suspend fun count(query: SupportSQLiteQuery): Int
    @RawQuery suspend fun records(query: SupportSQLiteQuery): List<RecordRow>
    @RawQuery suspend fun failures(query: SupportSQLiteQuery): List<FailureRow>
}

data class SummaryRow(val id: String, val source_post_id: Long, val last_seen_post_id: Long, val state: String,
    val current_post_id: Long?, val last_error: String?, val last_sync_at: Long?, val created_at: Long,
    val downloaded_count: Int, val pending_count: Int, val failed_count: Int, val current_bytes: Long,
    val current_total: Long, val pool_id: Long?, val display_name: String?, val cover_url: String?, val next_retry_at: Long?, val sync_mode: String)

data class GalleryRow(val collection_id: String, val post_id: Long, val file_url: String, val preview_url: String?,
    val local_uri: String?, val status: String, val error: String?)

data class RecordRow(val collection_id: String, val post_id: Long, val file_url: String, val md5: String,
    val status: String, val bytes_downloaded: Long, val total_bytes: Long, val local_uri: String?, val verified_md5: String?,
    val error: String?, val attempts: Int)

data class FailureRow(val collection_id: String, val post_id: Long, val file_url: String, val md5: String,
    val preview_url: String?, val local_uri: String?, val error: String?, val attempts: Int, val updated_at: Long)
