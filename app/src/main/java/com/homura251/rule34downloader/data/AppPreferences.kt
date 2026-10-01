package com.homura251.rule34downloader.data

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    var syncIntervalMinutes: Long
        get() = prefs.getLong(KEY_SYNC_INTERVAL, DEFAULT_INTERVAL_MINUTES).coerceAtLeast(15L)
        set(value) = prefs.edit().putLong(KEY_SYNC_INTERVAL, value.coerceAtLeast(15L)).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var existingDownloadsTreeUri: String?
        get() = prefs.getString("existing_downloads_tree_uri", null)
        set(value) = prefs.edit().putString("existing_downloads_tree_uri", value).apply()

    var verificationUrl: String?
        get() = prefs.getString("verification_url", null)
        set(value) = prefs.edit().putString("verification_url", value).apply()

    var browserDiagnostics: String?
        get() = prefs.getString("browser_diagnostics", null)
        set(value) = prefs.edit().putString("browser_diagnostics", value?.take(8_000)).apply()

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 360L

        private const val PREFS_NAME = "app_preferences"
        private const val KEY_AUTO_SYNC = "auto_sync_enabled"
        private const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
        private const val KEY_WIFI_ONLY = "wifi_only"
    }
}
