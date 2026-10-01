package com.homura251.rule34downloader

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.homura251.rule34downloader.network.BrowserPageReader
import com.homura251.rule34downloader.network.Rule34HtmlClient
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.storage.MediaStoreDownloader
import com.homura251.rule34downloader.work.DownloaderWorkerFactory
import com.homura251.rule34downloader.work.SyncServices
import com.homura251.rule34downloader.work.SyncServicesFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Configure the real worker factory before Application.onCreate can reschedule crashed work. */
class BulkAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        Thread {
            try { BulkTestEnvironment.configure(arguments) } catch (error: Throwable) { failure = error }
            finally { ready.countDown() }
        }.start()
        check(ready.await(30, TimeUnit.SECONDS)) { "Bulk fixture did not start" }
        failure?.let { throw it }
        super.onCreate(arguments)
    }
}

internal object BulkTestEnvironment {
    lateinit var origin: BulkOrigin
    val readers = ConcurrentLinkedQueue<BrowserPageReader>()
    fun configure(arguments: Bundle) {
        val phase = arguments.getString("bulkPhase") ?: "browser"
        val count = arguments.getString("bulkCount")?.toInt() ?: 84
        val key = arguments.getString("bulkKey") ?: "manual"
        val prefix = when (phase) { "browser" -> "browser_"; "crash", "recover" -> "crash_"; else -> "" }
        origin = BulkOrigin("test_bulk_" + prefix + key, count, phase in setOf("browser", "checkpoint", "crash"))
        DownloaderWorkerFactory.setServicesForTests(SyncServicesFactory { app, control ->
            val http = origin.client.newBuilder().addInterceptor(control.interceptor).build()
            val browser = if (phase == "browser") BrowserPageReader(app,
                { context -> Rule34Network.get(context).createWebView(context) },
                { it.toHttpUrlOrNull()?.host == origin.endpoint.host }).also { readers += it } else null
            val html = if (browser == null) Rule34HtmlClient(http, control::checkActive) else
                Rule34HtmlClient(http, control::checkActive) { url, active ->
                    browser.read(origin.localAddress(url), active).also { it.setBaseUri(url) }
                }
            SyncServices(html, MediaStoreDownloader(app, http, control::checkActive, control::register),
                pageDelayMs = 0, detailDelayMs = 0)
        })
    }
}
