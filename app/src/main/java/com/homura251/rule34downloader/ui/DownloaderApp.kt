package com.homura251.rule34downloader.ui

import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homura251.rule34downloader.data.SyncState

@Composable
fun DownloaderApp(
    viewModel: MainViewModel,
    onRequestNotificationPermission: () -> Unit,
) {
    val context = LocalContext.current
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val addState by viewModel.addAuthorState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val previousStates = remember { mutableStateMapOf<String, SyncState>() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is UiEvent.Message) snackbarHostState.showSnackbar(event.text)
        }
    }

    LaunchedEffect(artists) {
        artists.forEach { artist ->
            val previous = previousStates[artist.tag]
            if (previous == SyncState.SYNCING && artist.syncState == SyncState.COMPLETE) {
                snackbarHostState.showSnackbar("${artist.tag} 同步完成")
            } else if (previous == SyncState.SYNCING && artist.syncState == SyncState.ERROR) {
                snackbarHostState.showSnackbar("${artist.tag} 同步结束，有项目需要重试")
            }
            previousStates[artist.tag] = artist.syncState
        }
        val active = artists.mapTo(mutableSetOf()) { it.tag }
        previousStates.keys.filterNot(active::contains).forEach(previousStates::remove)
    }

    HomeScreen(
        artists = artists,
        credentialsConfigured = viewModel.credentialsConfigured,
        snackbarHostState = snackbarHostState,
        onSettings = viewModel::openSettings,
        onAdd = viewModel::openAddAuthor,
        onSync = viewModel::syncArtist,
        onRemove = viewModel::removeArtist,
    )

    if (addState.open) {
        AddAuthorDialog(
            state = addState,
            onDismiss = viewModel::closeAddAuthor,
            onInputChange = viewModel::updateAddInput,
            onResolve = viewModel::resolveAuthorInput,
            onSelect = viewModel::selectArtist,
            onConfirm = viewModel::subscribeSelectedArtist,
        )
    }

    if (showSettings) {
        SettingsDialog(
            initial = settings,
            onDismiss = viewModel::closeSettings,
            onVerifyWeb = { context.startActivity(Intent(context, WebVerificationActivity::class.java)) },
            onSave = { draft ->
                if (draft.autoSyncEnabled) onRequestNotificationPermission()
                viewModel.saveSettings(draft)
            },
        )
    }
}
