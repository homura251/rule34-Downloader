package com.homura251.rule34downloader

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.work.WorkManager
import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.storage.MediaStoreDownloader
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class BulkDownloadEndToEndTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<Rule34Application>()
    private val graph get() = app.graph
    private val repo get() = graph.library
    private val origin get() = BulkTestEnvironment.origin
    private val tag get() = origin.tag
    private val count get() = origin.count
    private val args get() = InstrumentationRegistry.getArguments()
    private val phase get() = args.getString("bulkPhase") ?: "api"
    private val scope get() = "bulk-${args.getString("bulkKey") ?: "manual"}"
    private val device = UiDevice.getInstance(instrumentation)
    private val trace = StringBuilder()
    private fun <T> read(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun records() = read { repo.records(tag) }
    private fun record(id: Long) = records().firstOrNull { it.postId == id }
    private fun state() = read { repo.getCollection(tag) }?.syncState
    private fun completed() = records().count { it.status == DownloadStatus.DOWNLOADED }

    @Test fun genuineApiUiProcessRecoveryAndReinstallation() {
        waitUiTag("nav-subscriptions")
        try { when (phase) {
            "crash" -> prepareCrash()
            "recover" -> recoverCrash()
            "api" -> basicApiPauseResume()
            "checkpoint" -> checkpointAndPause()
            "resume" -> resumeLargeBatch()
            "restore" -> restoreWithSaf()
            else -> error("Unknown bulk phase")
        }
        assertEquals(0, origin.authenticationLeaks.get())
        } finally {
            runCatching {
                capture("$phase-final.png")
                evidence("Final observed state; metadata requests=${origin.lists.get()}")
            }
        }
    }

    private fun prepareCrash() {
        addThroughUi()
        wait { record(1)?.bytesDownloaded?.let { it > 0 } == true && pendingFiles() > 0 }
        capture("crash-pending.png")
        evidence("READY: real original partially written; external script will force-stop this process")
        writeTestText(app, scope, "crash-ready.txt", "ready")
        // The script kills the real application and instrumentation; the recovery phase validates it.
        while (true) Thread.sleep(1000)
    }
    private fun recoverCrash() {
        wait(180_000) { completed() == count && workFinished() }
        assertEquals(0, pendingFiles())
        assertEquals(count, origin.totalOriginals())
        verifyAll(); capture("recover-complete.png")
        evidence("PASS: Application startup resumed the killed worker; incomplete MediaStore row cleaned")
    }
    private fun basicApiPauseResume() {
        addThroughUi(); wait { record(1)?.bytesDownloaded?.let { it > 0 } == true }
        pauseThroughUi()
        assertEquals(0, completed())
        assertEquals(count.toLong(), read { repo.getCollection(tag) }!!.lastSeenPostId)
        resumeThroughUi()
        wait(180_000) { completed() == count && workFinished() }
        assertEquals(count + 1, origin.totalOriginals())
        verifyAll(); incrementalKeepsUris()
        ui.onNodeWithTag("nav-gallery").performClick(); capture("api-paged-library.png")
        ui.onNodeWithTag("nav-subscriptions").performClick()
        evidence("PASS: API-only UI pause/resume; all original bytes verified; subsequent sync reused every URI")
    }
    private fun checkpointAndPause() {
        addThroughUi()
        wait { origin.delayedPages.get() == 1 && records().size == 200 }
        pauseThroughUi()
        assertEquals(0L, read { repo.getCollection(tag) }!!.lastSeenPostId)
        assertEquals(ScanCheckpoint(0, count.toLong() - 199, count.toLong()), read { repo.checkpoint(tag) })
        assertEquals(0, origin.totalOriginals())
        evidence("Partial scan paused: 200 metadata/task rows and checkpoint persisted; watermark remained zero")
        resumeThroughUi()
        wait(180_000) { records().size == count && record(1)?.bytesDownloaded?.let { it > 0 } == true }
        assertEquals(count.toLong(), read { repo.getCollection(tag) }!!.lastSeenPostId)
        assertNull(read { repo.checkpoint(tag) })
        pauseThroughUi()
        origin.mediaPauseId.set(3)
        resumeThroughUi()
        wait { completed() == 2 && record(3)?.bytesDownloaded?.let { it > 0 } == true }
        pauseThroughUi()
        val saved = records().filter { it.status == DownloadStatus.DOWNLOADED }
        assertEquals(listOf(1L, 2L), saved.map { it.postId })
        writeTestText(app, scope, "paused-uris.json", JSONObject().apply { saved.forEach { put(it.postId.toString(), it.localUri) } }.toString())
        capture("checkpoint-paused.png")
        evidence("PASS: complete scan committed watermark=$count; exactly two verified URIs saved; batch deliberately left PAUSED")
    }
    private fun resumeLargeBatch() {
        wait { state() == SyncState.PAUSED }
        assertTrue(read { repo.getCollection(tag) }!!.paused)
        assertEquals(2, completed())
        val previous = readEvidence("paused-uris.json")
        val uris = records().filter { it.status == DownloadStatus.DOWNLOADED }.associate { it.postId.toString() to it.localUri }
        uris.forEach { (id, uri) -> assertEquals(JSONObject(previous).getString(id), uri) }
        resumeThroughUi()
        wait(600_000) { completed() == count && workFinished() }
        assertEquals(count - 2, origin.totalOriginals())
        uris.forEach { (id, uri) -> assertEquals(uri, record(id.toLong())!!.localUri) }
        assertEquals(0, pendingFiles())
        verifyAll(); capture("resume-3000-complete.png"); incrementalKeepsUris()
        prepareReinstallation()
        evidence("PASS: process restart preserved PAUSED and completed URIs; resume fetched only unfinished files")
    }
    private fun prepareReinstallation() {
        val saved = records().associateBy { it.postId }
        for (id in 1L..5L) {
            val bytes = origin.bytes.getValue(id)
            val damaged = when (id) { 1L, 2L -> ByteArray(bytes.size) { 23 }; 3L, 4L -> bytes.copyOf(bytes.size / 2); else -> byteArrayOf() }
            app.contentResolver.openOutputStream(Uri.parse(saved.getValue(id).localUri!!), "wt")!!.use { it.write(damaged) }
        }
        for (id in 6L..7L) app.contentResolver.delete(Uri.parse(saved.getValue(id).localUri!!), null, null)
        for (id in 8L..9L) app.contentResolver.update(Uri.parse(saved.getValue(id).localUri!!),
            ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "$id.png") }, null, null)
        evidence("Prepared REAL uninstall: five damaged, two removed, two valid legacy filenames; all files otherwise retained")
    }
    private fun restoreWithSaf() {
        assertTrue("Actual reinstall clears the database", read { repo.getCollections() }.isEmpty())
        assertNull(read { graph.settings.snapshot() }.existingDownloadsTreeUri)
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        assertFalse("Android Keystore account data must be cleared by uninstall", BulkTestEnvironment.credentialsPresentBeforeSeed)
        origin.pagePauseBefore.set(count.toLong() - 199)
        pickFolder(child = false)
        val root = read { graph.settings.snapshot() }.existingDownloadsTreeUri!!
        assertEquals("Association restores all three author folders without manually adding subscriptions", 3, read { repo.getCollections() }.size)
        assertEquals(3, graph.folders.state.value.result!!.restored)
        assertEquals(3, graph.folders.state.value.result!!.scheduled)
        wait { origin.delayedPages.get() == 1 && records().size == 200 }
        pauseThroughUi()
        pickFolder(child = true)
        assertEquals(SyncState.PAUSED, state())
        assertTrue(read { repo.getCollection(tag) }!!.paused)
        assertTrue("Changing the selected folder retains the previous read grant", app.contentResolver.persistedUriPermissions.any { it.uri == Uri.parse(root) && it.isReadPermission })
        resumeThroughUi()
        wait(600_000) { completed() == count && workFinished() }
        wait { read { repo.getCollections() }.all { it.syncState == SyncState.COMPLETE } }
        assertEquals("Only damaged or absent originals may cross HTTP", 7, origin.totalOriginals())
        assertEquals(count - 7, records().count { it.localUri!!.contains("externalstorage.documents") })
        assertTrue(record(8)!!.localUri!!.contains("externalstorage.documents"))
        assertTrue(record(9)!!.localUri!!.contains("externalstorage.documents"))
        verifyAll(); incrementalKeepsUris(); capture("restore-3000-complete.png")
        evidence("PASS: real reinstall + native SAF root/child picker; association automatically restored/synced three author folders; ${count - 7} valid primary files reused; seven bad/missing originals repaired; all $count bytes/URIs verified")
    }
    private fun incrementalKeepsUris() {
        val uris = records().associate { it.postId to it.localUri }
        val media = origin.totalOriginals(); val pages = origin.lists.get()
        syncButton("同步")
        wait(180_000) { origin.lists.get() > pages && state() == SyncState.COMPLETE && workFinished() }
        assertEquals(media, origin.totalOriginals())
        assertEquals(uris, records().associate { it.postId to it.localUri })
    }

    private fun addThroughUi() {
        ui.onNodeWithTag("nav-subscriptions").performClick()
        ui.onNodeWithTag("add-author").performClick()
        ui.onNodeWithTag("author-input").performTextInput(tag)
        ui.onNodeWithText("继续", substring = false).performClick()
        waitUiText("订阅并同步"); ui.onNodeWithText("订阅并同步").performClick()
        waitUiTag("artist-$tag")
    }
    private fun syncButton(expected: String) {
        ui.onNodeWithTag("nav-subscriptions").performClick()
        settleFolderFeedback()
        ui.waitUntil(30_000) { ui.onAllNodes(hasTestTag("sync-$tag") and hasText(expected)).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("sync-$tag").performScrollTo().performClick()
    }
    private fun pauseThroughUi() {
        syncButton("暂停"); wait { state() == SyncState.PAUSED && workFinished() }
    }
    private fun resumeThroughUi() { assertEquals(SyncState.PAUSED, state()); syncButton("继续") }
    private fun pickFolder(child: Boolean) {
        val previous = read { graph.settings.snapshot() }.existingDownloadsTreeUri
        ui.onNodeWithTag("nav-settings").performClick()
        waitUiTag("settings-list")
        settleFolderFeedback()
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("link-downloads"))
        ui.onNodeWithTag("link-downloads").performClick()
        assertTrue("Native SAF picker must open after clicking the folder button", device.wait(Until.hasObject(By.text(Pattern.compile("(?i)use this folder"))), 15_000))
        val alreadyChild = previous?.let { DocumentsContract.getTreeDocumentId(Uri.parse(it)).endsWith("/$tag") } == true
        if (child && !alreadyChild) device.wait(Until.findObject(By.text(tag)), 10_000)?.click() ?: error("Subscription folder missing in system picker")
        val use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 10_000) ?: error("No folder confirmation")
        assertTrue(use.isEnabled); use.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)?.click() ?: error("No SAF permission confirmation")
        wait {
            val selected = read { graph.settings.snapshot() }.existingDownloadsTreeUri
            selected != null && (!child || DocumentsContract.getTreeDocumentId(Uri.parse(selected)).endsWith("/$tag")) &&
                app.contentResolver.persistedUriPermissions.any { it.uri == Uri.parse(selected) && it.isReadPermission } &&
                !graph.folders.state.value.linking && graph.folders.state.value.result != null
        }
        capture("$phase-folder-granted.png")
        ui.onNodeWithTag("nav-subscriptions").performClick()
    }
    private fun settleFolderFeedback() {
        if (ui.onAllNodesWithTag("app-message").fetchSemanticsNodes().isEmpty()) return
        // The previous folder confirmation can cover controls on a 320dp display.
        // Native waits do not advance the Compose clock used by the snackbar timeout.
        ui.mainClock.advanceTimeBy(6_000)
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("app-message").fetchSemanticsNodes().isEmpty()
        }
    }
    private fun verifyAll() {
        val saved = records()
        assertEquals(count, saved.size); assertEquals(count, saved.map { it.localUri }.distinct().size)
        var total = 0L
        for (r in saved) {
            assertEquals(DownloadStatus.DOWNLOADED, r.status)
            assertEquals(origin.hashes.getValue(r.postId), r.verifiedMd5)
            val digest = MessageDigest.getInstance("MD5")
            var length = 0L
            app.contentResolver.openInputStream(Uri.parse(r.localUri!!))!!.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); length += n }
            }
            assertEquals(r.bytesDownloaded, length)
            assertEquals(origin.hashes.getValue(r.postId), digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            total += length
        }
        evidence("Verified every actual file: count=$count; bytes=$total; unique URIs=$count")
    }
    private fun pendingFiles() = app.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Downloads._ID), Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ? AND is_pending = 1 AND owner_package_name = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(MediaStoreDownloader.buildRelativePath(tag), app.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }, null)!!.use { it.count }
    private fun readEvidence(name: String): String {
        app.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "relative_path = ? AND _display_name = ?", arrayOf("Download/Rule34 Downloader Test Evidence/$scope/", name), null)!!.use {
            check(it.moveToFirst()) { "Missing prior phase evidence" }
            return app.contentResolver.openInputStream(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)))!!.bufferedReader().use { input -> input.readText() }
        }
    }
    private fun workFinished() = WorkManager.getInstance(app).getWorkInfosForUniqueWork("rule34-sync-$tag").get(10, TimeUnit.SECONDS).all { it.state.isFinished }
    private fun waitUiText(text: String) = ui.waitUntil(20_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitUiTag(tag: String) = ui.waitUntil(30_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun wait(timeout: Long = 60_000, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (!predicate()) { check(System.nanoTime() < until) { "Timeout in $phase; $trace" }; Thread.sleep(100) }
    }
    private fun evidence(message: String) {
        val line = "$message; completed=${completed()}; originals=${origin.totalOriginals()}; state=${state()}"
        trace.append(line).append('\n'); writeTestText(app, scope, "$phase-result.txt", trace.toString())
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$line\n") })
    }
    private fun capture(name: String) {
        runCatching { ui.waitForIdle() }
        val image = instrumentation.uiAutomation.takeScreenshot()!!
        writeTestEvidence(app, scope, name, "image/png") { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}
