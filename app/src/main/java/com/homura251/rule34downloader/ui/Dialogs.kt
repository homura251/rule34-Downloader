package com.homura251.rule34downloader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AddAuthorDialog(
    state: AddAuthorState,
    onDismiss: () -> Unit,
    onInputChange: (String) -> Unit,
    onResolve: () -> Unit,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加作者") },
        text = {
            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "可以直接输入单个 artist tag，也可以粘贴帖子链接、帖子 ID 或单作者搜索链接。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("artist tag / Rule34 链接 / Post ID") },
                    placeholder = { Text("savvyraexo") },
                    enabled = !state.resolving,
                    singleLine = true,
                )
                if (state.resolving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在读取帖子中的 artist tag…")
                    }
                }
                AnimatedVisibility(!state.error.isNullOrBlank()) {
                    Text(
                        state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.candidates.isNotEmpty()) {
                    Column(
                        Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.candidates.forEach { candidate ->
                            Surface(
                                onClick = { onSelect(candidate.name) },
                                shape = RoundedCornerShape(14.dp),
                                color = if (state.selectedArtist == candidate.name) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = state.selectedArtist == candidate.name,
                                        onClick = { onSelect(candidate.name) },
                                    )
                                    Column {
                                        Text(candidate.name, fontWeight = FontWeight.Medium)
                                        Text(
                                            if (candidate.count > 0L) {
                                                "站内约 ${candidate.count} 个帖子"
                                            } else {
                                                "artist tag"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = if (state.candidates.isEmpty()) onResolve else onConfirm,
                enabled = !state.resolving &&
                    if (state.candidates.isEmpty()) state.input.isNotBlank() else state.selectedArtist != null,
            ) {
                Text(if (state.candidates.isEmpty()) "继续" else "添加并下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun SettingsDialog(
    initial: SettingsState,
    onDismiss: () -> Unit,
    onSave: (SettingsState) -> Unit,
    onVerifyWeb: () -> Unit,
    onLinkDownloads: () -> Unit,
) {
    var draft by remember(initial.userId, initial.apiKey, initial.autoSyncEnabled, initial.syncIntervalMinutes, initial.wifiOnly) { mutableStateOf(initial) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("下载与同步设置")
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "API 凭据是可选的。两个字段都留空时使用匿名网页模式；" +
                            "匿名模式会逐条解析帖子详情，因此更慢，也更容易遇到站点限流。配置 API 后会自动优先使用 API。",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                TextButton(onClick = onVerifyWeb) {
                    Text("网页验证")
                }
                TextButton(onClick = onLinkDownloads) {
                    Text(if (initial.existingDownloadsLinked) "重新关联旧下载目录" else "关联旧下载目录")
                }
                Text(
                    "重装后选择原来的 Download/Rule34 Downloader 文件夹（也可选择单个画师文件夹）。重新添加同一画师并同步，已有文件会校验后复用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "匿名模式遇到 Cloudflare 人机验证时，在这里完成验证，再重新同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.userId,
                    onValueChange = { draft = draft.copy(userId = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("User ID（可选）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = { draft = draft.copy(apiKey = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key（可选）") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "显示或隐藏 API Key",
                            )
                        }
                    },
                )
                Text(
                    "填写时必须同时提供 User ID 与 API Key；凭据用 Android Keystore 加密，仅保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动增量同步", fontWeight = FontWeight.SemiBold)
                        Text("API 与匿名模式都支持", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = draft.autoSyncEnabled,
                        onCheckedChange = { draft = draft.copy(autoSyncEnabled = it) },
                    )
                }
                AnimatedVisibility(draft.autoSyncEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("检查间隔", style = MaterialTheme.typography.labelLarge)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                15L to "15 分钟",
                                60L to "1 小时",
                                360L to "6 小时",
                                1440L to "24 小时",
                            ).forEach { (minutes, label) ->
                                FilterChip(
                                    selected = draft.syncIntervalMinutes == minutes,
                                    onClick = { draft = draft.copy(syncIntervalMinutes = minutes) },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Wifi, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("仅非计费网络")
                            }
                            Switch(
                                checked = draft.wifiOnly,
                                onCheckedChange = { draft = draft.copy(wifiOnly = it) },
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        "Android 10+ 通过 MediaStore 保存到 Download/Rule34 Downloader/<artist>/；" +
                            "不申请存储、定位、联系人、相机或麦克风权限。",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
