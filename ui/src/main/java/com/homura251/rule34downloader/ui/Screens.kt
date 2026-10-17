package com.homura251.rule34downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.homura251.rule34downloader.domain.*
import java.text.DateFormat
import java.util.Date

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
    SyncState.VERIFYING -> "校验已有文件"
    SyncState.SYNCING -> "下载中"
    SyncState.PAUSING -> "暂停中"
    SyncState.PAUSED -> "已暂停"
    SyncState.COMPLETE -> "已完成"
    SyncState.SCAN_INCOMPLETE -> "扫描待恢复"
    SyncState.QUEUE_COMPLETE -> "已发现文件已下载"
    SyncState.ERROR -> "有失败项目"
    SyncState.WAITING_AUTH -> "等待 API 验证"
    SyncState.WAITING_NETWORK -> "等待网络"
    SyncState.WAITING_RETRY -> "等待重试"
    SyncState.BLOCKED -> "需要处理"
    SyncState.ARCHIVED -> "本地图集"
}

@Composable
internal fun AccountBanner(account: AccountState, onSettings: () -> Unit) {
    if (account.canUseApi || account.status == AccountStatus.LOADING) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Key, null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(if (account.status == AccountStatus.INVALID) "API 凭据需要验证" else "连接 API 后开始同步", fontWeight = FontWeight.SemiBold)
                Text("本地作品仍可浏览，已发现文件可继续下载。", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onSettings) { Text("配置") }
        }
    }
}

@Composable
fun SubscriptionsScreen(viewModel: SubscriptionsViewModel, account: AccountState, snackbar: SnackbarHostState,
    onBrowse: (String) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, onRequestNotifications: () -> Unit) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<CollectionSummary?>(null) }
    ReportMessages(viewModel, snackbar)
    LazyColumn(Modifier.fillMaxSize().testTag("subscriptions-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            val downloaded = collections.sumOf { it.downloadedCount }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))).padding(24.dp)) {
                    Text("你的作品资料库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("订阅作者，持续保存原文件。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Metric("订阅", collections.size.toString())
                        Metric("已下载", downloaded.toString())
                        Metric("进行中", collections.count { it.syncState.active }.toString())
                    }
                }
            }
        }
        if (collections.isEmpty()) item {
            EmptyState("添加第一位作者", "粘贴帖子链接，或输入作者标签。作品会按作者分目录保存。",
                if (account.canUseApi) "添加订阅" else "配置 API", if (account.canUseApi) onAdd else onSettings)
        }
        items(collections, key = { it.tag }) { collection ->
            CollectionCard(collection, account.canUseApi, onBrowse = { onBrowse(collection.tag) }, onRemove = { removing = collection },
                onSync = { onRequestNotifications(); if (collection.syncState == SyncState.PAUSED) viewModel.resume(collection.tag) else viewModel.sync(collection.tag) }, onPause = { viewModel.pause(collection.tag) },
                onRetryScan = { onRequestNotifications(); viewModel.sync(collection.tag) },
                onDownloadKnown = { onRequestNotifications(); viewModel.downloadKnown(collection.tag) },
                onRescan = { onRequestNotifications(); viewModel.rescan(collection.tag) }, onSettings = onSettings)
        }
    }
    removing?.let { collection ->
        AlertDialog(onDismissRequest = { removing = null }, modifier = Modifier.semantics { testTagsAsResourceId = true }, title = { Text("移除订阅？") },
            text = { Text("${collection.title} 会从资料库移除，已下载文件保留在原目录。") },
            confirmButton = { TextButton({ viewModel.remove(collection.tag); removing = null }, Modifier.testTag("confirm-remove")) { Text("移除") } },
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
private fun CollectionCard(collection: CollectionSummary, canUseApi: Boolean, onBrowse: () -> Unit, onRemove: () -> Unit,
    onSync: () -> Unit, onPause: () -> Unit, onRetryScan: () -> Unit, onDownloadKnown: () -> Unit, onRescan: () -> Unit, onSettings: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("artist-${collection.tag}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(64.dp).clickable(onClick = onBrowse), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    val cover = collection.coverUrl?.takeIf { canUseApi || it.startsWith("content:") }
                    if (cover != null) AsyncImage(cover, "${collection.title} 封面", contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CollectionsBookmark, null) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp).clickable(onClick = onBrowse)) {
                    Text(collection.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (collection.poolId != null) "Pool #${collection.poolId} · 本地归档" else "作者订阅", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton({ menu = true }, Modifier.testTag("artist-menu-${collection.tag}")) { Icon(Icons.Rounded.MoreVert, "更多操作") }
                    DropdownMenu(menu, { menu = false }) {
                        if (collection.poolId == null && collection.syncState.pausable && !collection.syncState.stoppable)
                            DropdownMenuItem(text = { Text("暂停订阅") }, onClick = { menu = false; onPause() })
                        DropdownMenuItem(text = { Text("移除订阅") }, onClick = { menu = false; onRemove() }, modifier = Modifier.testTag("remove-${collection.tag}"))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(collection.syncState)
                Text("${collection.downloadedCount} 已下载 · ${collection.pendingCount} 待下载", Modifier.testTag("artist-count-${collection.tag}"), style = MaterialTheme.typography.labelMedium)
            }
            collection.lastSyncAt?.let { Text("最近同步 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            collection.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onBrowse, modifier = Modifier.weight(1f).testTag("browse-${collection.tag}")) { Text("查看作品") }
                if (collection.poolId == null) OutlinedButton(
                    onClick = if (collection.syncState.stoppable) onPause else onSync,
                    enabled = collection.syncState != SyncState.PAUSING && (collection.syncState.stoppable || canUseApi || (collection.syncState == SyncState.PAUSED && collection.syncMode == SyncMode.KNOWN_FILES)),
                    modifier = Modifier.weight(1f).testTag("sync-${collection.tag}")) {
                    Text(if (collection.syncState.stoppable) "暂停" else if (collection.syncState == SyncState.PAUSED) "继续" else "同步")
                }
            }
            TaskRecoveryActions(collection, canUseApi, onRetryScan, onDownloadKnown, onRescan, onSettings)
        }
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
fun TasksScreen(viewModel: TasksViewModel, canUseApi: Boolean, snackbar: SnackbarHostState, onRequestNotifications: () -> Unit,
    onSettings: () -> Unit) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("全部") }
    ReportMessages(viewModel, snackbar)
    val jobs = collections.filter { it.poolId == null }
    val shown = jobs.filter { when (filter) {
        "进行中" -> it.syncState.active || it.syncState in setOf(SyncState.WAITING_NETWORK, SyncState.WAITING_RETRY, SyncState.PAUSING)
        "已暂停" -> it.syncState == SyncState.PAUSED
        "需处理" -> it.failedCount > 0 || it.syncState in setOf(SyncState.ERROR, SyncState.BLOCKED, SyncState.SCAN_INCOMPLETE, SyncState.WAITING_AUTH)
        else -> true
    } }
    LazyColumn(Modifier.fillMaxSize().testTag("tasks-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Metric("待下载", jobs.sumOf { it.pendingCount }.toString())
                Metric("失败", jobs.sumOf { it.failedCount }.toString())
                Metric("已暂停", jobs.count { it.syncState == SyncState.PAUSED }.toString())
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(viewModel::pauseAll, enabled = jobs.any { it.syncState.pausable }) { Text("全部暂停") }
                OutlinedButton({ onRequestNotifications(); viewModel.resumeAll() }, enabled = jobs.any { it.syncState == SyncState.PAUSED && (canUseApi || it.syncMode == SyncMode.KNOWN_FILES) }) { Text("继续暂停任务") }
                OutlinedButton({ onRequestNotifications(); viewModel.retryFailed() }, enabled = canUseApi && jobs.any { it.failedCount > 0 || it.syncState in setOf(SyncState.ERROR, SyncState.BLOCKED, SyncState.SCAN_INCOMPLETE) }) { Text("重试失败") }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "进行中", "已暂停", "需处理").forEach { value -> FilterChip(filter == value, { filter = value }, label = { Text(value) }) }
            }
        }
        if (shown.isEmpty()) item { EmptyState("没有${if (filter == "全部") "任务" else filter + "任务"}", "订阅同步与下载进度会集中显示在这里。") }
        items(shown, key = { it.tag }) { collection ->
            Card(Modifier.fillMaxWidth().testTag("task-${collection.tag}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(collection.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        StatusBadge(collection.syncState)
                    }
                    Text("${collection.downloadedCount} 已下载 · ${collection.pendingCount} 待下载 · ${collection.failedCount} 失败", style = MaterialTheme.typography.bodySmall)
                    if (collection.syncState == SyncState.SYNCING && collection.currentPostId != null) {
                        Text("原文件 #${collection.currentPostId} · ${formatBytes(collection.currentBytes)} / ${if (collection.currentTotalBytes > 0) formatBytes(collection.currentTotalBytes) else "大小未知"}", style = MaterialTheme.typography.bodySmall)
                        val progress = collection.currentFileProgress
                        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else if (collection.syncState in setOf(SyncState.DISCOVERING, SyncState.VERIFYING)) LinearProgressIndicator(Modifier.fillMaxWidth())
                    collection.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    collection.nextRetryAt?.let { Text("预计重试 ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(if (collection.syncState.stoppable) { { viewModel.pause(collection.tag) } } else { { onRequestNotifications(); if (collection.syncState == SyncState.PAUSED) viewModel.resume(collection.tag) else viewModel.sync(collection.tag) } },
                            enabled = collection.syncState != SyncState.PAUSING && (collection.syncState.stoppable || canUseApi || (collection.syncState == SyncState.PAUSED && collection.syncMode == SyncMode.KNOWN_FILES)),
                            modifier = Modifier.testTag("task-sync-${collection.tag}")) {
                            Text(if (collection.syncState.stoppable) "暂停" else if (collection.syncState == SyncState.PAUSED) "继续" else "重新同步")
                        }
                        if (collection.syncState.pausable && !collection.syncState.stoppable)
                            TextButton({ viewModel.pause(collection.tag) }) { Text("暂停订阅") }
                        else if (collection.syncState in setOf(SyncState.WAITING_NETWORK, SyncState.WAITING_RETRY))
                            TextButton({ onRequestNotifications(); if (collection.syncMode == SyncMode.KNOWN_FILES) viewModel.resume(collection.tag) else viewModel.sync(collection.tag) }, enabled = canUseApi || collection.syncMode == SyncMode.KNOWN_FILES) { Text("立即重试") }
                    }
                    TaskRecoveryActions(collection, canUseApi,
                        onRetry = { onRequestNotifications(); viewModel.sync(collection.tag) },
                        onDownloadKnown = { onRequestNotifications(); viewModel.downloadKnown(collection.tag) },
                        onRescan = { onRequestNotifications(); viewModel.rescan(collection.tag) }, onSettings = onSettings)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
    else -> "$bytes B"
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, snackbar: SnackbarHostState, onLinkFolder: () -> Unit, onRequestNotifications: () -> Unit, setup: Boolean = false, onBrowseOffline: () -> Unit = {}, onAddAuthor: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    var clear by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val folder by viewModel.folderState.collectAsStateWithLifecycle()
    ReportMessages(viewModel, snackbar)
    LazyColumn(Modifier.fillMaxSize().testTag("settings-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (setup) item {
            Icon(Icons.Rounded.Key, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("连接你的 API", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("验证一次，开始订阅作者与保存原文件。密钥仅保存在此设备。", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SettingsSection(if (setup) "API 凭据" else "账号与 API") {
            Text(when (account.status) { AccountStatus.READY -> "已连接 · User ID ${account.userId}"; AccountStatus.INVALID -> "凭据需要重新验证"; else -> "尚未连接" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            CredentialsForm(state, account, viewModel)
            TextButton({ uriHandler.openUri("https://rule34.xxx/index.php?page=account&s=options") }) { Text("打开网站获取 API 凭据") }
            if (!setup && account.status in setOf(AccountStatus.READY, AccountStatus.INVALID)) TextButton({ clear = true }) { Text("清除 API 凭据") }
        } }
        if (setup) item {
            TextButton(onBrowseOffline, modifier = Modifier.fillMaxWidth()) { Text("稍后配置，浏览本地资料库") }
        } else {
            item { SettingsSection("下载与同步") {
                SettingSwitch("仅非计费网络", "手动下载和自动同步都遵循此设置。", state.settings.wifiOnly, Modifier.testTag("wifi-only")) { viewModel.settings(state.settings.copy(wifiOnly = it)) }
                SettingSwitch("自动检查新作品", "系统根据网络与电量条件安排检查。", state.settings.autoSyncEnabled, Modifier.testTag("auto-sync")) {
                    if (it) onRequestNotifications()
                    viewModel.settings(state.settings.copy(autoSyncEnabled = it))
                }
                if (state.settings.autoSyncEnabled) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15L to "15 分钟", 60L to "1 小时", 360L to "6 小时", 1440L to "24 小时").forEach { (minutes, label) ->
                        FilterChip(state.settings.syncIntervalMinutes == minutes, { viewModel.settings(state.settings.copy(syncIntervalMinutes = minutes)) }, label = { Text(label) }, modifier = Modifier.testTag("sync-interval-$minutes"))
                    }
                }
                Button(viewModel::saveSettings, enabled = state.loaded && !state.saving && !folder.linking, modifier = Modifier.testTag("save-settings")) { Text(if (state.saving) "保存中…" else "保存下载设置") }
            } }
            item { SettingsSection("存储与恢复") {
                Text("原文件保存到 Download/Rule34 Downloader/<作者>/。", style = MaterialTheme.typography.bodyMedium)
                Text("可选择作者目录或其上层目录，支持多层子目录和旧文件名。自动识别作者后恢复订阅并同步；无法识别时可手动添加作者，校验后复用文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onLinkFolder, enabled = !folder.linking, modifier = Modifier.testTag("link-downloads")) {
                    Text(if (folder.linking) "正在扫描并安排同步…" else if (state.settings.existingDownloadsTreeUri == null) "关联并自动同步" else "重新关联并同步")
                }
                if (state.settings.existingDownloadsTreeUri != null) {
                    TextButton(viewModel::unlinkFolder, enabled = !folder.linking, modifier = Modifier.testTag("unlink-downloads")) { Text("取消目录关联") }
                    Text("停止扫描此旧目录；已有文件和订阅保留。目录失效时，可取消关联后继续下载。", style = MaterialTheme.typography.bodySmall)
                }
                folder.result?.let { Text(it.copy(waitingForApi = it.waitingForApi && !account.canUseApi).description(), style = MaterialTheme.typography.bodySmall) }
                if (folder.result?.manualRecovery == true) {
                    TextButton(onAddAuthor, enabled = account.canUseApi, modifier = Modifier.testTag("folder-add-author")) { Text("手动添加作者") }
                    if (!account.canUseApi) Text("先验证 API，再添加作者；已关联的目录会保留。", style = MaterialTheme.typography.bodySmall)
                }
                folder.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } }
            item { SettingsSection("关于") {
                Text("Rule34 Downloader", style = MaterialTheme.typography.titleMedium)
                Text("API 获取作品信息 · 校验后保存原文件 · 本地优先浏览", style = MaterialTheme.typography.bodySmall)
                Text("已有 Pool 图集保留本地查看，当前版本暂不支持新增或在线同步 Pool。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }, title = { Text("清除 API 凭据？") }, text = { Text("联网任务将挂起，已下载文件与队列会保留。") },
        confirmButton = { TextButton({ clear = false; viewModel.clearAccount() }, Modifier.testTag("confirm-clear-account")) { Text("清除") } }, dismissButton = { TextButton({ clear = false }) { Text("取消") } })
}

@Composable
private fun CredentialsForm(state: SettingsUiState, account: AccountState, viewModel: SettingsViewModel) {
    var showKey by remember { mutableStateOf(false) }
    OutlinedTextField(state.userId, viewModel::userId, Modifier.fillMaxWidth().testTag("api-user-id"), label = { Text("User ID") }, singleLine = true, enabled = !state.validating, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    OutlinedTextField(state.apiKey, viewModel::apiKey, Modifier.fillMaxWidth().testTag("api-key"), label = { Text("API Key") }, singleLine = true, enabled = !state.validating,
        supportingText = { if (account.userId.isNotEmpty()) Text("留空可重新验证当前设备保存的密钥。") },
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton({ showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "显示或隐藏密钥") } })
    state.accountError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Button(viewModel::verify, Modifier.fillMaxWidth().testTag("verify-api"), enabled = state.loaded && !state.validating && state.userId.isNotBlank()) {
        if (state.validating) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (state.validating) "正在验证…" else "验证并保存")
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
private fun SettingSwitch(title: String, caption: String, value: Boolean, modifier: Modifier = Modifier, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onValue, modifier)
    }
}

@Composable
fun AddSubscriptionDialog(viewModel: AddSubscriptionViewModel, onDismiss: () -> Unit, onRequestNotifications: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.added) { if (state.added) onDismiss() }
    AlertDialog(onDismissRequest = { if (!state.saving) onDismiss() }, modifier = Modifier.semantics { testTagsAsResourceId = true }, title = { Text("添加作者订阅") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("输入作者标签，或粘贴帖子链接 / ID。")
                OutlinedTextField(state.input, viewModel::input, Modifier.fillMaxWidth().testTag("author-input"), label = { Text("作者标签 / 帖子") }, enabled = !state.resolving && !state.saving, singleLine = true)
                if (state.resolving) CircularProgressIndicator(Modifier.size(24.dp))
                state.candidates.forEach { candidate ->
                    Row(Modifier.fillMaxWidth().clickable { viewModel.select(candidate.name) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.selected == candidate.name, { viewModel.select(candidate.name) })
                        Text(candidate.name, Modifier.weight(1f))
                    }
                }
                if (state.selected != null) Text("目录：${com.homura251.rule34downloader.domain.files.DirectoryLayout.relativePath(state.selected.orEmpty())}", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = if (state.selected == null) viewModel::resolve else { { onRequestNotifications(); viewModel.subscribe() } },
                enabled = state.input.isNotBlank() && !state.resolving && !state.saving) {
                Text(if (state.saving) "添加中…" else if (state.selected == null) "继续" else "订阅并同步")
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
