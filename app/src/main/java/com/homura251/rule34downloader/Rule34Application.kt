package com.homura251.rule34downloader

import android.app.Application
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.work.DownloadNotifications
import com.homura251.rule34downloader.work.SyncScheduler
import com.homura251.rule34downloader.work.DownloaderWorkerFactory

class Rule34Application : Application(), ImageLoaderFactory, Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(DownloaderWorkerFactory).build()
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Rule34Network.get(this).client }
        .components { add(ImageDecoderDecoder.Factory()) }
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.createChannels(this)
        SyncScheduler.ensurePeriodicSchedule(this)
    }
}
