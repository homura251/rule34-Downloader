package com.homura251.rule34downloader

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homura251.rule34downloader.data.DownloadRecord
import com.homura251.rule34downloader.data.DownloadStatus
import com.homura251.rule34downloader.data.Rule34Database
import com.homura251.rule34downloader.network.BrowserMediaReader
import com.homura251.rule34downloader.network.BrowserPageReader
import com.homura251.rule34downloader.network.CloudflareChallengeException
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.network.RetryableApiException
import com.homura251.rule34downloader.storage.ExistingDownloads
import com.homura251.rule34downloader.storage.MediaStoreDownloader
import com.homura251.rule34downloader.storage.VerifiedTransfer
import com.homura251.rule34downloader.work.SyncControl
import com.homura251.rule34downloader.work.SyncPausedException
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadReliabilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var network: Rule34Network
    private val local: (String) -> Boolean = { url ->
        url.toHttpUrlOrNull()?.host in setOf("localhost", "127.0.0.1")
    }

    @Before fun prepareWebView() {
        val cleared = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            network = Rule34Network.get(context)
            CookieManager.getInstance().removeAllCookies { cleared.countDown() }
        }
        assertTrue(cleared.await(10, TimeUnit.SECONDS))
    }

    @Test fun readsJavascriptRenderedPageWithUnchangedClearance() {
        MockWebServer().use { server ->
            val url = server.url("/index.php?page=post&s=view&id=42").toString()
            val stored = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                CookieManager.getInstance().setCookie(url, "cf_clearance=unchanged; Path=/") { stored.countDown() }
            }
            assertTrue(stored.await(10, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("""
                <title>Just a moment...</title><form id="challenge-form"></form>
                <script>setTimeout(() => {
                  document.title='Rule34';
                  document.body.innerHTML='<div id="tag-sidebar"></div><div class="link-list"><a href="/original.jpg">Original image</a></div>';
                }, 300);</script>
            """.trimIndent()))
            val document = pages().read(url)
            assertNotNull(document.selectFirst(".link-list a"))
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie").orEmpty().contains("cf_clearance=unchanged"))
            assertTrue(CookieManager.getInstance().getCookie(url).contains("cf_clearance=unchanged"))
        }
    }

    @Test fun readsAnUnprotectedPageWithoutAnyClearanceCookie() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<div class='image-list'></div>"))
            assertNotNull(pages().read(server.url("/list").toString()).selectFirst(".image-list"))
        }
    }

    @Test fun ordinaryRateLimitsDoNotAskForCloudflareVerification() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type", "text/html").setBody("<p>Too many requests</p>"))
            assertThrows(RetryableApiException::class.java) { pages().read(server.url("/limit").toString()) }
        }
    }

    @Test fun readsDownloadableMediaResponsesWithoutStartingAnotherDownload() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Disposition", "attachment; filename=original.bin").setBody("hello"))
            val url = server.url("/original.bin").toString()
            assertEquals(url, pages().read(url).baseUri())
        }
    }

    @Test fun changingTheCookieDoesNotCompleteAnUnreadableChallenge() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
                .setHeader("Set-Cookie", "cf_clearance=new-but-blocked; Path=/")
                .setBody("<title>Just a moment...</title><form id='challenge-form'></form>"))
            assertThrows(CloudflareChallengeException::class.java) {
                pages(1_500).read(server.url("/challenge").toString())
            }
        }
    }

    @Test fun chromiumStreamsLargeChunkedOriginalWithoutBufferingTheWholeFile() {
        val bytes = ByteArray(512 * 1024 + 7) { (it % 251).toByte() }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
                .setChunkedBody(Buffer().write(bytes), 8192))
            media().open(server.url("/original.bin").toString()).use { source ->
                assertEquals(-1L, source.contentLength)
                val output = ByteArrayOutputStream()
                assertEquals(bytes.size.toLong(), VerifiedTransfer.copy(source.input, output, md5(bytes), source.contentLength).bytes)
                assertArrayEquals(bytes, output.toByteArray())
            }
        }
    }

    @Test fun chromiumRejectsHtmlInsteadOfSavingItAsAnImage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<title>Just a moment...</title>"))
            assertThrows(CloudflareChallengeException::class.java) {
                media().open(server.url("/original.jpg").toString()).close()
            }
        }
    }

    @Test fun pauseCancelsAnAlreadyStreamingWebViewTransfer() {
        val control = SyncControl()
        val firstChunk = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        MockWebServer().use { server ->
            val bytes = ByteArray(16 * 1024) { 42 }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(bytes)).throttleBody(1024, 500, TimeUnit.MILLISECONDS))
            try {
                val result = executor.submit<Throwable?> {
                    try {
                        media().open(server.url("/slow.bin").toString(), control::checkActive, control::register).use {
                            VerifiedTransfer.copy(it.input, ByteArrayOutputStream(), md5(bytes), it.contentLength,
                                control::checkActive) { firstChunk.countDown() }
                        }
                        null
                    } catch (e: Exception) { e }
                }
                assertTrue(firstChunk.await(20, TimeUnit.SECONDS))
                control.pause()
                assertTrue(result.get(5, TimeUnit.SECONDS) is SyncPausedException)
            } finally { control.pause(); executor.shutdownNow() }
        }
    }

    @Test fun mediaStorePublishesOnlyVerifiedFilesAndRejectsTruncatedOldFiles() {
        val tag = "test_${UUID.randomUUID()}"
        val client = OkHttpClient()
        MockWebServer().use { server ->
            val bytes = "hello".toByteArray()
            val hash = md5(bytes)
            val record = DownloadRecord(tag, 42, server.url("/$hash.bin").toString(), hash, DownloadStatus.PENDING, 0, 0)
            val downloader = MediaStoreDownloader(context, client)
            try {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("wrong"))
                assertThrows(IOException::class.java) { downloader.download(record, {}, { _, _ -> }) }
                assertEquals(0, fileCount(tag))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(bytes)))
                val result = downloader.download(record, {}, { _, _ -> })
                assertEquals(5L, result.bytesWritten)
                assertEquals(hash, result.verifiedMd5)
                assertEquals(1, fileCount(tag))
                context.contentResolver.query(result.uri, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null)!!.use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                val saved = record.copy(status = DownloadStatus.DOWNLOADED, localUri = result.uri.toString(), bytesDownloaded = 5, verifiedMd5 = hash)
                assertNotNull(ExistingDownloads(context, tag) {}.find(saved) {})
                context.contentResolver.openOutputStream(result.uri, "wt")!!.use { it.write("hel".toByteArray()) }
                assertNull(ExistingDownloads(context, tag) {}.find(saved) {})
            } finally { deleteFiles(tag); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun crashCleanupLeavesPublishedAndOtherArtistFilesAlone() {
        val tag = "test_${UUID.randomUUID()}"
        val other = tag + "_other"
        fun insert(folder: String, pending: Int) = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "42.bin")
                put(MediaStore.Downloads.RELATIVE_PATH, MediaStoreDownloader.buildRelativePath(folder))
                put(MediaStore.Downloads.IS_PENDING, pending)
            })!!
        try {
            insert(tag, 1); insert(tag, 0); insert(other, 1)
            assertEquals(2, fileCount(tag)); assertEquals(1, fileCount(other))
            MediaStoreDownloader(context).cleanInterruptedFiles(tag)
            assertEquals(1, fileCount(tag)); assertEquals(1, fileCount(other))
        } finally { deleteFiles(tag); deleteFiles(other) }
    }

    @Test fun upgradingRechecksOldCompletionsAndPreservesTheirUris() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE downloads(status TEXT, local_uri TEXT)")
            db.execSQL("INSERT INTO downloads VALUES ('DOWNLOADED', 'content://old/file')")
            Rule34Database.getInstance(context).onUpgrade(db, 4, 5)
            db.rawQuery("SELECT status, local_uri, verified_md5 FROM downloads", null).use {
                assertTrue(it.moveToFirst()); assertEquals("PENDING", it.getString(0))
                assertEquals("content://old/file", it.getString(1)); assertTrue(it.isNull(2))
            }
        }
    }

    private fun pages(timeout: Long = 10_000) = BrowserPageReader(context, network::createWebView, local, timeout)
    private fun media() = BrowserMediaReader(context, network::createWebView, local)
    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fileCount(tag: String): Int = MediaStoreDownloader.queryIncludingPending(context.contentResolver,
        "relative_path = ?", arrayOf(MediaStoreDownloader.buildRelativePath(tag)))!!.use { it.count }
    private fun deleteFiles(tag: String) {
        MediaStoreDownloader.queryIncludingPending(context.contentResolver,
            "relative_path = ?", arrayOf(MediaStoreDownloader.buildRelativePath(tag)))?.use {
            while (it.moveToNext()) context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)), null, null)
        }
    }
}
