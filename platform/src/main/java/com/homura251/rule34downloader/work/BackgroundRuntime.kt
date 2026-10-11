package com.homura251.rule34downloader.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.*
import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.download.*
import com.homura251.rule34downloader.network.HttpMediaTransport
import com.homura251.rule34downloader.storage.ExistingDownloads
import com.homura251.rule34downloader.storage.MediaStoreDownloader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

interface BackgroundRuntimeOwner { val backgroundRuntime: BackgroundRuntime }

class BackgroundRuntime(
    private val context: Context,
    val repository: SyncRepository,
    val account: AccountRepository,
    private val settings: SettingsRepository,
    private val catalog: CatalogGateway,
    private val mediaClient: OkHttpClient,
) : TaskController, DownloadFolderAccess {
    private val manager get() = WorkManager.getInstance(context)
    private val slots = Semaphore(2)
    @Volatile private var ready = CompletableDeferred<Unit>()

    suspend fun initialize() {
        if (ready.isCompleted) ready = CompletableDeferred()
        try {
        repository.recoverInterruptedRuns()
        settings.snapshot()
        account.state.first { it.status != AccountStatus.LOADING }
        updateSchedule()
        if (!account.state.value.canUseApi) { repository.suspendForAuthentication(); return }
        for (collection in repository.getCollections()) {
            if (!collection.paused && collection.poolId == null &&
                (repository.queue(collection.tag).isNotEmpty() || collection.syncState in setOf(SyncState.IDLE, SyncState.WAITING_AUTH)))
                enqueue(collection.tag, ExistingWorkPolicy.KEEP, false)
        }
        } catch (e: Exception) { ready.completeExceptionally(e); throw e }
        finally { if (!ready.isCompleted) ready.complete(Unit) }
    }

    override suspend fun sync(id: String) {
        requireAccount()
        val collection = repository.getCollection(id) ?: return
        if (collection.poolId != null) throw SourceFailure(FailureKind.BAD_RESPONSE, "已有 Pool 目前仅支持本地查看。")
        if (collection.paused && !repository.resume(id))
            throw SourceFailure(FailureKind.BAD_RESPONSE, "任务正在停止，请在暂停完成后继续。")
        repository.resetFailures(id)
        val replace = collection.paused || collection.syncState in setOf(SyncState.ERROR, SyncState.BLOCKED,
            SyncState.WAITING_AUTH, SyncState.WAITING_RETRY, SyncState.WAITING_NETWORK)
        enqueue(id, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, true)
    }

    suspend fun automaticSync() {
        if (!account.state.value.canUseApi || !settings.snapshot().autoSyncEnabled) return
        for (collection in repository.getCollections()) if (!collection.paused && collection.poolId == null)
            enqueue(collection.tag, ExistingWorkPolicy.KEEP, false)
    }

    private suspend fun enqueue(id: String, policy: ExistingWorkPolicy, userInitiated: Boolean, delayMillis: Long = 0) {
        val collection = repository.getCollection(id) ?: return
        if (collection.paused || collection.poolId != null || !account.state.value.canUseApi) return
        if (policy == ExistingWorkPolicy.KEEP && withContext(Dispatchers.IO) {
            manager.getWorkInfosForUniqueWork(workName(id)).get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
        }) return
        val preferences = settings.snapshot()
        if (delayMillis == 0L) repository.markQueued(id)
        val request = OneTimeWorkRequestBuilder<ArtistSyncWorker>()
            .setInputData(workDataOf(ArtistSyncWorker.KEY_ARTIST_TAG to id, ArtistSyncWorker.KEY_USER_INITIATED to userInitiated))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (preferences.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(SYNC_TAG).addTag(workName(id)).build()
        manager.enqueueUniqueWork(workName(id), policy, request)
    }

    suspend fun followUp(id: String, delayMillis: Long) = enqueue(id, ExistingWorkPolicy.APPEND_OR_REPLACE, false, delayMillis)

    override suspend fun pause(id: String) {
        repository.requestPause(id)
        SyncControls.pause(id)
        withContext(Dispatchers.IO) { manager.cancelUniqueWork(workName(id)).result.get(10, TimeUnit.SECONDS) }
        repository.finishPause(id)
    }
    override suspend fun remove(id: String) {
        pause(id)
        SyncControls.gate(MediaStoreDownloader.buildRelativePath(id)).withLock { repository.removeCollection(id) }
    }
    override suspend fun pauseAll() {
        for (collection in repository.getCollections()) if (collection.poolId == null && !collection.paused) pause(collection.tag)
    }
    override suspend fun resumeAll() {
        requireAccount()
        for (collection in repository.getCollections()) if (collection.poolId == null && collection.syncState == SyncState.PAUSED) sync(collection.tag)
    }
    override suspend fun retryFailed() {
        requireAccount()
        for (collection in repository.getCollections()) if (collection.poolId == null && !collection.paused &&
            collection.syncState in setOf(SyncState.ERROR, SyncState.BLOCKED, SyncState.WAITING_RETRY, SyncState.WAITING_NETWORK)) sync(collection.tag)
    }
    override suspend fun authenticationChanged() {
        for (id in SyncControls.activeTags()) SyncControls.pause(id)
        withContext(Dispatchers.IO) { manager.cancelAllWorkByTag(SYNC_TAG).result.get(10, TimeUnit.SECONDS) }
        repository.suspendForAuthentication()
        updateSchedule()
        if (account.state.value.canUseApi) for (collection in repository.getCollections())
            if (!collection.paused && collection.poolId == null) enqueue(collection.tag, ExistingWorkPolicy.REPLACE, false)
    }
    override suspend fun updateSchedule() {
        val preferences = settings.snapshot()
        if (!preferences.autoSyncEnabled || !account.state.value.canUseApi) {
            manager.cancelUniqueWork(PERIODIC_NAME); return
        }
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(preferences.syncIntervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (preferences.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    override suspend fun folderChanged() {
        for (collection in repository.getCollections()) if (collection.poolId == null && !collection.paused &&
            collection.syncState in setOf(SyncState.SYNCING, SyncState.DISCOVERING, SyncState.VERIFYING,
                SyncState.QUEUED, SyncState.WAITING_RETRY, SyncState.WAITING_NETWORK)) {
            SyncControls.pause(collection.tag)
            enqueue(collection.tag, ExistingWorkPolicy.REPLACE, false)
        }
    }
    override suspend fun link(uri: String) {
        context.contentResolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        settings.save(settings.snapshot().copy(existingDownloadsTreeUri = uri))
        folderChanged()
    }

    suspend fun execute(id: String, observer: SyncObserver): SyncOutcome = slots.withPermit {
        ready.await()
        SyncControls.gate(MediaStoreDownloader.buildRelativePath(id)).withLock {
            requireAccount()
            val job = currentCoroutineContext()
            val control = SyncControl { job.ensureActive() }
            SyncControls.register(id, control)
            val watcher = CoroutineScope(job).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { control.pause() }
            }
            try {
                val preferences = settings.snapshot()
                val http = mediaClient.newBuilder().addInterceptor(control.interceptor).build()
                val files by lazy { ExistingDownloads(context, id, control::checkActive) { preferences.existingDownloadsTreeUri } }
                val reuse = object : FileReuse {
                    override suspend fun lookup(record: DownloadRecord): ReuseResult = withContext(Dispatchers.IO) {
                        val result = files.lookup(record, control::checkActive)
                        ReuseResult(result.match?.let { SavedFile(it.uri.toString(), it.bytes, it.verifiedMd5) }, result.rejection)
                    }
                }
                SyncEngine(repository, catalog, MediaStoreDownloader(context, HttpMediaTransport(http, control), control::checkActive), reuse, observer).run(id)
            } finally { watcher.cancel(); control.pause(); SyncControls.unregister(id, control) }
        }
    }
    private fun requireAccount() {
        if (!account.state.value.canUseApi) throw SourceFailure(FailureKind.AUTH, "请先配置并验证 API 凭据。")
    }
    companion object {
        private const val SYNC_TAG = "rule34-sync"
        private const val PERIODIC_NAME = "rule34-periodic-discovery"
        fun workName(id: String) = "rule34-sync-$id"
    }
}
