package com.homura251.rule34downloader

import android.content.ContentValues
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class FolderLinkRecoveryTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Rule34Application>()
    private val graph get() = app.graph
    private val origin get() = ApiTestEnvironment.origin
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val root = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Download/Rule34 Restore Tests")
    private fun <T> read(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @Test fun nativeAssociationStartsCompletedAndRestoredSubscriptionsAndDefersUntilApiVerification() {
        grantNotificationsForTest()
        wait { graph.startup.value.status == StartupStatus.READY }
        read {
            graph.account.save(ApiCredentials("42", "fixture-key"))
            graph.background.authenticationChanged()
            for (collection in graph.library.getCollections()) graph.background.remove(collection.tag)
        }
        val files = mutableListOf<Uri>()
        try {
            val completedUris = seedFiles("restore_complete").also { files += it }
            files += seedFiles("restore_missing")
            files += seedFiles("restore_paused")
            read {
                graph.library.addCollection("restore_complete", 1)
                assertTrue(graph.library.beginRun("restore_complete", "seed"))
                val posts = (1L..3L).map { id -> Rule34Post(id, "https://wimg.rule34.xxx/images/$id/${origin.hashes.getValue(id)}.png",
                    origin.hashes.getValue(id), listOf("restore_complete")) }
                graph.library.savePage("restore_complete", "seed", posts, ScanCheckpoint(0, 1, 3))
                graph.library.commitScan("restore_complete", "seed", 3)
                posts.forEachIndexed { index, post -> graph.library.markDownloaded("restore_complete", "seed", post.id,
                    SavedFile(completedUris[index].toString(), origin.bytes.getValue(post.id).size.toLong(), post.md5)) }
                graph.library.setState("restore_complete", "seed", SyncState.COMPLETE)
                graph.library.endRun("restore_complete", "seed")
                graph.library.addCollection("restore_paused", 0)
                graph.library.requestPause("restore_paused"); graph.library.finishPause("restore_paused")
                // Only a picker location hint: permission must still be granted by the native dialog.
                graph.settings.save(graph.settings.snapshot().copy(existingDownloadsTreeUri = root.toString()))
            }
            ui.waitUntil(20_000) { ui.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
            val requests = origin.totalOriginals()
            pickRoot()
            wait { graph.folders.state.value.result?.scheduled == 2 }
            val first = graph.folders.state.value.result!!
            assertEquals(1, first.restored); assertEquals(1, first.paused); assertFalse(first.waitingForApi)
            waitFinished("restore_complete", "restore_missing")
            assertEquals(completedUris.map { it.toString() }, read { graph.library.records("restore_complete") }.map { it.localUri })
            assertTrue(read { graph.library.records("restore_missing") }.all { it.localUri!!.contains("externalstorage.documents") })
            assertEquals(SyncState.PAUSED, read { graph.library.getCollection("restore_paused") }!!.syncState)
            assertEquals(requests, origin.totalOriginals())

            files += seedFiles("restore_waiting")
            read { graph.account.clear(); graph.background.authenticationChanged() }
            val pages = origin.lists.get()
            pickRoot()
            wait { graph.folders.state.value.result?.waitingForApi == true }
            assertEquals(3, graph.folders.state.value.result!!.scheduled)
            wait { read { graph.library.getCollection("restore_waiting") }?.syncState == SyncState.WAITING_AUTH }
            assertEquals(pages, origin.lists.get()); assertEquals(requests, origin.totalOriginals())
            settleFeedback()
            ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("api-user-id"))
            ui.onNodeWithTag("api-user-id").performTextReplacement("42")
            ui.onNodeWithTag("api-key").performTextReplacement("fixture-key")
            ui.onNodeWithTag("verify-api").performScrollTo().performClick()
            waitFinished("restore_complete", "restore_missing", "restore_waiting")
            assertEquals(SyncState.PAUSED, read { graph.library.getCollection("restore_paused") }!!.syncState)
            assertEquals(requests, origin.totalOriginals())
            assertEquals(0, origin.authenticationLeaks.get())
            writeTestText(app, "controlled", "folder-link-result.txt", "PASS: real SAF picker; completed subscriptions automatically resynced; missing subscriptions recreated from author folders; saved URIs preserved; paused subscription unchanged; no API calls while credentials missing; validation automatically resumed recovery; no extra original HTTP requests")
        } finally {
            runCatching {
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
                writeTestEvidence(app, "controlled", "folder-link-final.png", "image/png") { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            files.forEach { app.contentResolver.delete(it, null, null) }
        }
    }

    private fun seedFiles(tag: String): List<Uri> = (1L..3L).map { id ->
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${id}_${origin.hashes.getValue(id)}.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Rule34 Restore Tests/Rule34 Downloader/$tag/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        })!!
        app.contentResolver.openOutputStream(uri)!!.use { it.write(origin.bytes.getValue(id)) }
        app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        uri
    }
    private fun pickRoot() {
        if (ui.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithTag("nav-settings").performClick()
        settleFeedback()
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("link-downloads"))
        ui.onNodeWithTag("link-downloads").performClick()
        val use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 15_000) ?: error("Native folder picker missing")
        assertTrue(use.isEnabled); use.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)?.click()
        wait { app.contentResolver.persistedUriPermissions.any { it.uri == root && it.isReadPermission } && !graph.folders.state.value.linking }
    }
    private fun settleFeedback() {
        ui.waitForIdle()
        if (ui.onAllNodesWithTag("app-message").fetchSemanticsNodes().isNotEmpty()) {
            ui.mainClock.advanceTimeBy(6_000)
            ui.waitUntil(10_000) { ui.onAllNodesWithTag("app-message").fetchSemanticsNodes().isEmpty() }
        }
    }
    private fun waitFinished(vararg tags: String) = wait(90_000) {
        tags.all { tag -> read { graph.library.getCollection(tag) }?.syncState == SyncState.COMPLETE &&
            WorkManager.getInstance(app).getWorkInfosForUniqueWork("rule34-sync-$tag").get(10, TimeUnit.SECONDS).all { it.state.isFinished } }
    }
    private fun wait(timeout: Long = 30_000, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (!predicate()) { check(System.nanoTime() < until) { "Directory recovery timed out: ${graph.folders.state.value}" }; Thread.sleep(50) }
    }
}
