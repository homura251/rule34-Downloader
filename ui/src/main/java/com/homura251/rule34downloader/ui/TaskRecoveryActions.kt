package com.homura251.rule34downloader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.homura251.rule34downloader.domain.*

@Composable
internal fun TaskRecoveryActions(collection: CollectionSummary, canUseApi: Boolean, onRetry: () -> Unit,
    onDownloadKnown: () -> Unit, onRescan: () -> Unit, onSettings: () -> Unit) {
    if (collection.poolId != null || collection.syncState.active || collection.syncState == SyncState.PAUSING) return
    var options by remember { mutableStateOf(false) }
    var confirmRescan by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (collection.pendingCount > 0 || collection.failedCount > 0) {
            TextButton(onDownloadKnown, Modifier.testTag("download-known-${collection.tag}")) { Text("下载已发现文件") }
        }
        TextButton({ options = true }, Modifier.testTag("recovery-${collection.tag}")) { Text("恢复选项") }
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }, title = { Text("恢复同步") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            collection.lastError?.let { Text(it) }
            Text("扫描异常表示作品列表尚未确认完整，不表示已下载文件损坏。可以先下载已发现文件，再重试扫描。")
            TextButton({ options = false; onRetry() }, enabled = canUseApi,
                modifier = Modifier.testTag("retry-scan-${collection.tag}")) { Text("重试扫描") }
            TextButton({ options = false; confirmRescan = true }, enabled = canUseApi,
                modifier = Modifier.testTag("rescan-${collection.tag}")) { Text("从头扫描") }
            TextButton({ options = false; onSettings() }) { Text("打开 API 与旧目录设置") }
        }
    }, confirmButton = { TextButton({ options = false }) { Text("关闭") } })
    if (confirmRescan) AlertDialog(onDismissRequest = { confirmRescan = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }, title = { Text("从头扫描作品列表？") },
        text = { Text("保留订阅、已下载文件和待下载作品。从作者作品列表的开头重新扫描，完整旧文件校验后复用，只补下载缺失或校验不通过的文件。") },
        confirmButton = { TextButton({ confirmRescan = false; onRescan() },
            Modifier.testTag("confirm-rescan-${collection.tag}")) { Text("保留文件并扫描") } },
        dismissButton = { TextButton({ confirmRescan = false }) { Text("取消") } })
}
