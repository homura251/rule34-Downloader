package com.homura251.rule34downloader.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.*
import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.download.*
import com.homura251.rule34downloader.network.HttpMediaTransport
import com.homura251.rule34downloader.storage.ExistingDownloads
import com.homura251.rule34downloader.storage.LinkedDownloadDirectory
import com.homura251.rule34downloader.storage.MediaStoreDownloader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.io.IOException

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
    private val folderLinks = Mutex()
    private val mutableFolderState = MutableStateFlow(FolderLinkState())
    override val state = mutableFolderState.asStateFlow()
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

    private suspend fun enqueue(id: String, policy: ExistingWorkPolicy, userInitiated: Boolean, delayMillis: Long = 0): Boolean {
        val collection = repository.getCollection(id) ?: return false
        if (collection.paused || collection.poolId != null || !account.state.value.canUseApi) return false
        if (policy == ExistingWorkPolicy.KEEP && withContext(Dispatchers.IO) {
            manager.getWorkInfosForUniqueWork(workName(id)).get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
        }) return false
        val preferences = settings.snapshot()
        if (delayMillis == 0L) repository.markQueued(id)
        val request = OneTimeWorkRequestBuilder<ArtistSyncWorker>()
            .setInputData(workDataOf(ArtistSyncWorker.KEY_ARTIST_TAG to id, ArtistSyncWorker.KEY_USER_INITIATED to userInitiated))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (preferences.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(SYNC_TAG).addTag(workName(id)).build()
        withContext(Dispatchers.IO) { manager.enqueueUniqueWork(workName(id), policy, request).result.get(10, TimeUnit.SECONDS) }
        return true
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
        val collections = repository.getCollections()
        for (id in SyncControls.activeTags()) SyncControls.pause(id)
        withContext(Dispatchers.IO) {
            // Pre-2.0 work has the same unique name but does not carry the new group tag.
            val cancellations = collections.map { manager.cancelUniqueWork(workName(it.tag)) }
            manager.cancelAllWorkByTag(SYNC_TAG).result.get(10, TimeUnit.SECONDS)
            cancellations.forEach { it.result.get(10, TimeUnit.SECONDS) }
        }
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
        resyncLinkedFolders(repository.getCollections().filter { it.poolId == null }.mapTo(mutableSetOf()) { it.tag })
    }
    private suspend fun resyncLinkedFolders(ids: Set<String>): Int {
        if (!account.state.value.canUseApi) { repository.suspendForAuthentication(); return 0 }
        var scheduled = 0
        for (collection in repository.getCollections()) if (collection.tag in ids && collection.poolId == null && !collection.paused) {
            SyncControls.pause(collection.tag)
            repository.resetFailures(collection.tag)
            if (enqueue(collection.tag, ExistingWorkPolicy.REPLACE, true)) scheduled++
        }
        return scheduled
    }
    override suspend fun link(uri: String): FolderLinkResult = folderLinks.withLock {
        mutableFolderState.value = FolderLinkState(linking = true)
        try {
            val tree = Uri.parse(uri)
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val job = currentCoroutineContext()
            val scan = withContext(Dispatchers.IO) { LinkedDownloadDirectory(context.contentResolver, tree) { job.ensureActive() }.scan() }
            val plan = FolderRestorePlan.create(scan.directories, repository.getCollections(), scan.wholeLibrary)
            if (plan.targets.isEmpty() && plan.paused == 0 && plan.poolDirectories == 0)
                throw SourceFailure(FailureKind.FILE, "未发现可恢复的作者目录。请选择 Rule34 Downloader 根目录或作者子目录。")
            // Once the association is saved, creating subscriptions and scheduling work must survive navigation.
            val result = withContext(NonCancellable) {
                settings.save(settings.snapshot().copy(existingDownloadsTreeUri = uri))
                var restored = 0
                for (addition in plan.additions) if (repository.addCollection(addition.tag, addition.sourcePostId)) restored++
                val scheduled = resyncLinkedFolders(plan.targets)
                FolderLinkResult(restored, scheduled, plan.paused, plan.targets.isNotEmpty() && !account.state.value.canUseApi,
                    plan.poolDirectories, plan.unrecognizedDirectories)
            }
            mutableFolderState.value = FolderLinkState(result = result)
            result
        } catch (e: CancellationException) {
            mutableFolderState.value = FolderLinkState()
            throw e
        } catch (e: Exception) {
            val error = when (e) {
                is SecurityException -> SourceFailure(FailureKind.FILE, "无法读取所选目录，请重新授权旧下载目录。")
                is IOException -> SourceFailure(FailureKind.FILE, "无法扫描旧下载目录，请检查目录是否可读取。")
                else -> e
            }
            mutableFolderState.value = FolderLinkState(error = error.userMessage())
            throw error
        }
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
