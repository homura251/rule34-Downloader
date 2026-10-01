package com.homura251.rule34downloader

import android.graphics.Bitmap
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Actual HTTP requests, unique PNGs and strict Rule34 HTML; no success is synthesized. */
internal class BulkOrigin(val tag: String, val count: Int, checkpoint: Boolean) : AutoCloseable {
    val originals = ConcurrentHashMap<Long, AtomicInteger>()
    val details = ConcurrentHashMap<Long, AtomicInteger>()
    val lists = AtomicInteger()
    val mediaPauseId = AtomicLong(if (checkpoint) count.toLong() else -1)
    val detailPauseId = AtomicLong(if (checkpoint) count.toLong() - 2 else -1)
    private val delayedBodyDeadline = AtomicLong(0)
    val server = MockWebServer()
    val bytes: Map<Long, ByteArray> = (1L..count.toLong()).associateWith { id ->
        val large = id == count.toLong()
        val image = Bitmap.createBitmap(if (large) 1024 else 2, if (large) 768 else 2, Bitmap.Config.ARGB_8888)
        if (large) {
            val random = java.util.Random(id)
            val pixels = IntArray(1024 * 768) { 0xff000000.toInt() or random.nextInt(0x1000000) }
            image.setPixels(pixels, 0, 1024, 0, 0, 1024, 768)
        } else image.eraseColor(0xff000000.toInt() or ((id * 1993).toInt() and 0xffffff))
        ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { image.recycle() }
    }
    val hashes = bytes.mapValues { md5(it.value) }
    val endpoint: HttpUrl
    val client: OkHttpClient

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath.startsWith("/images/")) {
                    val id = url.encodedPath.split('/').getOrNull(2)?.toLongOrNull() ?: return MockResponse().setResponseCode(404)
                    val data = bytes[id] ?: return MockResponse().setResponseCode(404)
                    originals.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(data)).also {
                        if (mediaPauseId.compareAndSet(id, -1)) it.throttleBody(1, 500, TimeUnit.MILLISECONDS)
                    }
                }
                if (url.queryParameter("s") == "view") {
                    val id = url.queryParameter("id")!!.toLong()
                    details.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                    val original = "https://wimg.rule34.xxx/images/$id/" + hashes.getValue(id) + ".png"
                    return html("<title>Rule34 fixture</title><ul id='tag-sidebar'><li class='tag-type-artist'>" +
                        "<a href='?page=post&amp;s=list&amp;tags=$tag'>$tag</a> $count</li></ul>" +
                        "<div id='options'><a href='$original'>Original image</a></div>").also {
                        if (detailPauseId.compareAndSet(id, -1)) {
                            delayedBodyDeadline.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(21))
                            it.setBodyDelay(20, TimeUnit.SECONDS)
                        }
                    }
                }
                if (url.queryParameter("s") == "list") {
                    lists.incrementAndGet()
                    val tags = url.queryParameter("tags").orEmpty()
                    val lower = Regex("id:>([0-9]+)").find(tags)?.groupValues?.get(1)?.toLong() ?: 0L
                    val upper = Regex("id:<([0-9]+)").find(tags)?.groupValues?.get(1)?.toLong() ?: Long.MAX_VALUE
                    check(!(tags.contains("id:>") && tags.contains("id:<")))
                    check(url.queryParameter("pid") == "0")
                    val ids = (1L..count.toLong()).filter { it > lower && it < upper }.reversed().take(42)
                    return html("<title>Rule34 fixture</title><div class='image-list'>" + ids.joinToString("") {
                        "<span class='thumb' id='s$it'><a href='?page=post&amp;s=view&amp;id=$it'>$it</a></span>"
                    } + "</div>")
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start(java.net.InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
        endpoint = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).build()
        client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).addInterceptor { chain ->
            val original = chain.request().url
            val target = original.newBuilder().scheme(endpoint.scheme).host(endpoint.host).port(endpoint.port).build()
            chain.proceed(chain.request().newBuilder().url(target).build())
        }.build()
    }

    fun localAddress(url: String): String {
        val source = url.toHttpUrl()
        return source.newBuilder().scheme(endpoint.scheme).host(endpoint.host).port(endpoint.port).build().toString()
    }
    fun originalCount(id: Long) = originals[id]?.get() ?: 0
    fun detailCount(id: Long) = details[id]?.get() ?: 0
    fun totalOriginals() = originals.values.sumOf { it.get() }
    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        // MockWebServer body-delay sleeps are not interrupted by a client cancel.
        // Drain this deliberate delay before its five-second shutdown assertion.
        val remaining = delayedBodyDeadline.get() - System.nanoTime()
        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
        server.shutdown()
    }
    companion object {
        fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
