package com.homura251.rule34downloader.work

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.download.*
import kotlinx.coroutines.CancellationException

/** The legacy worker name and input keys are retained so queued pre-upgrade work remains constructible. */
class ArtistSyncWorker(context: Context, parameters: WorkerParameters, private val runtime: BackgroundRuntime) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ARTIST_TAG)?.takeIf(String::isNotBlank) ?: return Result.failure()
        val notifications = DownloadNotifications(applicationContext)
        DownloadNotifications.createChannels(applicationContext)
        try {
            if (inputData.getBoolean(KEY_USER_INITIATED, false)) {
                try { setForeground(notifications.foregroundInfo(id, 0, 0)) }
                catch (e: IllegalStateException) {
                    if (Build.VERSION.SDK_INT < 31 || e.javaClass.name != "android.app.ForegroundServiceStartNotAllowedException") throw e
                }
            }
            return when (val outcome = runtime.execute(id, SyncObserver { done, total, post -> notifications.updateProgress(id, done, total, post) })) {
                is SyncOutcome.Retry -> { runtime.followUp(id, outcome.delayMillis); Result.success() }
                is SyncOutcome.Complete -> { notifications.showFinished(id, outcome.downloaded, outcome.failed); Result.success() }
                SyncOutcome.NeedsAuthentication -> { runtime.repository.suspendForAuthentication(); Result.success() }
                SyncOutcome.Skipped -> Result.success()
                SyncOutcome.Blocked -> Result.failure()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: SourceFailure) {
            if (e.kind == FailureKind.AUTH) { runtime.repository.suspendForAuthentication(); return Result.success() }
            return Result.failure()
        } finally { notifications.cancel(id) }
    }
    companion object {
        const val KEY_ARTIST_TAG = "artist_tag"
        const val KEY_USER_INITIATED = "user_initiated"
    }
}
