package com.homura251.rule34downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homura251.rule34downloader.data.ApiCredentials
import com.homura251.rule34downloader.data.AppPreferences
import com.homura251.rule34downloader.data.CredentialsStore
import com.homura251.rule34downloader.data.Rule34Database
import com.homura251.rule34downloader.data.Rule34Tag
import com.homura251.rule34downloader.network.PostUrlParser
import com.homura251.rule34downloader.network.Rule34Client
import com.homura251.rule34downloader.work.SyncScheduler
import kotlinx.coroutines.Dispatchers
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
)

data class SettingsState(
    val userId: String = "",
    val apiKey: String = "",
    val autoSyncEnabled: Boolean = false,
    val syncIntervalMinutes: Long = AppPreferences.DEFAULT_INTERVAL_MINUTES,
    val wifiOnly: Boolean = false,
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = Rule34Database.getInstance(application)
    private val credentialsStore = CredentialsStore(application)
    private val preferences = AppPreferences(application)

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
        if (!credentialsStore.isConfigured()) {
            eventsChannel.trySend(UiEvent.Message("请先配置 Rule34 API User ID 与 API Key。"))
            openSettings()
            return
        }
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
        )
    }

    fun selectArtist(tag: String) {
        _addAuthorState.value = _addAuthorState.value.copy(selectedArtist = tag)
    }

    fun resolvePostArtists() {
        val current = _addAuthorState.value
        val postId = PostUrlParser.parsePostId(current.input)
        if (postId == null) {
            _addAuthorState.value = current.copy(error = "请输入有效的 Rule34 帖子 URL 或数字 ID。")
            return
        }
        val credentials = credentialsStore.get()
        if (credentials == null) {
            _addAuthorState.value = current.copy(error = "API 凭据尚未配置。")
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
                val client = Rule34Client(credentials)
                val post = client.getPost(postId)
                post to client.resolveArtistTags(post.tags)
            }.onSuccess { (post, artists) ->
                _addAuthorState.value = _addAuthorState.value.copy(
                    resolving = false,
                    sourcePostId = post.id,
                    candidates = artists,
                    selectedArtist = artists.singleOrNull()?.name,
                    error = if (artists.isEmpty()) "这个帖子没有可识别的 artist tag。" else null,
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
        val postId = current.sourcePostId ?: return
        val tag = current.selectedArtist ?: return
        val added = database.addArtist(tag, postId)
        closeAddAuthor()
        if (added) {
            SyncScheduler.enqueueArtistSync(getApplication(), tag)
            eventsChannel.trySend(UiEvent.Message("已添加 $tag，开始下载全部原文件。"))
        } else {
            eventsChannel.trySend(UiEvent.Message("$tag 已在作者列表中。"))
        }
    }

    fun syncArtist(tag: String) {
        if (!credentialsStore.isConfigured()) {
            eventsChannel.trySend(UiEvent.Message("请先配置 API 凭据。"))
            openSettings()
            return
        }
        SyncScheduler.enqueueArtistSync(getApplication(), tag)
    }

    fun removeArtist(tag: String) {
        SyncScheduler.cancelArtistSync(getApplication(), tag)
        database.removeArtist(tag)
        eventsChannel.trySend(UiEvent.Message("已移除 $tag；已下载文件保留在 Download 目录。"))
    }

    fun saveSettings(state: SettingsState) {
        val userId = state.userId.trim()
        val apiKey = state.apiKey.trim()
        if (userId.isEmpty() || apiKey.isEmpty()) {
            eventsChannel.trySend(UiEvent.Message("User ID 与 API Key 都不能为空。"))
            return
        }
        credentialsStore.save(ApiCredentials(userId = userId, apiKey = apiKey))
        preferences.autoSyncEnabled = state.autoSyncEnabled
        preferences.syncIntervalMinutes = state.syncIntervalMinutes
        preferences.wifiOnly = state.wifiOnly
        SyncScheduler.ensurePeriodicSchedule(getApplication())
        _settings.value = loadSettings()
        _showSettings.value = false
        eventsChannel.trySend(UiEvent.Message("设置已保存。"))
    }

    private fun loadSettings(): SettingsState {
        val credentials = credentialsStore.get()
        return SettingsState(
            userId = credentials?.userId.orEmpty(),
            apiKey = credentials?.apiKey.orEmpty(),
            autoSyncEnabled = preferences.autoSyncEnabled,
            syncIntervalMinutes = preferences.syncIntervalMinutes,
            wifiOnly = preferences.wifiOnly,
        )
    }
}
