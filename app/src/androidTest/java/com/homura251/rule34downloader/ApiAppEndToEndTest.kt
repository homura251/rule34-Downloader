package com.homura251.rule34downloader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice
import androidx.work.WorkManager
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

    @Test fun credentialsGateFourTabsPauseResumeAndLocalLibrary() {
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("api-user-id").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, origin.lists.get())
        assertEquals(0, origin.totalOriginals())
        ui.onNodeWithTag("api-user-id").performTextInput("42")
        ui.onNodeWithTag("api-key").performTextInput("fixture-key")
        ui.onNodeWithTag("verify-api").performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("add-author").fetchSemanticsNodes().isNotEmpty() }
        origin.mediaPauseId.set(1)
        ui.onNodeWithTag("add-author").performClick()
        ui.onNodeWithTag("author-input").performTextInput(origin.tag)
        ui.onNodeWithText("继续", substring = false).performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("订阅并同步").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("订阅并同步").performClick()
        // Exercise the actual Android notification permission prompt before returning to Compose.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val allow = device.wait(Until.findObject(By.res("com.google.android.permissioncontroller", "permission_allow_button")), 10_000)
            ?: device.wait(Until.findObject(By.text("Allow")), 2_000)
            ?: error("Notification permission prompt did not appear")
        allow.click()
        wait { read { graph.library.records(origin.tag) }.any { it.postId == 1L && it.bytesDownloaded > 0 } }
        ui.onNodeWithTag("sync-${origin.tag}").performScrollTo().performClick()
        wait { read { graph.library.getCollection(origin.tag) }?.syncState == SyncState.PAUSED }
        assertEquals(3L, read { graph.library.getCollection(origin.tag) }!!.lastSeenPostId)
        assertTrue(read { graph.library.getCollection(origin.tag) }!!.paused)
        capture("api-paused.png")
        ui.onNodeWithTag("nav-tasks").performClick()
        ui.onNodeWithText("继续暂停任务").performClick()
        wait(90_000) { read { graph.library.records(origin.tag) }.count { it.status == DownloadStatus.DOWNLOADED } == 3 }
        wait { WorkManager.getInstance(app).getWorkInfosForUniqueWork("rule34-sync-${origin.tag}").get(10, TimeUnit.SECONDS).all { it.state.isFinished } }
        val saved = read { graph.library.records(origin.tag) }
        for (record in saved) {
            val bytes = app.contentResolver.openInputStream(Uri.parse(record.localUri!!))!!.use { it.readBytes() }
            assertEquals(origin.hashes.getValue(record.postId), TestApiOrigin.md5(bytes))
        }
        assertEquals(4, origin.totalOriginals()) // The interrupted first file restarts; completed originals do not.
        assertEquals(0, origin.authenticationLeaks.get())
        ui.onNodeWithTag("nav-gallery").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("3 件作品", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("api-gallery.png")
        // Clearing the account keeps downloaded files and the local, paged gallery usable.
        read { graph.account.clear(); graph.background.authenticationChanged() }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("连接 API 后开始同步").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(saved.map { it.localUri }, read { graph.library.records(origin.tag) }.map { it.localUri })
        ui.onNodeWithTag("nav-subscriptions").performClick()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("sync-${origin.tag}") and hasText("暂停")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("sync-${origin.tag}").performScrollTo().performClick()
        wait { read { graph.library.getCollection(origin.tag) }?.syncState == SyncState.PAUSED }
        ui.onNodeWithTag("nav-settings").performClick()
        ui.onNodeWithTag("verify-api").assertExists()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("api-user-id").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("api-user-id").performTextReplacement("42")
        ui.onNodeWithTag("api-key").performTextInput("fixture-key")
        ui.onNodeWithTag("verify-api").performScrollTo().performClick()
        wait { graph.account.state.value.canUseApi }
        assertEquals(SyncState.PAUSED, read { graph.library.getCollection(origin.tag) }!!.syncState)
        ui.onNodeWithTag("nav-tasks").performClick()
        ui.onNodeWithText("继续暂停任务").performClick()
        wait { read { graph.library.getCollection(origin.tag) }?.syncState == SyncState.COMPLETE }
        assertEquals(4, origin.totalOriginals())
        assertEquals(saved.map { it.localUri }, read { graph.library.records(origin.tag) }.map { it.localUri })
        writeTestText(app, "controlled", "api-ui-result.txt", "PASS: credential gate, real API and media HTTP, four tabs, pause/resume, readback MD5, local gallery and pause after clearing credentials, pause intent preserved after revalidation, manual resume reused saved files; originals=4; authentication leaks=0")
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
