package com.homura251.rule34downloader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.homura251.rule34downloader.data.AppPreferences
import com.homura251.rule34downloader.data.CredentialsStore
import com.homura251.rule34downloader.data.DownloadStatus
import com.homura251.rule34downloader.data.Rule34Database
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.work.SyncScheduler
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Activity, Compose actions, Chromium, WorkManager and MediaStore; controlled HTTP origin. */
@RunWith(AndroidJUnit4::class)
class AppEndToEndTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Rule34Database.getInstance(context)
    private val tag = "test_e2e_${UUID.randomUUID().toString().replace("-", "")}"
    private val server = MockWebServer()
    private val originals = AtomicInteger()
    private val lists = AtomicInteger()
    private lateinit var image: ByteArray
    private lateinit var hash: String
    private lateinit var network: Rule34Network
    private var previous: Rule34Network? = null
    private var uri: Uri? = null

    @Before fun prepareAnonymousOrigin() {
        CredentialsStore(context).clear()
        AppPreferences(context).apply { autoSyncEnabled = false; wifiOnly = false }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff42a5f5.toInt()) }
        image = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        hash = md5(image)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when {
                    url.encodedPath == "/images/42/$hash.png" -> {
                        originals.incrementAndGet()
                        MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image))
                    }
                    url.queryParameter("s") == "view" -> html("""
                        <title>Rule34 test</title><div id="header">Rule34</div>
                        <ul id="tag-sidebar"><li class="tag-type-artist"><a href="?page=post&amp;s=list&amp;tags=$tag">$tag</a> 1</li></ul>
                        <div id="options"><h5>Options</h5></div><script>setTimeout(() => {
                          document.getElementById('options').innerHTML += '<a href="https://wimg.rule34.xxx/images/42/$hash.png">Original image</a>';
                        }, 650);</script>
                    """.trimIndent())
                    url.queryParameter("s") == "list" -> {
                        lists.incrementAndGet()
                        val contents = if (url.queryParameter("tags").orEmpty().contains("id:>42")) "" else
                            "<span class='thumb' id='s42'><a href='?page=post&amp;s=view&amp;id=42'>42</a></span>"
                        html("<title>Rule34 test</title><div class='image-list'>$contents</div>")
                            .setHeadersDelay(1200, TimeUnit.MILLISECONDS)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val endpoint = server.url("/")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            network = Rule34Network(context, endpoint)
            previous = Rule34Network.exchangeForTests(network)
        }
    }

    @After fun cleanUp() {
        SyncScheduler.cancelArtistSync(context, tag)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!workFinished() && System.nanoTime() < deadline) Thread.sleep(50)
        uri?.let { context.contentResolver.delete(it, null, null) }
        // Also remove a published file if an assertion failed before its URI was recorded.
        database.getSavedRecords(tag).mapNotNull { it.localUri }.distinct().forEach {
            runCatching { context.contentResolver.delete(Uri.parse(it), null, null) }
        }
        database.removeArtist(tag)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (::network.isInitialized) { Rule34Network.exchangeForTests(previous); network.closeForTests() }
        }
        server.shutdown()
    }

    @Test fun anonymousUiCanPauseResumeDownloadPreviewAndReuseTheVerifiedFile() {
        ui.onNodeWithText("添加作者/图集").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("42")
        ui.onNodeWithText("继续").performClick()
        waitFor("添加并下载")
        ui.onNodeWithText("添加并下载").performClick()
        waitFor("暂停")
        ui.onNodeWithText("暂停").performClick()
        waitFor("继续")
        assertEquals(0, originals.get())
        ui.onNodeWithText("继续").performClick()

        ui.waitUntil(30_000) { database.getSavedRecords(tag).singleOrNull()?.status == DownloadStatus.DOWNLOADED && workFinished() }
        val record = database.getSavedRecords(tag).single()
        uri = Uri.parse(record.localUri!!)
        assertEquals(hash, record.verifiedMd5)
        assertEquals(image.size.toLong(), record.bytesDownloaded)
        context.contentResolver.openInputStream(uri!!)!!.use { assertArrayEquals(image, it.readBytes()) }
        context.contentResolver.query(uri!!, arrayOf(MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.SIZE), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(image.size.toLong(), it.getLong(1))
        }
        assertEquals(1, originals.get())

        waitFor("查看作品")
        ui.onNodeWithText("查看作品").performScrollTo().performClick()
        waitFor("画师作品 · 1 项")
        ui.onNodeWithText("#42").performClick()
        waitFor("#42 · 1/1")
        capture("anonymous-preview.png")
        ui.onNodeWithContentDescription("关闭预览").performClick()
        ui.onNodeWithContentDescription("返回").performClick()

        val before = lists.get()
        waitFor("同步")
        ui.onNodeWithText("同步").performScrollTo().performClick()
        ui.waitUntil(20_000) { lists.get() > before && workFinished() }
        assertEquals(record.localUri, database.getSavedRecords(tag).single().localUri)
        assertEquals(1, originals.get())
        capture("anonymous-reuse.png")
        File(context.getExternalFilesDir(null), "e2e/result.txt").writeText(
            "PASS: anonymous UI -> pause/resume -> Chromium original parser -> WorkManager -> MediaStore -> preview -> resync reuse\n" +
                "Origin: controlled MockWebServer (not the live site)\nBytes: ${image.size}\nMD5: $hash\nOriginal requests: ${originals.get()}\n",
        )
    }

    private fun waitFor(text: String) = ui.waitUntil(20_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun workFinished() = WorkManager.getInstance(context).getWorkInfosForUniqueWork("rule34-sync-$tag")
        .get(5, TimeUnit.SECONDS).all { it.state.isFinished }
    private fun capture(name: String) {
        ui.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("No screenshot")
        val file = File(context.getExternalFilesDir(null), "e2e/$name").also { it.parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }
    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
