package com.homura251.rule34downloader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ApiAppEndToEndTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Rule34Application>()
    private val graph get() = app.graph
    private val origin get() = ApiTestEnvironment.origin
    private fun <T> read(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @Test fun optionalCredentialsThreeTabsPauseResumeAndLocalLibrary() {
        grantNotificationsForTest()
        wait { graph.startup.value.status == StartupStatus.READY }
        read {
            for (collection in graph.library.getCollections()) graph.background.remove(collection.tag)
            graph.account.clear(); graph.background.authenticationChanged()
        }
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("add-author").fetchSemanticsNodes().isNotEmpty() }
        val pages = origin.lists.get()
        ui.onNodeWithTag("add-author").performClick()
        ui.onNodeWithTag("author-input").performTextInput(origin.tag)
        ui.onNodeWithText("添加", substring = false).performClick()
        ui.waitUntil(30_000) {
            ui.onAllNodesWithTag("author-input").fetchSemanticsNodes().isEmpty() &&
                ui.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isNotEmpty()
        }
        wait { read { graph.library.getCollection(origin.tag) } != null }
        assertEquals(pages, origin.lists.get())
        ui.onNodeWithTag("nav-settings").performClick()
        origin.mediaPauseId.set(1)
        ui.onNodeWithTag("api-user-id").performTextInput("42")
        ui.onNodeWithTag("api-key").performTextInput("fixture-key")
        ui.onNodeWithTag("verify-api").performScrollTo().performClick()
        wait { read { graph.library.records(origin.tag) }.any { it.postId == 1L && it.bytesDownloaded > 0 } }
        ui.onNodeWithContentDescription("返回").performClick()
        ui.waitForIdle()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.waitForIdle()
        ui.onNodeWithContentDescription("${origin.tag} 更多操作").performClick()
        ui.onNodeWithTag("sync-${origin.tag}").performClick()
        wait { read { graph.library.getCollection(origin.tag) }?.syncState == SyncState.PAUSED }
        assertEquals(3L, read { graph.library.getCollection(origin.tag) }!!.lastSeenPostId)
        capture("api-paused.png")
        ui.onNodeWithTag("nav-tasks").performClick()
        ui.onNodeWithTag("task-filter-已暂停").performClick()
        ui.onNodeWithTag("task-sync-${origin.tag}").performScrollTo().performClick()
        wait(90_000) { read { graph.library.records(origin.tag) }.count { it.status == DownloadStatus.DOWNLOADED } == 3 }
        val saved = read { graph.library.records(origin.tag) }
        for (record in saved) {
            val bytes = app.contentResolver.openInputStream(Uri.parse(record.localUri!!))!!.use { it.readBytes() }
            assertEquals(origin.hashes.getValue(record.postId), TestApiOrigin.md5(bytes))
            assertNull(record.verifiedMd5)
        }
        assertEquals(0, origin.authenticationLeaks.get())
        ui.onNodeWithTag("nav-gallery").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("3 件作品", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("api-gallery.png")
        read { graph.account.clear(); graph.background.authenticationChanged() }
        assertEquals(saved.map { it.localUri }, read { graph.library.records(origin.tag) }.map { it.localUri })
        ui.onNodeWithTag("gallery-post-${origin.tag}-3").performClick()
        ui.onNodeWithContentDescription("关闭预览").assertExists().performClick()
        writeTestText(app, "controlled", "api-ui-result.txt",
            "PASS: optional credentials, direct author addition, three tabs, UI pause/resume, real MediaStore files, no hash claims, local preview after clearing credentials; authentication leaks=0")
    }
    private fun wait(timeout: Long = 30_000, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (!predicate()) { check(System.nanoTime() < until) { "Timed out waiting for actual persisted state" }; Thread.sleep(50) }
    }
    private fun capture(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        writeTestEvidence(app, "controlled", name, "image/png") { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}
