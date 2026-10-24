package com.homura251.rule34downloader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    @Test fun failuresIdentifyTheFileAndRetryWithoutCredentialsOrRescanning() {
        wait { graph.startup.value.status == StartupStatus.READY }
        read {
            graph.account.save(ApiCredentials("42", "fixture-key"))
            graph.background.authenticationChanged()
            for (collection in graph.library.getCollections()) graph.background.remove(collection.tag)
            graph.settings.save(graph.settings.snapshot().copy(existingDownloadsTreeUri = null))
            graph.library.addCollection(tag, 1)
            assertTrue(graph.library.beginRun(tag, "seed"))
            graph.library.savePage(tag, "seed", (1L..3L).map { graph.catalog.getPost(it) }, ScanCheckpoint(0, 1, 3))
            graph.library.endRun(tag, "seed")
        }
        try {
            origin.brokenLists.add(tag); origin.forbiddenOriginals.add(1)
            read { graph.background.sync(tag) }
            wait {
                read { graph.library.getCollection(tag) }?.syncState == SyncState.WAITING_RETRY &&
                    read { graph.library.records(tag) }.count { it.status == DownloadStatus.DOWNLOADED } == 2
            }
            val healthy = read { graph.library.records(tag) }.filter { it.postId != 1L }.associate { it.postId to it.localUri }
            read { graph.account.clear(); graph.background.authenticationChanged() }
            wait { workFinished() }
            val pages = origin.lists.get()
            val transfers = origin.totalOriginals()
            ui.onNodeWithTag("nav-tasks").performClick()
            ui.onNodeWithTag("task-filter-失败").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("failed-$tag-1").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Post ID 1").assertExists()
            ui.onNodeWithText("1_${origin.hashes.getValue(1)}.png").assertExists()
            ui.onNodeWithText("HTTP 403", substring = true).assertExists()
            capture("failed-file.png")
            origin.forbiddenOriginals.remove(1)
            ui.onNodeWithTag("retry-file-$tag-1").performClick()
            allowNotificationsIfPrompted()
            wait { read { graph.library.getCollection(tag) }?.syncState == SyncState.QUEUE_COMPLETE && workFinished() }
            assertEquals(pages, origin.lists.get())
            assertEquals(transfers + 1, origin.totalOriginals())
            assertEquals(healthy, read { graph.library.records(tag) }.filter { it.postId != 1L }.associate { it.postId to it.localUri })
            assertNotNull(read { graph.library.checkpoint(tag) })
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("failed-$tag-1").fetchSemanticsNodes().isEmpty() }
            read {
                graph.account.save(ApiCredentials("42", "fixture-key"))
                origin.brokenLists.remove(tag)
                graph.background.rescan(tag)
            }
            wait { read { graph.library.getCollection(tag) }?.syncState == SyncState.COMPLETE && workFinished() }
            assertNull(read { graph.library.checkpoint(tag) })
            assertEquals(transfers + 1, origin.totalOriginals())
            assertEquals(0, origin.authenticationLeaks.get())
            writeTestText(app, "controlled", "recovery-result.txt",
                "PASS: failed file thumbnail, filename, artist, Post ID and HTTP reason; selected retry without API or scan; healthy URIs retained; full rescan retained all completed files")
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
        ui.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        writeTestEvidence(app, "controlled", name, "image/png") { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
