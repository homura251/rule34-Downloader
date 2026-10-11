package com.homura251.rule34downloader.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class AccountStatus { LOADING, MISSING, READY, INVALID }
data class AccountState(val status: AccountStatus = AccountStatus.MISSING, val userId: String = "", val error: String? = null) {
    val canUseApi: Boolean get() = status == AccountStatus.READY
}

data class AppSettings(
    val autoSyncEnabled: Boolean = false,
    val syncIntervalMinutes: Long = 360,
    val wifiOnly: Boolean = false,
    val existingDownloadsTreeUri: String? = null,
)

interface AccountRepository {
    val state: StateFlow<AccountState>
    suspend fun credentials(): ApiCredentials?
    suspend fun save(credentials: ApiCredentials)
    suspend fun clear()
    suspend fun invalidate(expected: ApiCredentials, reason: String)
}

interface SettingsRepository {
    val state: StateFlow<AppSettings>
    suspend fun snapshot(): AppSettings
    suspend fun save(settings: AppSettings)
}

interface CatalogGateway {
    val pageSize: Int
    suspend fun validate(credentials: ApiCredentials)
    suspend fun getPost(postId: Long): Rule34Post
    suspend fun resolveArtistTags(post: Rule34Post): List<Rule34Tag>
    suspend fun searchPosts(tag: String, afterPostId: Long, beforePostId: Long?): List<Rule34Post>
}

enum class GalleryFilter { ALL, DOWNLOADED, FAILED, IMAGES, VIDEOS }
data class GalleryQuery(val collectionId: String? = null, val filter: GalleryFilter = GalleryFilter.ALL)

interface LibraryRepository {
    val changes: Flow<Unit>
    fun observeCollections(): Flow<List<CollectionSummary>>
    suspend fun getCollection(id: String): CollectionRecord?
    suspend fun getCollections(): List<CollectionRecord>
    suspend fun addCollection(tag: String, sourcePostId: Long): Boolean
    suspend fun removeCollection(id: String)
    suspend fun gallery(query: GalleryQuery, offset: Int, limit: Int): GalleryPage
}

data class ScanCheckpoint(val lowerWatermark: Long, val beforeId: Long? = null, val maxSeenId: Long = lowerWatermark)

/** Writes made by a runner are scoped to its lease. A cancelled runner cannot overwrite its successor. */
interface SyncRepository : LibraryRepository {
    suspend fun beginRun(id: String, runId: String): Boolean
    suspend fun setState(id: String, runId: String, state: SyncState, error: String? = null, nextRetryAt: Long? = null)
    suspend fun endRun(id: String, runId: String)
    suspend fun requestPause(id: String)
    suspend fun finishPause(id: String)
    suspend fun resume(id: String): Boolean
    suspend fun suspendForAuthentication()
    suspend fun resetInterrupted(id: String, runId: String)
    suspend fun checkpoint(id: String): ScanCheckpoint?
    suspend fun savePage(id: String, runId: String, posts: List<Rule34Post>, checkpoint: ScanCheckpoint)
    suspend fun commitScan(id: String, runId: String, watermark: Long)
    suspend fun repairMetadata(id: String, runId: String, post: Rule34Post)
    suspend fun records(id: String): List<DownloadRecord>
    suspend fun queue(id: String): List<DownloadRecord>
    suspend fun markDownloading(id: String, runId: String, postId: Long, total: Long)
    suspend fun progress(id: String, runId: String, postId: Long, bytes: Long, total: Long)
    suspend fun markDownloaded(id: String, runId: String, postId: Long, file: SavedFile)
    suspend fun invalidateFile(id: String, runId: String, postId: Long)
    suspend fun markFailed(id: String, runId: String, postId: Long, error: String)
    suspend fun resetFailures(id: String)
    suspend fun markQueued(id: String)
    suspend fun recoverInterruptedRuns()
}

data class SavedFile(val uri: String, val bytes: Long, val verifiedMd5: String)
data class ReuseResult(val file: SavedFile? = null, val rejection: String? = null)

interface FileReuse {
    suspend fun lookup(record: DownloadRecord): ReuseResult
}

interface MediaDownloader {
    suspend fun cleanInterruptedFiles(collectionId: String)
    /** Invoke onPublished inside the uncancellable publication boundary, before returning. */
    suspend fun download(record: DownloadRecord, onHeaders: (Long) -> Unit, onProgress: (Long, Long) -> Unit,
        onPublished: suspend (SavedFile) -> Unit): SavedFile
}

interface TaskController {
    suspend fun sync(id: String)
    suspend fun pause(id: String)
    suspend fun remove(id: String)
    suspend fun pauseAll()
    suspend fun resumeAll()
    suspend fun retryFailed()
    suspend fun authenticationChanged()
    suspend fun updateSchedule()
    suspend fun folderChanged()
}

interface DownloadFolderAccess { suspend fun link(uri: String) }

enum class StartupStatus { LOADING, READY, ERROR }
data class StartupState(val status: StartupStatus = StartupStatus.LOADING, val error: String? = null)

/** App assembles concrete adapters; the UI sees only these contracts. */
interface AppServices {
    val startup: StateFlow<StartupState>
    fun retryStartup()
    val library: LibraryRepository
    val account: AccountRepository
    val settings: SettingsRepository
    val catalog: CatalogGateway
    val tasks: TaskController
    val folders: DownloadFolderAccess
}

enum class FailureKind { AUTH, NETWORK, RATE_LIMIT, SERVER, BLOCKED, BAD_RESPONSE, NOT_FOUND, FILE }
class SourceFailure(val kind: FailureKind, override val message: String, val retryAfterMillis: Long? = null) : Exception(message) {
    val retryable: Boolean get() = kind in setOf(FailureKind.NETWORK, FailureKind.RATE_LIMIT, FailureKind.SERVER)
}

fun Throwable.userMessage(): String = when (this) {
    is SourceFailure -> message
    is com.homura251.rule34downloader.domain.files.FileIntegrityException -> message.orEmpty()
    else -> "操作失败，请重试；已完成的文件会保留。"
}
