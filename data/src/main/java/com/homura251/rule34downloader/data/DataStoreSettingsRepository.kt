package com.homura251.rule34downloader.data

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val Context.settingsStore by preferencesDataStore("settings_v2", produceMigrations = { context ->
    listOf(SharedPreferencesMigration(context, "app_preferences", keysToMigrate = setOf(
        "auto_sync_enabled", "sync_interval_minutes", "wifi_only", "existing_downloads_tree_uri")))
})

class DataStoreSettingsRepository(context: Context, scope: CoroutineScope) : SettingsRepository {
    private val store = context.applicationContext.settingsStore
    private val auto = booleanPreferencesKey("auto_sync_enabled")
    private val interval = longPreferencesKey("sync_interval_minutes")
    private val wifi = booleanPreferencesKey("wifi_only")
    private val folder = stringPreferencesKey("existing_downloads_tree_uri")
    private fun Preferences.settings() = AppSettings(this[auto] ?: false, (this[interval] ?: 360).coerceAtLeast(15),
        this[wifi] ?: false, this[folder])
    override val state = store.data.map { it.settings() }.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    override suspend fun snapshot() = store.data.first().settings()
    override suspend fun save(settings: AppSettings) {
        store.edit { prefs ->
            prefs[auto] = settings.autoSyncEnabled; prefs[interval] = settings.syncIntervalMinutes.coerceAtLeast(15)
            prefs[wifi] = settings.wifiOnly
            val uri = settings.existingDownloadsTreeUri
            if (uri == null) prefs.remove(folder) else prefs[folder] = uri
        }
    }
}
