package com.homura251.rule34downloader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class UiMessage(val id: Long = System.nanoTime(), val text: String)

abstract class ActionViewModel : ViewModel() {
    private val mutableMessage = MutableStateFlow<UiMessage?>(null)
    val message = mutableMessage.asStateFlow()
    fun acknowledge(id: Long) { mutableMessage.update { if (it?.id == id) null else it } }
    protected fun notify(text: String) { mutableMessage.value = UiMessage(text = text) }
    protected fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { notify(e.userMessage()) }
    }
}

class SubscriptionsViewModel(private val library: LibraryRepository, private val tasks: TaskController) : ActionViewModel() {
    val collections = library.observeCollections().catch { notify("读取资料库失败，请重试。"); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun sync(id: String) = action { tasks.sync(id) }
    fun downloadKnown(id: String) = action { tasks.downloadKnown(id) }
    fun rescan(id: String) = action { tasks.rescan(id) }
    fun resume(id: String) = action { tasks.resume(id) }
    fun pause(id: String) = action { tasks.pause(id) }
    fun remove(id: String) = action { tasks.remove(id); notify("订阅已移除，已下载文件保留。") }
}

data class AddSubscriptionState(val input: String = "", val resolving: Boolean = false, val saving: Boolean = false,
    val sourcePostId: Long = 0, val candidates: List<Rule34Tag> = emptyList(), val selected: String? = null,
    val error: String? = null, val added: Boolean = false)

class AddSubscriptionViewModel(private val services: AppServices) : ViewModel() {
    private val mutable = MutableStateFlow(AddSubscriptionState())
    val state = mutable.asStateFlow()
    private var resolveJob: Job? = null
    fun reset() { resolveJob?.cancel(); mutable.value = AddSubscriptionState() }
    fun input(value: String) { resolveJob?.cancel(); mutable.value = AddSubscriptionState(input = value) }
    fun select(value: String) { mutable.update { it.copy(selected = value) } }
    fun resolve() {
        val input = state.value.input.trim()
        if (input.isEmpty()) return
        resolveJob?.cancel()
        mutable.update { it.copy(resolving = true, error = null) }
        resolveJob = viewModelScope.launch {
            try {
                requireAccount()
                val result = ResolveSubscription(services.catalog)(input)
                mutable.update { it.copy(resolving = false, sourcePostId = result.sourcePostId, candidates = result.candidates,
                    selected = result.candidates.singleOrNull()?.name,
                    error = if (result.candidates.isEmpty()) "未识别到作者标签，可以直接输入作者标签。" else null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(resolving = false, error = e.userMessage()) } }
        }
    }
    fun subscribe() {
        val current = state.value
        val tag = current.selected ?: return
        if (current.saving) return
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                requireAccount()
                if (!services.library.addCollection(tag, current.sourcePostId)) {
                    mutable.update { it.copy(saving = false, error = "这个作者已经在订阅列表中。") }; return@launch
                }
                services.tasks.sync(tag)
                mutable.update { it.copy(saving = false, added = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(saving = false, error = e.userMessage()) } }
        }
    }
    private fun requireAccount() {
        if (!services.account.state.value.canUseApi) throw SourceFailure(FailureKind.AUTH, "请先配置并验证 API 凭据。")
    }
}

class TasksViewModel(private val library: LibraryRepository, private val tasks: TaskController) : ActionViewModel() {
    val collections = library.observeCollections().catch { notify("读取任务失败，请重试。"); emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun pauseAll() = action { tasks.pauseAll() }
    fun resumeAll() = action { tasks.resumeAll() }
    fun retryFailed() = action { tasks.retryFailed() }
    fun sync(id: String) = action { tasks.sync(id) }
    fun downloadKnown(id: String) = action { tasks.downloadKnown(id) }
    fun rescan(id: String) = action { tasks.rescan(id) }
    fun resume(id: String) = action { tasks.resume(id) }
    fun pause(id: String) = action { tasks.pause(id) }
}

data class SettingsUiState(val userId: String = "", val apiKey: String = "", val validating: Boolean = false,
    val accountError: String? = null, val settings: AppSettings = AppSettings(), val loaded: Boolean = false,
    val saving: Boolean = false)

class SettingsViewModel(private val services: AppServices) : ActionViewModel() {
    private val mutable = MutableStateFlow(SettingsUiState())
    val state = mutable.asStateFlow()
    val account = services.account.state
    val folderState = services.folders.state
    init {
        action {
            val settings = services.settings.snapshot()
            val credentials = services.account.credentials()
            mutable.update { it.copy(userId = credentials?.userId.orEmpty(), settings = settings, loaded = true) }
            services.settings.state.collect { latest ->
                mutable.update { it.copy(settings = it.settings.copy(existingDownloadsTreeUri = latest.existingDownloadsTreeUri)) }
            }
        }
    }
    fun userId(value: String) { mutable.update { it.copy(userId = value, accountError = null) } }
    fun apiKey(value: String) { mutable.update { it.copy(apiKey = value, accountError = null) } }
    fun settings(value: AppSettings) { mutable.update { it.copy(settings = value) } }
    fun verify() {
        val draft = state.value
        if (draft.validating) return
        mutable.update { it.copy(validating = true, accountError = null) }
        viewModelScope.launch {
            try {
                val old = services.account.credentials()
                val userId = draft.userId.trim()
                val key = draft.apiKey.trim().ifEmpty { old?.takeIf { it.userId == userId }?.apiKey.orEmpty() }
                val credentials = ApiCredentials(userId, key)
                services.catalog.validate(credentials)
                ensureActive()
                // Once committed, rebinding work must survive navigation away from this screen.
                withContext(NonCancellable) {
                    services.account.save(credentials)
                    services.tasks.authenticationChanged()
                }
                mutable.update { it.copy(validating = false, apiKey = "") }
                notify("API 验证成功，联网任务已恢复。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(validating = false, accountError = e.userMessage()) } }
        }
    }
    fun clearAccount() = action {
        withContext(NonCancellable) { services.account.clear(); services.tasks.authenticationChanged() }
        mutable.update { it.copy(userId = "", apiKey = "", accountError = null) }
        notify("API 凭据已清除，可以继续浏览本地作品。")
    }
    fun saveSettings() {
        if (state.value.saving) return
        mutable.update { it.copy(saving = true) }
        action {
            try {
                // Keep a folder grant obtained while this screen was open.
                val folder = services.settings.snapshot().existingDownloadsTreeUri
                services.settings.save(state.value.settings.copy(existingDownloadsTreeUri = folder))
                services.tasks.updateSchedule()
                mutable.update { it.copy(settings = services.settings.snapshot()) }
                notify("下载设置已保存。")
            } finally { mutable.update { it.copy(saving = false) } }
        }
    }
    fun linkFolder(uri: String) = action {
        val result = services.folders.link(uri)
        mutable.update { it.copy(settings = services.settings.snapshot()) }
        notify(result.description())
    }
    fun unlinkFolder() = action {
        services.folders.unlink()
        mutable.update { it.copy(settings = services.settings.snapshot()) }
        notify("旧目录关联已取消，未暂停的任务已重新安排；已有文件和订阅保留。")
    }
}

internal fun FolderLinkResult.description(): String = buildString {
    append("旧下载目录已关联")
    selectedDirectory?.let { append("：$it（找到 $filesFound 个文件，识别 $identifiedFiles 个作品文件）") }
    if (restored > 0) append("，恢复 $restored 个作者订阅")
    if (scheduled > 0) append("，已自动同步 $scheduled 个订阅")
    if (waitingForApi) append("；验证 API 后会自动同步")
    if (paused > 0) append("；$paused 个暂停订阅保持暂停")
    if (poolDirectories > 0) append("；$poolDirectories 个 Pool 目录暂未恢复")
    if (unrecognizedDirectories > 0) append("；$unrecognizedDirectories 个目录无法识别作者")
    if (unreadableDirectories > 0) append("；$unreadableDirectories 个子目录无法读取，请单独选择这些目录重新授权")
    if (manualRecovery) append("；请手动添加作者标签后同步，将按作品信息和文件内容校验复用")
    else if (selectedDirectory != null && filesFound == 0 && restored == 0 && scheduled == 0 && paused == 0)
        append("；目录可读取，但未找到文件，请检查所选位置或重新关联")
    append("。")
}

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(private val library: LibraryRepository, private val saved: SavedStateHandle,
    collectionId: String? = null) : ViewModel() {
    private val query = MutableStateFlow(GalleryQuery(collectionId,
        saved.get<String>("filter")?.let { runCatching { GalleryFilter.valueOf(it) }.getOrNull() } ?: GalleryFilter.ALL))
    val filter = query.map { it.filter }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), query.value.filter)
    private val mutableTotal = MutableStateFlow(0)
    val total = mutableTotal.asStateFlow()
    val posts = query.flatMapLatest { selected ->
        Pager(PagingConfig(pageSize = 60, initialLoadSize = 60, prefetchDistance = 12, maxSize = 300)) {
            LibraryPagingSource(library, selected, viewModelScope) { mutableTotal.value = it }
        }.flow
    }.cachedIn(viewModelScope)
    fun filter(value: GalleryFilter) { saved["filter"] = value.name; query.update { it.copy(filter = value) } }
}

private class LibraryPagingSource(private val library: LibraryRepository, private val query: GalleryQuery,
    scope: CoroutineScope, private val onCount: (Int) -> Unit) : PagingSource<Int, GalleryPost>() {
    private val watcher = scope.launch { library.changes.drop(1).collect { invalidate() } }
    init { registerInvalidatedCallback { watcher.cancel() } }
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, GalleryPost> = try {
        val offset = params.key ?: 0
        val page = library.gallery(query, offset, params.loadSize)
        onCount(page.totalCount)
        LoadResult.Page(page.posts, if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
            if (offset + page.posts.size >= page.totalCount || page.posts.isEmpty()) null else offset + page.posts.size)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { LoadResult.Error(e) }
    override fun getRefreshKey(state: PagingState<Int, GalleryPost>): Int? = state.anchorPosition?.let { position ->
        val page = state.closestPageToPosition(position)
        page?.prevKey?.plus(state.config.pageSize) ?: page?.nextKey?.minus(state.config.pageSize)
    }
}
