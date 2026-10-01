package com.homura251.rule34downloader.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.homura251.rule34downloader.data.AppPreferences
import com.homura251.rule34downloader.data.Rule34Database
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val PERIODIC_SYNC_NAME = "rule34-periodic-discovery"

    fun enqueueArtistSync(context: Context, artistTag: String, resume: Boolean = false, replace: Boolean = false) {
        val database = Rule34Database.getInstance(context)
        if (resume && !database.resumeSync(artistTag)) return
        if (database.isPaused(artistTag)) return
        val preferences = AppPreferences(context)
        val request = OneTimeWorkRequestBuilder<ArtistSyncWorker>()
            .setInputData(workDataOf(ArtistSyncWorker.KEY_ARTIST_TAG to artistTag))
            .setConstraints(networkConstraints(preferences.wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(artistWorkTag(artistTag))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            artistWorkName(artistTag),
            if (resume || replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun restartAfterFolderChange(context: Context) {
        val database = Rule34Database.getInstance(context)
        val manager = WorkManager.getInstance(context)
        val active = SyncControls.activeTags().toSet()
        for (tag in database.getArtistTags()) {
            if (database.isPaused(tag)) continue
            // Backoff/constraint-waiting work has no registered SyncControl yet.
            if (tag !in active && manager.getWorkInfosForUniqueWork(artistWorkName(tag))
                    .get(10, TimeUnit.SECONDS).none { !it.state.isFinished }) continue
            SyncControls.pause(tag)
            enqueueArtistSync(context, tag, replace = true)
        }
    }

    fun cancelArtistSync(context: Context, artistTag: String) {
        SyncControls.pause(artistTag)
        WorkManager.getInstance(context).cancelUniqueWork(artistWorkName(artistTag))
    }

    fun pauseArtistSync(context: Context, artistTag: String) {
        val database = Rule34Database.getInstance(context)
        database.requestPause(artistTag)
        if (!SyncControls.pause(artistTag)) database.finishPaused(artistTag)
    }

    fun ensurePeriodicSchedule(context: Context) {
        val preferences = AppPreferences(context)
        val workManager = WorkManager.getInstance(context)
        if (!preferences.autoSyncEnabled) {
            workManager.cancelUniqueWork(PERIODIC_SYNC_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(
            preferences.syncIntervalMinutes.coerceAtLeast(15L),
            TimeUnit.MINUTES,
        )
            .setConstraints(networkConstraints(preferences.wifiOnly))
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun networkConstraints(wifiOnly: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

    private fun artistWorkName(artistTag: String) = "rule34-sync-$artistTag"
    private fun artistWorkTag(artistTag: String) = "rule34-artist-$artistTag"
}
