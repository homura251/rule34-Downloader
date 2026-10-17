package com.homura251.rule34downloader

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Only compiled into the separately identified Maestro APK; never in debug/release downloads. */
internal fun createDefaultGraph(context: Context): AppGraph {
    val endpoint = "http://127.0.0.1:18734/index.php".toHttpUrl()
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url
        check(url.host in setOf("127.0.0.1", "wimg.rule34.xxx"))
        val local = url.newBuilder().scheme("http").host(endpoint.host).port(endpoint.port).build()
        chain.proceed(chain.request().newBuilder().url(local).build())
    }.build()
    return AppGraph(context, client, client, endpoint)
}
