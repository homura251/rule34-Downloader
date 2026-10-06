package com.homura251.rule34downloader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homura251.rule34downloader.data.ApiCredentials
import com.homura251.rule34downloader.data.AppPreferences
import com.homura251.rule34downloader.data.CredentialsStore
import com.homura251.rule34downloader.data.Rule34Database
import com.homura251.rule34downloader.data.Rule34Tag
import com.homura251.rule34downloader.data.GalleryPost
import com.homura251.rule34downloader.data.SyncState
import com.homura251.rule34downloader.network.PostUrlParser
import com.homura251.rule34downloader.network.Rule34Client
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.network.Rule34PoolClient
import com.homura251.rule34downloader.work.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AddAuthorState(
    val open: Boolean = false,
    val input: String = "",
    val resolving: Boolean = false,
    val sourcePostId: Long? = null,
    val candidates: List<Rule34Tag> = emptyList(),
    val selectedArtist: String? = null,
    val error: String? = null,
    val poolId: Long? = null,
    val poolTitle: String? = null,
)

data class SettingsState(
    val userId: String = "",
    val apiKey: String = "",
    val autoSyncEnabled: Boolean = false,
    val syncIntervalMinutes: Long = AppPreferences.DEFAULT_INTERVAL_MINUTES,
    val wifiOnly: Boolean = false,
    val existingDownloadsLinked: Boolean = false,
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
}

data class GalleryState(
    val artistTag: String? = null,
    val posts: List<GalleryPost> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val title: String? = null,
    val poolId: Long? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = Rule34Database.getInstance(application)
    private val credentialsStore = CredentialsStore(application)
    private val preferences = AppPreferences(application)
    private val _gallery = MutableStateFlow(GalleryState())
    val gallery: StateFlow<GalleryState> = _gallery.asStateFlow()
    private var galleryJob: Job? = null
    private var galleryLimit = GALLERY_PAGE_SIZE

    val artists = database.observeArtistSummaries().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _addAuthorState = MutableStateFlow(AddAuthorState())
    val addAuthorState: StateFlow<AddAuthorState> = _addAuthorState.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val eventsChannel = Channel<UiEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    val credentialsConfigured: Boolean
        get() = credentialsStore.isConfigured()

    fun openSettings() {
        _settings.value = loadSettings()
        _showSettings.value = true
    }

    fun closeSettings() {
        _showSettings.value = false
    }

    fun openAddAuthor() {
        _addAuthorState.value = AddAuthorState(open = true)
    }

    fun closeAddAuthor() {
        _addAuthorState.value = AddAuthorState()
    }

    fun updateAddInput(value: String) {
        _addAuthorState.value = _addAuthorState.value.copy(
            input = value,
            sourcePostId = null,
            candidates = emptyList(),
            selectedArtist = null,
            error = null,
            poolId = null,
            poolTitle = null,
        )
    }

    fun selectArtist(tag: String) {
        _addAuthorState.value = _addAuthorState.value.copy(selectedArtist = tag)
    }

    fun resolveAuthorInput() {
        val current = _addAuthorState.value
        val input = current.input.trim()
        if (input.isEmpty()) {
            _addAuthorState.value = current.copy(error = "请输入 artist tag、帖子链接/ID 或图集链接。")
            return
        }

        val poolId = PostUrlParser.parsePoolId(input)
        if (poolId != null) {
            _addAuthorState.value = current.copy(resolving = true, error = null, candidates = emptyList(), selectedArtist = null)
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    Rule34PoolClient(Rule34Network.get(getApplication()).htmlClient()).getPage(poolId)
                }.onSuccess { page ->
                    if (!_addAuthorState.value.open || _addAuthorState.value.input != current.input) return@onSuccess
                    _addAuthorState.value = _addAuthorState.value.copy(
                        resolving = false, poolId = poolId, poolTitle = page.title,
                        sourcePostId = 0L, selectedArtist = Rule34PoolClient.key(poolId), error = null,
                    )
                }.onFailure { error ->
                    if (!_addAuthorState.value.open || _addAuthorState.value.input != current.input) return@onFailure
                    _addAuthorState.value = _addAuthorState.value.copy(resolving = false, error = error.message ?: "读取图集失败。")
                }
            }
            return
        }
        val postId = PostUrlParser.parsePostId(input)
        if (postId == null) {
            val tag = PostUrlParser.parseArtistTag(input)
            if (tag == null) {
                _addAuthorState.value = current.copy(
                    error = "无法识别输入。请输入单个 artist tag，或粘贴 Rule34 帖子、作者搜索、Pool 图集链接。",
                )
                return
            }
            _addAuthorState.value = current.copy(
                sourcePostId = 0L,
                candidates = listOf(
                    Rule34Tag(
                        name = tag,
                        type = Rule34Client.ARTIST_TAG_TYPE,
                        count = 0L,
                    ),
                ),
                selectedArtist = tag,
                error = null,
            )
            return
        }

        _addAuthorState.value = current.copy(
            resolving = true,
            error = null,
            candidates = emptyList(),
            selectedArtist = null,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val credentials = credentialsStore.get()
                if (credentials != null) {
                    val client = Rule34Client(credentials)
                    val post = client.getPost(postId)
                    post.id to client.resolveArtistTags(post.tags)
                } else {
                    val resolved = Rule34Network.get(getApplication()).htmlClient()
                        .getPostWithArtists(postId)
                    resolved.post.id to resolved.artists
                }
            }.onSuccess { (resolvedPostId, artists) ->
                _addAuthorState.value = _addAuthorState.value.copy(
                    resolving = false,
                    sourcePostId = resolvedPostId,
                    candidates = artists,
                    selectedArtist = artists.singleOrNull()?.name,
                    error = if (artists.isEmpty()) {
                        "这个帖子没有识别到 artist tag；也可以直接输入作者 tag 添加。"
                    } else {
                        null
                    },
                )
            }.onFailure { error ->
                _addAuthorState.value = _addAuthorState.value.copy(
                    resolving = false,
                    error = error.message ?: "读取帖子信息失败。",
                )
            }
        }
    }

    fun subscribeSelectedArtist() {
        val current = _addAuthorState.value
        val tag = current.selectedArtist ?: return
        val postId = current.sourcePostId ?: 0L
        val added = database.addArtist(tag, postId, current.poolId, current.poolTitle)
        closeAddAuthor()
        if (added) {
            SyncScheduler.enqueueArtistSync(getApplication(), tag, userInitiated = true)
            val mode = if (credentialsStore.isConfigured()) "API 元数据" else "匿名网页"
            eventsChannel.trySend(UiEvent.Message(if (current.poolId != null) {
                "已添加图集 ${current.poolTitle}，开始整组同步。"
            } else "已添加 $tag，使用${mode}模式开始同步。"))
        } else {
            eventsChannel.trySend(UiEvent.Message("${current.poolTitle ?: tag} 已在列表中。"))
        }
    }

    fun syncArtist(tag: String) {
        viewModelScope.launch(Dispatchers.IO) {
            SyncScheduler.enqueueArtistSync(getApplication(), tag, resume = database.isPaused(tag),
                replace = database.getSyncState(tag) == SyncState.ERROR, userInitiated = true)
        }
    }

    fun pauseArtist(tag: String) {
        viewModelScope.launch(Dispatchers.IO) { SyncScheduler.pauseArtistSync(getApplication(), tag) }
    }

    fun downloadsFolderUri(): Uri? = preferences.existingDownloadsTreeUri?.let(Uri::parse)

    fun linkDownloadsFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                preferences.existingDownloadsTreeUri = uri.toString()
                // Gallery URIs can still reference the previous tree. Retain its
                // read grant so changing the scan folder cannot break old previews.
                SyncScheduler.restartAfterFolderChange(getApplication())
                _settings.value = _settings.value.copy(existingDownloadsLinked = true)
                eventsChannel.send(UiEvent.Message("旧下载目录已关联，正在同步的任务会重新扫描；暂停任务继续后会校验并复用旧文件。"))
            } catch (_: Exception) {
                eventsChannel.send(UiEvent.Message("目录授权失败，请重新选择 Rule34 Downloader 文件夹。"))
            }
        }
    }

    fun openGallery(tag: String) {
        galleryLimit = GALLERY_PAGE_SIZE
        _gallery.value = GalleryState(artistTag = tag, loading = true)
        observeGallery(tag)
    }

    fun closeGallery() {
        galleryJob?.cancel()
        _gallery.value = GalleryState()
    }

    fun loadMoreGallery() {
        val tag = _gallery.value.artistTag ?: return
        if (_gallery.value.loading || _gallery.value.posts.size >= _gallery.value.totalCount) return
        galleryLimit += GALLERY_PAGE_SIZE
        _gallery.value = _gallery.value.copy(loading = true, error = null)
        observeGallery(tag)
    }

    fun retryGallery() {
        val tag = _gallery.value.artistTag ?: return
        _gallery.value = _gallery.value.copy(loading = true, error = null)
        observeGallery(tag)
    }

    private fun observeGallery(tag: String) {
        galleryJob?.cancel()
        val limit = galleryLimit
        galleryJob = viewModelScope.launch {
            try {
                database.observeGallery(tag, limit).collect { page ->
                    _gallery.value = GalleryState(tag, page.posts, page.totalCount, title = page.title, poolId = page.poolId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _gallery.value = _gallery.value.copy(loading = false, error = e.message ?: "读取作品失败。")
            }
        }
    }

    fun removeArtist(tag: String) {
        SyncScheduler.cancelArtistSync(getApplication(), tag)
        database.removeArtist(tag)
        eventsChannel.trySend(UiEvent.Message("已移除 $tag；已下载文件保留在 Download 目录。"))
    }

    fun saveSettings(state: SettingsState) {
        val userId = state.userId.trim()
        val apiKey = state.apiKey.trim()
        when {
            userId.isEmpty() && apiKey.isEmpty() -> credentialsStore.clear()
            userId.isEmpty() || apiKey.isEmpty() -> {
                eventsChannel.trySend(
                    UiEvent.Message("要么同时填写 User ID 与 API Key，要么两个都留空使用匿名模式。"),
                )
                return
            }
            else -> credentialsStore.save(ApiCredentials(userId = userId, apiKey = apiKey))
        }

        preferences.autoSyncEnabled = state.autoSyncEnabled
        preferences.syncIntervalMinutes = state.syncIntervalMinutes
        preferences.wifiOnly = state.wifiOnly
        SyncScheduler.ensurePeriodicSchedule(getApplication())
        _settings.value = loadSettings()
        _showSettings.value = false
        eventsChannel.trySend(
            UiEvent.Message(
                if (credentialsStore.isConfigured()) {
                    "设置已保存，帖子元数据优先使用 API；原文件仍走媒体下载链路。"
                } else {
                    "设置已保存，当前使用匿名网页模式。"
                },
            ),
        )
    }

    private fun loadSettings(): SettingsState {
        val credentials = credentialsStore.get()
        return SettingsState(
            userId = credentials?.userId.orEmpty(),
            apiKey = credentials?.apiKey.orEmpty(),
            autoSyncEnabled = preferences.autoSyncEnabled,
            syncIntervalMinutes = preferences.syncIntervalMinutes,
            wifiOnly = preferences.wifiOnly,
            existingDownloadsLinked = preferences.existingDownloadsTreeUri != null,
        )
    }

    companion object {
        private const val GALLERY_PAGE_SIZE = 60
    }
}
