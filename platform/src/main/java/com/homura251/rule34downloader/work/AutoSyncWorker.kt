package com.homura251.rule34downloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AutoSyncWorker(context: Context, parameters: WorkerParameters, private val runtime: BackgroundRuntime) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result { runtime.automaticSync(); return Result.success() }
}
