package com.homura251.rule34downloader.data.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_post_id") val sourcePostId: Long,
    @ColumnInfo(name = "last_seen_post_id") val lastSeenPostId: Long = 0,
    @ColumnInfo(name = "user_paused") val userPaused: Boolean = false,
    @ColumnInfo(name = "pool_id") val poolId: Long? = null,
    @ColumnInfo(name = "display_name") val displayName: String? = null,
    val state: String = "IDLE",
    @ColumnInfo(name = "run_id") val runId: String? = null,
    @ColumnInfo(name = "current_post_id") val currentPostId: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "retry_attempts") val retryAttempts: Int = 0,
    @ColumnInfo(name = "sync_mode", defaultValue = "'FULL'") val syncMode: String = "FULL",
)

@Entity(tableName = "posts")
data class PostEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "file_url") val fileUrl: String,
    @ColumnInfo(name = "preview_url") val previewUrl: String?,
    val md5: String,
    val width: Int? = null,
    val height: Int? = null,
    val tags: String = "",
)

@Entity(tableName = "collection_posts", primaryKeys = ["collection_id", "post_id"],
    foreignKeys = [
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collection_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PostEntity::class, parentColumns = ["id"], childColumns = ["post_id"], onDelete = ForeignKey.CASCADE),
    ], indices = [Index("post_id"), Index(value = ["collection_id", "position"])])
data class MembershipEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "post_id") val postId: Long,
    val position: Int? = null,
    @ColumnInfo(name = "preview_url") val previewUrl: String? = null,
)

@Entity(tableName = "download_tasks", primaryKeys = ["collection_id", "post_id"],
    foreignKeys = [ForeignKey(entity = MembershipEntity::class, parentColumns = ["collection_id", "post_id"], childColumns = ["collection_id", "post_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["collection_id", "status"])])
data class TaskEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "post_id") val postId: Long,
    val status: String = "PENDING",
    @ColumnInfo(name = "bytes_downloaded") val bytesDownloaded: Long = 0,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long = 0,
    val attempts: Int = 0,
    val error: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "local_files", primaryKeys = ["collection_id", "post_id"],
    foreignKeys = [ForeignKey(entity = MembershipEntity::class, parentColumns = ["collection_id", "post_id"], childColumns = ["collection_id", "post_id"], onDelete = ForeignKey.CASCADE)])
data class LocalFileEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "post_id") val postId: Long,
    val uri: String,
    val bytes: Long,
    @ColumnInfo(name = "verified_md5") val verifiedMd5: String?,
)

@Entity(tableName = "sync_checkpoints", foreignKeys = [ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collection_id"], onDelete = ForeignKey.CASCADE)])
data class CheckpointEntity(
    @PrimaryKey @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "lower_watermark") val lowerWatermark: Long,
    @ColumnInfo(name = "before_id") val beforeId: Long?,
    @ColumnInfo(name = "max_seen_id") val maxSeenId: Long,
)
