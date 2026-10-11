package com.homura251.rule34downloader.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

class DownloaderWorkerFactory(private val runtime: () -> BackgroundRuntime) : WorkerFactory() {
    override fun createWorker(context: Context, className: String, parameters: WorkerParameters): ListenableWorker? = when (className) {
        ArtistSyncWorker::class.java.name -> ArtistSyncWorker(context, parameters, runtime())
        AutoSyncWorker::class.java.name -> AutoSyncWorker(context, parameters, runtime())
        else -> null
    }
}
