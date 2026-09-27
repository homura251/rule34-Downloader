package com.homura251.rule34downloader

import android.app.Application
import com.homura251.rule34downloader.work.DownloadNotifications
import com.homura251.rule34downloader.work.SyncScheduler

class Rule34Application : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.createChannels(this)
        SyncScheduler.ensurePeriodicSchedule(this)
    }
}
