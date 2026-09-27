package com.homura251.rule34downloader.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.homura251.rule34downloader.MainActivity
import com.homura251.rule34downloader.R

class DownloadNotifications(
    private val context: Context,
) {
    fun foregroundInfo(
        artistTag: String,
        completed: Int,
        total: Int,
        currentPostId: Long? = null,
    ): ForegroundInfo {
        val notification = progressBuilder(artistTag, completed, total, currentPostId).build()
        return ForegroundInfo(
            notificationId(artistTag),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    fun updateProgress(
        artistTag: String,
        completed: Int,
        total: Int,
        currentPostId: Long?,
    ) {
        if (!canPostNotifications()) return
        NotificationManagerCompat.from(context).notify(
            notificationId(artistTag),
            progressBuilder(artistTag, completed, total, currentPostId).build(),
        )
    }

    fun showFinished(artistTag: String, downloaded: Int, failed: Int) {
        if (!canPostNotifications()) return
        val text = when {
            failed > 0 -> "新增 $downloaded 个文件，$failed 个下载失败"
            downloaded > 0 -> "新增 $downloaded 个原文件"
            else -> "没有发现新作品"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("$artistTag 同步完成")
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(artistTag), notification)
    }

    private fun progressBuilder(
        artistTag: String,
        completed: Int,
        total: Int,
        currentPostId: Long?,
    ): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
        .setSmallIcon(R.drawable.ic_download_notification)
        .setContentTitle("正在同步 $artistTag")
        .setContentText(
            currentPostId?.let { "原文件 #$it · ${completed.coerceAtMost(total)}/$total" }
                ?: "检查新作品…",
        )
        .setContentIntent(contentIntent())
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setProgress(total.coerceAtLeast(1), completed.coerceAtMost(total), total <= 0)

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationId(artistTag: String): Int =
        10_000 + (artistTag.hashCode() and 0x7fffffff) % 20_000

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                context.getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_downloads_description)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
