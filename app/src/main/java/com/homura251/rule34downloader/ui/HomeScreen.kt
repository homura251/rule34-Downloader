package com.homura251.rule34downloader.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homura251.rule34downloader.data.ArtistSummary
import com.homura251.rule34downloader.data.SyncState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    artists: List<ArtistSummary>,
    credentialsConfigured: Boolean,
    snackbarHostState: SnackbarHostState,
    onSettings: () -> Unit,
    onAdd: () -> Unit,
    onSync: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBrowse: (String) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Rule34 Downloader", fontWeight = FontWeight.SemiBold)
                        Text(
                            "按 artist tag 分目录保存原文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("添加作者") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!credentialsConfigured) {
                item { OptionalApiCard(onSettings) }
            }
            if (artists.isEmpty()) {
                item { EmptyCard(onAdd) }
            }
            items(artists, key = { it.tag }) { artist ->
                ArtistCard(
                    artist = artist,
                    onSync = { onSync(artist.tag) },
                    onRemove = { onRemove(artist.tag) },
                    onBrowse = { onBrowse(artist.tag) },
                )
            }
        }
    }
}

@Composable
private fun OptionalApiCard(onSettings: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    "当前为匿名网页模式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "无需 API 也可以下载。配置自己的 User ID 与 API Key 后，会优先使用更快、更稳定的 API 模式。",
            )
            FilledTonalButton(onClick = onSettings) {
                Text("可选：配置 API")
            }
        }
    }
}

@Composable
private fun EmptyCard(onAdd: () -> Unit) {
    Card(shape = RoundedCornerShape(28.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    Icons.Rounded.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.padding(18.dp).size(34.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                "还没有作者",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "直接输入 artist tag，或粘贴 Rule34 帖子/作者搜索链接，然后把该作者原文件下载到独立目录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onAdd) {
                Text("添加作者")
            }
        }
    }
}

@Composable
private fun ArtistCard(
    artist: ArtistSummary,
    onSync: () -> Unit,
    onRemove: () -> Unit,
    onBrowse: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        artist.tag,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Download/Rule34 Downloader/${artist.tag}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                StatusPill(artist.syncState)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Metric("已完成", artist.downloadedCount)
                Metric("待处理", artist.pendingCount)
                Metric("失败", artist.failedCount)
            }

            AnimatedVisibility(
                visible = artist.syncState == SyncState.SYNCING,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    artist.currentFileProgress?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                    Text(
                        artist.currentPostId?.let { "正在保存原文件 #$it" }
                            ?: "正在检查新作品…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(visible = !artist.lastError.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        artist.lastError.orEmpty(),
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 9.dp,
                        ),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("查看作品")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    artist.lastSyncAt?.let(::relativeTime)
                        ?: "尚未完成首次同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmRemove = true }) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("移除")
                }
                FilledTonalButton(
                    onClick = onSync,
                    enabled = artist.syncState != SyncState.SYNCING,
                ) {
                    AnimatedContent(
                        targetState = artist.syncState == SyncState.SYNCING,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "syncButton",
                    ) { syncing ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (syncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(if (syncing) "同步中" else "同步")
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("移除 ${artist.tag}？") },
            text = { Text("停止跟踪该 artist tag；已保存到 Download 的文件不会删除。") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                    },
                ) {
                    Text("移除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun Metric(label: String, count: Int) {
    Column {
        Text(count.toString(), fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatusPill(state: SyncState) {
    val container = when (state) {
        SyncState.COMPLETE -> MaterialTheme.colorScheme.primaryContainer
        SyncState.ERROR -> MaterialTheme.colorScheme.errorContainer
        SyncState.SYNCING -> MaterialTheme.colorScheme.secondaryContainer
        SyncState.IDLE -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = container,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "status",
        ) { value ->
            Row(
                modifier = Modifier.padding(
                    horizontal = 10.dp,
                    vertical = 6.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when (value) {
                    SyncState.COMPLETE -> Icons.Rounded.CheckCircle
                    SyncState.ERROR -> Icons.Rounded.ErrorOutline
                    SyncState.SYNCING -> Icons.Rounded.CloudSync
                    SyncState.IDLE -> Icons.Rounded.Schedule
                }
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    when (value) {
                        SyncState.COMPLETE -> "已完成"
                        SyncState.ERROR -> "需重试"
                        SyncState.SYNCING -> "同步中"
                        SyncState.IDLE -> "等待"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun relativeTime(timeMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timeMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
