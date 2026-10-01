package com.homura251.rule34downloader.work

import android.content.Context
import com.homura251.rule34downloader.data.CredentialsStore
import com.homura251.rule34downloader.network.Rule34Client
import com.homura251.rule34downloader.network.Rule34HtmlClient
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.storage.MediaStoreDownloader

/** Worker dependencies can be supplied by an instrumentation WorkerFactory. */
internal fun interface SyncServicesFactory {
    fun create(context: Context, control: SyncControl): SyncServices
}

internal data class SyncServices(
    val html: Rule34HtmlClient,
    val downloader: MediaStoreDownloader,
    val api: Rule34Client? = null,
    val pageDelayMs: Long = if (api == null) 1_000L else 250L,
    val detailDelayMs: Long = if (api == null) 750L else 250L,
)

internal object ProductionSyncServices : SyncServicesFactory {
    override fun create(context: Context, control: SyncControl): SyncServices {
        val network = Rule34Network.get(context)
        val http = network.client.newBuilder().addInterceptor(control.interceptor).build()
        val api = CredentialsStore(context).get()?.let {
            Rule34Client(it, control::checkActive) { connection -> control.register(connection::disconnect) }
        }
        return SyncServices(network.htmlClient(http, control::checkActive),
            MediaStoreDownloader(context, http, control::checkActive, control::register), api)
    }
}
