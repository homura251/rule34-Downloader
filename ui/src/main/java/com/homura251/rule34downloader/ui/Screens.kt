package com.homura251.rule34downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.LoadState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.homura251.rule34downloader.domain.*

@Composable
internal fun ReportMessages(viewModel: ActionViewModel, snackbar: SnackbarHostState) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message?.id) { message?.let { snackbar.showSnackbar(it.text); viewModel.acknowledge(it.id) } }
}

internal val SyncState.active: Boolean get() = this in setOf(SyncState.DISCOVERING, SyncState.VERIFYING, SyncState.SYNCING, SyncState.QUEUED)
internal val SyncState.stoppable: Boolean get() = active || this in setOf(SyncState.WAITING_AUTH, SyncState.WAITING_NETWORK, SyncState.WAITING_RETRY)
internal val SyncState.pausable: Boolean get() = this !in setOf(SyncState.PAUSED, SyncState.PAUSING, SyncState.ARCHIVED)
internal val SyncState.label: String get() = when (this) {
    SyncState.IDLE -> "待同步"
    SyncState.QUEUED -> "排队中"
    SyncState.DISCOVERING -> "发现作品"
    SyncState.VERIFYING -> "准备下载"
    SyncState.SYNCING -> "下载中"
    SyncState.PAUSING -> "暂停中"
    SyncState.PAUSED -> "已暂停"
    SyncState.COMPLETE -> "已完成"
    SyncState.SCAN_INCOMPLETE -> "扫描待恢复"
    SyncState.QUEUE_COMPLETE -> "已发现文件已下载"
    SyncState.ERROR -> "有失败项目"
    SyncState.WAITING_AUTH -> "需要配置 API"
    SyncState.WAITING_NETWORK -> "等待网络"
    SyncState.WAITING_RETRY -> "等待重试"
    SyncState.BLOCKED -> "需要处理"
    SyncState.ARCHIVED -> "本地图集"
}

@Composable
fun SubscriptionsScreen(viewModel: SubscriptionsViewModel, snackbar: SnackbarHostState,
    onBrowse: (String) -> Unit, onAdd: () -> Unit, onRequestNotifications: () -> Unit,
    onFailures: (String) -> Unit) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<CollectionSummary?>(null) }
    ReportMessages(viewModel, snackbar)
    LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), modifier = Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.surface).testTag("subscriptions-list"),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (collections.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState("添加第一位作者", "作者作品会自动整理成相册。", "添加作者", onAdd)
        }
        items(collections, key = { it.tag }) { collection ->
            var menu by remember { mutableStateOf(false) }
            Card(onClick = { onBrowse(collection.tag) }, modifier = Modifier.testTag("artist-${collection.tag}")) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (collection.coverUrl != null) AsyncImage(collection.coverUrl, "${collection.title} 封面",
                        modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else Icon(Icons.Rounded.PhotoLibrary, null, Modifier.align(Alignment.Center).size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.align(Alignment.TopEnd)) {
                        FilledTonalIconButton({ menu = true }, Modifier.padding(4.dp)) { Icon(Icons.Rounded.MoreVert, "${collection.title} 更多操作") }
                        DropdownMenu(menu, { menu = false }) {
                            if (collection.poolId == null) {
                                DropdownMenuItem(text = { Text(if (collection.syncState.stoppable) "暂停" else if (collection.syncState == SyncState.PAUSED) "继续" else "更新作者") },
                                    modifier = Modifier.testTag("sync-${collection.tag}"), onClick = {
                                        menu = false
                                        if (collection.syncState.stoppable) viewModel.pause(collection.tag)
                                        else { onRequestNotifications(); if (collection.syncState == SyncState.PAUSED) viewModel.resume(collection.tag) else viewModel.sync(collection.tag) }
                                    })
                                DropdownMenuItem(text = { Text("重新扫描") }, onClick = { menu = false; viewModel.rescan(collection.tag) })
                            }
                            DropdownMenuItem(text = { Text("移除作者") }, onClick = { menu = false; removing = collection })
                        }
                    }
                }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(collection.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val count = collection.downloadedCount + collection.pendingCount + collection.failedCount
                    Text("$count 件作品 · ${collection.downloadedCount} 已下载", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (collection.poolId != null) Text("本地图集", style = MaterialTheme.typography.labelSmall)
                    else if (collection.syncState != SyncState.COMPLETE) Text(collection.syncState.label, style = MaterialTheme.typography.labelSmall)
                    if (collection.failedCount > 0) TextButton({ onFailures(collection.tag) }, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.testTag("author-failures-${collection.tag}")) {
                        Text("${collection.failedCount} 个文件失败", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    removing?.let { collection ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除作者？") },
            text = { Text("${collection.title} 的已下载文件会保留。") },
            confirmButton = { TextButton({ viewModel.remove(collection.tag); removing = null }) { Text("移除") } },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } })
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun StatusBadge(state: SyncState) {
    val error = state in setOf(SyncState.ERROR, SyncState.BLOCKED, SyncState.SCAN_INCOMPLETE, SyncState.WAITING_AUTH)
    Surface(shape = MaterialTheme.shapes.small, color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(state.label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun TasksScreen(viewModel: TasksViewModel, snackbar: SnackbarHostState, onRequestNotifications: () -> Unit,
    onSettings: () -> Unit, initialAuthor: String? = null) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(if (initialAuthor == null) "进行中" else "失败") }
    val selectedAuthor by viewModel.selectedAuthor.collectAsStateWithLifecycle()
    val failures = viewModel.failures.collectAsLazyPagingItems()
    var preview by remember { mutableStateOf<GalleryPost?>(null) }
    LaunchedEffect(initialAuthor) { viewModel.showFailures(initialAuthor) }
    ReportMessages(viewModel, snackbar)
    val jobs = collections.filter { it.poolId == null }
    val shown = jobs.filter { when (filter) {
        "进行中" -> it.syncState.active || it.syncState in setOf(SyncState.WAITING_NETWORK, SyncState.WAITING_RETRY, SyncState.PAUSING)
        "已暂停" -> it.syncState == SyncState.PAUSED
        else -> true
    } }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Metric("待下载", jobs.sumOf { it.pendingCount }.toString())
                Metric("失败文件", jobs.sumOf { it.failedCount }.toString())
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("进行中", "已暂停", "失败", "全部").forEach { value ->
                    FilterChip(filter == value, { filter = value }, label = { Text(value) }, modifier = Modifier.testTag("task-filter-$value"))
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (filter == "失败") {
                    TextButton({ onRequestNotifications(); viewModel.retryFailed() }, enabled = jobs.any { it.failedCount > 0 }) { Text("重试全部失败文件") }
                    selectedAuthor?.let { author -> TextButton({ viewModel.showFailures(null) }) { Text("$author · 显示全部作者") } }
                } else {
                    TextButton(viewModel::pauseAll, enabled = jobs.any { it.syncState.pausable }) { Text("全部暂停") }
                    TextButton({ onRequestNotifications(); viewModel.resumeAll() }, enabled = jobs.any { it.syncState == SyncState.PAUSED }) { Text("继续暂停任务") }
                }
            }
        }
        if (filter == "失败") {
            if (failures.loadState.refresh is LoadState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (failures.loadState.refresh is LoadState.Error) item { EmptyState("读取失败文件出错", "", "重试", failures::retry) }
            else if (failures.itemCount == 0 && failures.loadState.refresh !is LoadState.Loading) item { EmptyState("没有失败文件", "下载错误会显示在这里。") }
            items(failures.itemCount, key = { index -> failures.peek(index)?.key?.let { "${it.collectionId}:${it.postId}" } ?: "loading-$index" }) { index ->
                failures[index]?.let { failure ->
                    Card(Modifier.fillMaxWidth().testTag("failed-${failure.key.collectionId}-${failure.key.postId}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PostImage(failure.post, fullSize = false,
                                    modifier = Modifier.size(76.dp).clickable { preview = failure.post }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(failure.key.collectionId, style = MaterialTheme.typography.titleSmall)
                                    Text(failure.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("Post ID ${failure.key.postId}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text(failure.post.error ?: "下载失败，请重试。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton({ onRequestNotifications(); viewModel.retryFile(failure.key) }, modifier = Modifier.testTag("retry-file-${failure.key.collectionId}-${failure.key.postId}")) { Text("重试文件") }
                                TextButton({ preview = failure.post }) { Text("预览") }
                                val uriHandler = LocalUriHandler.current
                                TextButton({ uriHandler.openUri(failure.post.postUrl) }) { Text("打开原帖") }
                            }
                        }
                    }
                }
            }
            if (failures.loadState.append is LoadState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (failures.loadState.append is LoadState.Error) item { TextButton(failures::retry) { Text("加载更多失败，重试") } }
        } else {
            if (shown.isEmpty()) item { EmptyState("没有${filter}任务", "添加作者后可在这里查看下载进度。") }
            items(shown, key = { it.tag }) { collection ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(collection.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            StatusBadge(collection.syncState)
                        }
                        Text("${collection.downloadedCount} 已下载 · ${collection.pendingCount} 待下载", style = MaterialTheme.typography.bodySmall)
                        if (collection.currentPostId != null && collection.syncState == SyncState.SYNCING) {
                            Text("#${collection.currentPostId} · ${formatBytes(collection.currentBytes)} / ${if (collection.currentTotalBytes > 0) formatBytes(collection.currentTotalBytes) else "大小未知"}", style = MaterialTheme.typography.bodySmall)
                            val progress = collection.currentFileProgress
                            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else if (collection.syncState.active) LinearProgressIndicator(Modifier.fillMaxWidth())
                        collection.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Row {
                            TextButton({
                                if (collection.syncState.stoppable) viewModel.pause(collection.tag)
                                else { onRequestNotifications(); if (collection.syncState == SyncState.PAUSED) viewModel.resume(collection.tag) else viewModel.sync(collection.tag) }
                            }, enabled = collection.syncState != SyncState.PAUSING, modifier = Modifier.testTag("task-sync-${collection.tag}")) {
                                Text(if (collection.syncState.stoppable) "暂停" else if (collection.syncState == SyncState.PAUSED) "继续" else "更新")
                            }
                            if (collection.failedCount > 0) TextButton({ viewModel.showFailures(collection.tag); filter = "失败" }) { Text("查看 ${collection.failedCount} 个失败文件") }
                            if (collection.syncState == SyncState.WAITING_AUTH) TextButton(onSettings) { Text("配置 API") }
                        }
                    }
                }
            }
        }
    }
    preview?.let { post -> SinglePostPreview(post, { preview = null }) }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
    else -> "$bytes B"
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, snackbar: SnackbarHostState, onLinkFolder: () -> Unit, onRequestNotifications: () -> Unit, onAddAuthor: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    var clear by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val folder by viewModel.folderState.collectAsStateWithLifecycle()
    ReportMessages(viewModel, snackbar)
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("settings-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { SettingsSection("账号与 API") {
            Text(when (account.status) { AccountStatus.READY -> "已保存 · User ID ${account.userId}"; AccountStatus.INVALID -> "凭据需要更新"; else -> "尚未连接" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            CredentialsForm(state, account, viewModel)
            TextButton({ uriHandler.openUri("https://rule34.xxx/index.php?page=account&s=options") }) { Text("打开网站获取 API 凭据") }
            if (account.status in setOf(AccountStatus.READY, AccountStatus.INVALID)) TextButton({ clear = true }) { Text("清除 API 凭据") }
        } }
            item { SettingsSection("下载与同步") {
                SettingSwitch("仅非计费网络", "手动下载和自动同步都遵循此设置。", state.settings.wifiOnly) { viewModel.settings(state.settings.copy(wifiOnly = it)) }
                SettingSwitch("自动检查新作品", "系统根据网络与电量条件安排检查。", state.settings.autoSyncEnabled) {
                    if (it) onRequestNotifications()
                    viewModel.settings(state.settings.copy(autoSyncEnabled = it))
                }
                if (state.settings.autoSyncEnabled) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15L to "15 分钟", 60L to "1 小时", 360L to "6 小时", 1440L to "24 小时").forEach { (minutes, label) ->
                        FilterChip(state.settings.syncIntervalMinutes == minutes, { viewModel.settings(state.settings.copy(syncIntervalMinutes = minutes)) }, label = { Text(label) })
                    }
                }
            } }
            item { SettingsSection("存储与恢复") {
                Text("原文件保存到 Download/Rule34 Downloader/<作者>/。", style = MaterialTheme.typography.bodyMedium)
                Text("可选择作者目录或其上层目录，支持多层子目录和旧文件名。自动识别作者后恢复订阅；按 Post ID 或文件名匹配复用文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onLinkFolder, enabled = !folder.linking, modifier = Modifier.testTag("link-downloads")) {
                    Text(if (folder.linking) "正在扫描并安排同步…" else if (state.settings.existingDownloadsTreeUri == null) "关联并自动同步" else "重新关联并同步")
                }
                folder.result?.let { Text(it.copy(waitingForApi = it.waitingForApi && !account.canUseApi).description(), style = MaterialTheme.typography.bodySmall) }
                if (folder.result?.manualRecovery == true) {
                    TextButton(onAddAuthor, modifier = Modifier.testTag("folder-add-author")) { Text("手动添加作者") }
                    if (!account.canUseApi) Text("可先添加作者标签，配置 API 后获取作品列表。", style = MaterialTheme.typography.bodySmall)
                }
                folder.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } }
            item { SettingsSection("关于") {
                Text("Rule34 Downloader", style = MaterialTheme.typography.titleMedium)
                Text("API 获取作品信息 · 保存原文件 · 本地优先浏览", style = MaterialTheme.typography.bodySmall)
                Text("已有 Pool 图集保留本地查看，当前版本暂不支持新增或在线同步 Pool。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清除 API 凭据？") }, text = { Text("作品列表更新将等待配置，已有文件下载与本地浏览继续可用。") },
        confirmButton = { TextButton({ clear = false; viewModel.clearAccount() }) { Text("清除") } }, dismissButton = { TextButton({ clear = false }) { Text("取消") } })
}

@Composable
private fun CredentialsForm(state: SettingsUiState, account: AccountState, viewModel: SettingsViewModel) {
    var showKey by remember { mutableStateOf(false) }
    OutlinedTextField(state.userId, viewModel::userId, Modifier.fillMaxWidth().testTag("api-user-id"), label = { Text("User ID") }, singleLine = true, enabled = !state.savingAccount, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    OutlinedTextField(state.apiKey, viewModel::apiKey, Modifier.fillMaxWidth().testTag("api-key"), label = { Text("API Key") }, singleLine = true, enabled = !state.savingAccount,
        supportingText = { if (account.userId.isNotEmpty()) Text("留空保留当前账号已保存的密钥。") },
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton({ showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "显示或隐藏密钥") } })
    state.accountError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Button(viewModel::saveAccount, Modifier.fillMaxWidth().testTag("verify-api"), enabled = state.loaded && !state.savingAccount && state.userId.isNotBlank()) {
        if (state.savingAccount) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (state.savingAccount) "保存中…" else "保存凭据")
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, caption: String, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onValue)
    }
}

@Composable
fun AddSubscriptionDialog(viewModel: AddSubscriptionViewModel, onDismiss: () -> Unit, onRequestNotifications: () -> Unit,
    onAdded: (String) -> Unit, onSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.addedId) { state.addedId?.let(onAdded) }
    AlertDialog(onDismissRequest = { if (!state.saving) onDismiss() }, title = { Text("添加作者") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(state.input, viewModel::input, Modifier.fillMaxWidth().testTag("author-input"),
                    label = { Text("作者标签 / 帖子链接 / Post ID") }, singleLine = true, enabled = !state.saving)
                if (state.resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.candidates.size > 1) {
                    Text("选择作者")
                    state.candidates.forEach { candidate ->
                        Row(Modifier.fillMaxWidth().clickable { viewModel.select(candidate.name) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(state.selected == candidate.name, { viewModel.select(candidate.name) })
                            Text(candidate.name, Modifier.weight(1f))
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onSettings) { Text("配置 API") } }
            }
        },
        confirmButton = {
            Button({ onRequestNotifications(); viewModel.submit() }, enabled = state.input.isNotBlank() && !state.resolving && !state.saving && (state.candidates.size <= 1 || state.selected != null)) {
                Text(if (state.saving || state.resolving) "添加中…" else "添加")
            }
        }, dismissButton = { TextButton(onDismiss, enabled = !state.saving) { Text("取消") } })
}

@Composable
internal fun EmptyState(title: String, caption: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.CollectionsBookmark, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) FilledTonalButton(onAction) { Text(action) }
    }
}
