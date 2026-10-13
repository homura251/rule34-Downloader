package com.homura251.rule34downloader

import android.graphics.Bitmap
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.mockwebserver.Dispatcher
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*

/** HTTP API fixture and real, distinct PNG originals. Thumbnails never count as original transfers. */
internal open class TestApiOrigin(val tag: String, val count: Int, largeFirst: Boolean = true) : AutoCloseable {
    val originals = ConcurrentHashMap<Long, AtomicInteger>()
    val lists = AtomicInteger()
    val authenticationLeaks = AtomicInteger()
    val mediaPauseId = AtomicLong(-1)
    val pagePauseBefore = AtomicLong(-1)
    val delayedPages = AtomicInteger()
    private val delayDeadline = AtomicLong(0)
    val server = MockWebServer()
    val bytes = (1L..count.toLong()).associateWith { id ->
        val large = largeFirst && id == 1L
        val image = Bitmap.createBitmap(if (large) 1024 else 2, if (large) 768 else 2, Bitmap.Config.ARGB_8888)
        if (large) {
            val random = java.util.Random(id)
            image.setPixels(IntArray(1024 * 768) { 0xff000000.toInt() or random.nextInt(0x1000000) }, 0, 1024, 0, 0, 1024, 768)
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
                if (url.encodedPath.startsWith("/images/") || url.encodedPath.startsWith("/previews/")) {
                    if (url.queryParameter("api_key") != null || request.getHeader("Authorization") != null) authenticationLeaks.incrementAndGet()
                    val id = url.encodedPath.split('/').getOrNull(2)?.toLongOrNull() ?: return MockResponse().setResponseCode(404)
                    val original = url.encodedPath.startsWith("/images/")
                    val payload = if (original) bytes[id] else bytes[if (count > 1) 2 else 1]
                    if (payload == null) return MockResponse().setResponseCode(404)
                    if (original) originals.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(payload)).also {
                        if (original && mediaPauseId.compareAndSet(id, -1)) it.throttleBody(1, 500, TimeUnit.MILLISECONDS)
                    }
                }
                if (url.queryParameter("page") != "dapi" || url.queryParameter("q") != "index") return MockResponse().setResponseCode(404)
                if (url.queryParameter("user_id") != "42" || url.queryParameter("api_key") != "fixture-key") return MockResponse().setResponseCode(401)
                if (url.queryParameter("s") == "tag") return json(JSONArray().put(JSONObject().put("name", tag).put("type", 1).put("count", count)).toString())
                val id = url.queryParameter("id")?.toLongOrNull()
                if (id != null) return json(JSONArray().apply { if (id in 1L..count.toLong()) put(post(id)) }.toString())
                val tags = url.queryParameter("tags").orEmpty()
                if (tags.isBlank()) return json("[]") // Low-cost credential validation, without a collection scan.
                lists.incrementAndGet()
                val artist = tags.substringBefore(' ')
                val artistCount = if (count > 200 && artist != tag &&
                    (artist == tag.replace("test_bulk_", "test_bulk_api_") || artist == tag.replace("test_bulk_", "test_bulk_crash_"))) 84 else count
                val lower = Regex("id:>([0-9]+)").find(tags)?.groupValues?.get(1)?.toLong() ?: 0
                val upper = Regex("id:<([0-9]+)").find(tags)?.groupValues?.get(1)?.toLong() ?: Long.MAX_VALUE
                check(!(tags.contains("id:>") && tags.contains("id:<")))
                check(url.queryParameter("pid") == "0")
                val limit = url.queryParameter("limit")!!.toInt()
                return json(JSONArray().apply { (1L..artistCount.toLong()).reversed().filter { it > lower && it < upper }.take(limit).forEach { put(post(it, artist)) } }.toString()).also {
                    if (pagePauseBefore.compareAndSet(upper, -1)) {
                        delayedPages.incrementAndGet(); delayDeadline.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(21))
                        it.setBodyDelay(20, TimeUnit.SECONDS)
                    }
                }
            }
        }
        server.start(InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
        endpoint = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).addPathSegment("index.php").build()
        client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).addInterceptor { chain ->
            val url = chain.request().url
            check(url.host in setOf("127.0.0.1", "wimg.rule34.xxx")) { "Fixture refused an unexpected host" }
            chain.proceed(chain.request().newBuilder().url(url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()).build())
        }.build()
    }
    fun post(id: Long, artist: String = tag) = JSONObject().put("id", id).put("file_url", "https://wimg.rule34.xxx/images/$id/${hashes.getValue(id)}.png")
        .put("preview_url", "https://wimg.rule34.xxx/previews/$id/thumb.png").put("md5", hashes.getValue(id)).put("tags", artist)
        .put("tag_info", JSONObject().put(artist, 1)).put("width", 2).put("height", 2)
    fun originalCount(id: Long) = originals[id]?.get() ?: 0
    fun totalOriginals() = originals.values.sumOf { it.get() }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    override fun close() {
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        val remaining = delayDeadline.get() - System.nanoTime()
        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
        server.shutdown()
    }
    companion object { fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } }
}
