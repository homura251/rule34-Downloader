package com.homura251.rule34downloader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecoveryUiEndToEndTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Rule34Application>()
    private val graph get() = app.graph
    private val origin get() = ApiTestEnvironment.origin
    private val tag = "test_recovery_controlled"
    private fun <T> read(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @Test fun scanFailureDrainsHealthyFilesAndUiRecoveryWorksWithoutApiCredentials() {
        wait { graph.startup.value.status == StartupStatus.READY }
        read {
            graph.account.save(ApiCredentials("42", "fixture-key"))
            graph.background.authenticationChanged()
            for (collection in graph.library.getCollections()) graph.background.remove(collection.tag)
            graph.settings.save(graph.settings.snapshot().copy(existingDownloadsTreeUri = null))
            graph.library.addCollection(tag, 1)
            assertTrue(graph.library.beginRun(tag, "seed"))
            val posts = (1L..3L).map { graph.catalog.getPost(it) }
            graph.library.savePage(tag, "seed", posts, ScanCheckpoint(0, 1, 3))
            graph.library.endRun(tag, "seed")
        }
        try {
            origin.brokenLists.add(tag)
            origin.forbiddenOriginals.add(1)
            read { graph.background.sync(tag) }
            wait {
                read { graph.library.getCollection(tag) }?.syncState == SyncState.WAITING_RETRY &&
                    read { graph.library.records(tag) }.count { it.status == DownloadStatus.DOWNLOADED } == 2
            }
            val healthy = read { graph.library.records(tag) }.filter { it.postId != 1L }.associate { it.postId to it.localUri }
            assertEquals(0L, read { graph.library.getCollection(tag) }!!.lastSeenPostId)
            assertNotNull(read { graph.library.checkpoint(tag) })
            read { graph.account.clear(); graph.background.authenticationChanged() }
            origin.forbiddenOriginals.remove(1)
            val pages = origin.lists.get()
            val transfers = origin.totalOriginals()
            origin.mediaPauseId.set(1)
            ui.onNodeWithTag("nav-tasks").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("download-known-$tag").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("download-known-$tag").performScrollTo().assertIsEnabled().performClick()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wait(
                Until.findObject(By.res("com.google.android.permissioncontroller", "permission_allow_button")), 2_000)?.click()
            wait { read { graph.library.records(tag) }.any { it.postId == 1L && it.bytesDownloaded > 0 } }
            ui.onNodeWithTag("task-sync-$tag").performScrollTo().performClick()
            wait { read { graph.library.getCollection(tag) }?.syncState == SyncState.PAUSED && workFinished() }
            assertEquals(SyncMode.KNOWN_FILES, read { graph.library.getCollection(tag) }!!.syncMode)
            ui.onNodeWithTag("task-sync-$tag").performScrollTo().assertIsEnabled().performClick()
            wait { read { graph.library.getCollection(tag) }?.syncState == SyncState.QUEUE_COMPLETE && workFinished() }
            assertEquals(pages, origin.lists.get())
            assertEquals(transfers + 2, origin.totalOriginals())
            val known = read { graph.library.records(tag) }
            assertEquals(3, known.count { it.status == DownloadStatus.DOWNLOADED })
            assertEquals(healthy, known.filter { it.postId != 1L }.associate { it.postId to it.localUri })
            assertNotNull(read { graph.library.checkpoint(tag) })

            // "Continue" keeps the saved mode, while "retry scan" must explicitly switch to FULL.
            read {
                graph.account.save(ApiCredentials("42", "fixture-key"))
                graph.background.pause(tag)
            }
            wait { read { graph.library.getCollection(tag) }?.syncState == SyncState.PAUSED && workFinished() }
            ui.onNodeWithTag("nav-subscriptions").performClick()
            ui.onNodeWithTag("recovery-$tag").performScrollTo().performClick()
            ui.onNodeWithTag("retry-scan-$tag").performClick()
            wait {
                val collection = read { graph.library.getCollection(tag) }
                collection?.syncMode == SyncMode.FULL && collection.syncState == SyncState.WAITING_RETRY && origin.lists.get() > pages
            }
            assertEquals(known.map { it.localUri }, read { graph.library.records(tag) }.map { it.localUri })
            origin.brokenLists.remove(tag)
            ui.onNodeWithTag("recovery-$tag").performScrollTo().performClick()
            ui.onNodeWithText("扫描异常表示作品列表尚未确认完整，不表示已下载文件损坏。可以先下载已发现文件，再重试扫描。").assertExists()
            capture("recovery-options.png")
            ui.onNodeWithTag("rescan-$tag").performClick()
            ui.onNodeWithText("取消").performClick()
            assertNotNull(read { graph.library.checkpoint(tag) })
            assertEquals(SyncState.WAITING_RETRY, read { graph.library.getCollection(tag) }!!.syncState)
            ui.onNodeWithTag("recovery-$tag").performScrollTo().performClick()
            ui.onNodeWithTag("rescan-$tag").performClick()
            val beforeRescan = origin.totalOriginals()
            ui.onNodeWithTag("confirm-rescan-$tag").performClick()
            wait { read { graph.library.getCollection(tag) }?.syncState == SyncState.COMPLETE && workFinished() }
            assertNull(read { graph.library.checkpoint(tag) })
            assertEquals(3L, read { graph.library.getCollection(tag) }!!.lastSeenPostId)
            assertEquals(known.map { it.localUri }, read { graph.library.records(tag) }.map { it.localUri })
            assertEquals(beforeRescan, origin.totalOriginals())
            assertEquals(0, origin.authenticationLeaks.get())
            for (record in read { graph.library.records(tag) }) {
                val actual = app.contentResolver.openInputStream(Uri.parse(record.localUri!!))!!.use { it.readBytes() }
                assertEquals(origin.hashes.getValue(record.postId), TestApiOrigin.md5(actual))
            }
            capture("recovery-complete.png")
            writeTestText(app, "controlled", "recovery-result.txt",
                "PASS: broken API scan still downloaded healthy known files; one forbidden original isolated; UI downloaded remaining file without API credentials or API calls; original URIs preserved; subscription retry scan left paused known-file mode; cancelled rescan changed nothing; confirmed rescan reset discovery and reused all three MediaStore files")
        } finally {
            origin.brokenLists.remove(tag); origin.forbiddenOriginals.clear()
            read {
                graph.library.records(tag).mapNotNull { it.localUri }.forEach { app.contentResolver.delete(Uri.parse(it), null, null) }
                graph.background.remove(tag)
            }
        }
    }

    private fun workFinished() = WorkManager.getInstance(app).getWorkInfosForUniqueWork("rule34-sync-$tag")
        .get(10, TimeUnit.SECONDS).all { it.state.isFinished }
    private fun wait(timeout: Long = 60_000, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (!predicate()) { check(System.nanoTime() < until) { "Recovery state did not converge" }; Thread.sleep(50) }
    }
    private fun capture(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        writeTestEvidence(app, "controlled", name, "image/png") { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
