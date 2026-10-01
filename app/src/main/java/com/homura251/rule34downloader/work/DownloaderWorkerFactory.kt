package com.homura251.rule34downloader.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.homura251.rule34downloader.BuildConfig

/** The regular WorkManager engine is also used by on-device integration tests. */
internal object DownloaderWorkerFactory : WorkerFactory() {
    @Volatile private var testServices: SyncServicesFactory? = null

    internal fun setServicesForTests(value: SyncServicesFactory?) {
        check(BuildConfig.DEBUG)
        testServices = value
    }

    override fun createWorker(context: Context, className: String, parameters: WorkerParameters): ListenableWorker? {
        if (BuildConfig.DEBUG && className == ArtistSyncWorker::class.java.name) {
            testServices?.let { return ArtistSyncWorker(context, parameters, it) }
        }
        return null // Default production constructors for every other case.
    }
}
