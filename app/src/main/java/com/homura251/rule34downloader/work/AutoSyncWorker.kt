package com.homura251.rule34downloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.homura251.rule34downloader.data.AppPreferences
import com.homura251.rule34downloader.data.Rule34Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutoSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!AppPreferences(applicationContext).autoSyncEnabled) {
            return@withContext Result.success()
        }
        Rule34Database.getInstance(applicationContext)
            .getArtistTags()
            .forEach { tag -> SyncScheduler.enqueueArtistSync(applicationContext, tag, userInitiated = false) }
        Result.success()
    }
}
