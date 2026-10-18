package com.homura251.rule34downloader

import android.app.Application
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import com.homura251.rule34downloader.work.DownloadNotifications
import com.homura251.rule34downloader.work.BackgroundRuntimeOwner
import com.homura251.rule34downloader.work.DownloaderWorkerFactory

class Rule34Application : Application(), ImageLoaderFactory, Configuration.Provider, BackgroundRuntimeOwner {
    private var overrideGraph: AppGraph? = null
    private val defaultGraph by lazy { createDefaultGraph(this) }
    val graph: AppGraph get() = overrideGraph ?: defaultGraph
    override val backgroundRuntime get() = graph.background
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(DownloaderWorkerFactory { backgroundRuntime }).build()
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { graph.mediaHttp }
        .components { add(ImageDecoderDecoder.Factory()) }
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.createChannels(this)
        graph.start()
    }
    fun installGraphForTests(value: AppGraph) {
        check(BuildConfig.DEBUG)
        overrideGraph?.close(); overrideGraph = value; value.start()
    }
}
