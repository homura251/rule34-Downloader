package com.homura251.rule34downloader.data

enum class SyncState {
    IDLE,
    SYNCING,
    COMPLETE,
    ERROR,
}

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}

data class ApiCredentials(
    val userId: String,
    val apiKey: String,
)

data class Rule34Post(
    val id: Long,
    val fileUrl: String,
    val md5: String,
    val tags: List<String>,
    val width: Int? = null,
    val height: Int? = null,
)

data class Rule34Tag(
    val name: String,
    val type: Int,
    val count: Long,
)

data class ArtistRecord(
    val tag: String,
    val sourcePostId: Long,
    val lastSeenPostId: Long,
)

data class ArtistSummary(
    val tag: String,
    val sourcePostId: Long,
    val lastSeenPostId: Long,
    val syncState: SyncState,
    val currentPostId: Long?,
    val lastError: String?,
    val lastSyncAt: Long?,
    val createdAt: Long,
    val downloadedCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
    val currentBytes: Long,
    val currentTotalBytes: Long,
) {
    val currentFileProgress: Float?
        get() = if (currentTotalBytes > 0L) {
            (currentBytes.toDouble() / currentTotalBytes.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        } else {
            null
        }
}

data class DownloadRecord(
    val artistTag: String,
    val postId: Long,
    val fileUrl: String,
    val md5: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
)
