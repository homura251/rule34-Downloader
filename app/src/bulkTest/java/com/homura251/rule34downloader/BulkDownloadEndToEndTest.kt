package com.homura251.rule34downloader

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import com.homura251.rule34downloader.data.*
import com.homura251.rule34downloader.network.BrowserPageReader
import com.homura251.rule34downloader.network.Rule34HtmlClient
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.storage.MediaStoreDownloader
import com.homura251.rule34downloader.work.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** UI -> regular WorkManager -> real HTTP -> MediaStore -> real external-storage SAF. */
@RunWith(AndroidJUnit4::class)
class BulkDownloadEndToEndTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Rule34Database.getInstance(context)
    private val arguments = InstrumentationRegistry.getArguments()
    private val phase = arguments.getString("bulkPhase") ?: "browser"
    private val count = arguments.getString("bulkCount")?.toInt() ?: 84
    private val key = arguments.getString("bulkKey") ?: "manual"
    private val tag = "test_bulk_" + when (phase) { "browser" -> "browser_"; "crash", "recover" -> "crash_"; else -> "" } + key
    private val scope = "bulk-" + key
    private val device = UiDevice.getInstance(instrumentation)
    private val readers = ConcurrentLinkedQueue<BrowserPageReader>()
    private val trace = StringBuilder()
    private lateinit var origin: BulkOrigin
    private var scenario: ActivityScenario<MainActivity>? = null
    private var successful = false

    @Before fun configureActualWorkerEngine() {
        assertFalse(CredentialsStore(context).isConfigured())
        AppPreferences(context).apply { autoSyncEnabled = false; wifiOnly = false }
        origin = BulkTestEnvironment.origin
        assertEquals(tag, origin.tag)
        instrumentation.runOnMainSync { Rule34Network.get(context) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() {
        SyncScheduler.cancelArtistSync(context, tag)
        runCatching { wait(10_000) { workFinished() } }
        BulkTestEnvironment.readers.forEach { it.close() }
        DownloaderWorkerFactory.setServicesForTests(null)
        scenario?.close()
        origin.close()
        if (successful && phase in setOf("browser", "recover")) {
            if (phase in setOf("browser", "recover")) deleteOwnedFiles()
            database.removeArtist(tag)
        }
    }

    @Test fun executeBulkPhase() {
        try {
            when (phase) {
                "browser" -> { checkpoint(); resumeAndVerify(); deleteOwnedFiles() }
                "checkpoint" -> checkpoint()
                "resume" -> { resumeAndVerify(); prepareReinstallation() }
                "restore" -> restoreThroughSystemPicker()
                "crash" -> crashDuringTransfer()
                "recover" -> recoverAfterRealProcessDeath()
                else -> error("Unknown phase: " + phase)
            }
            successful = true
            checkpointEvidence("PASS " + phase)
        } catch (error: Throwable) {
            checkpointEvidence("FAILED " + phase + ": " + error)
            capture(phase + "-failure.png")
            runCatching { writeTestEvidence(context, scope, phase + "-hierarchy.xml", "text/xml") { device.dumpWindowHierarchy(it) } }
            throw error
        }
    }

    private fun crashDuringTransfer() {
        addArtist()
        wait { record(count.toLong())?.bytesDownloaded?.let { it > 0 } == true }
        assertEquals(1, pendingFiles())
        val pending = MediaStoreDownloader.queryIncludingPending(context.contentResolver,
            "relative_path = ? AND is_pending = 1 AND owner_package_name = ?",
            arrayOf(MediaStoreDownloader.buildRelativePath(tag), context.packageName))!!.use {
            assertTrue(it.moveToFirst()); ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0))
        }
        context.getSharedPreferences("bulk-crash", Context.MODE_PRIVATE).edit().putString("pending", pending.toString()).commit()
        capture("crash-incomplete-transfer.png")
        checkpointEvidence("ready for REAL process kill; pending=" + pending)
        writeTestText(context, scope, "crash-ready.txt", "READY actual incomplete MediaStore transfer\n")
        // The shell deliberately force-stops this process while bytes are pending.
        while (true) Thread.sleep(500)
    }

    private fun recoverAfterRealProcessDeath() {
        val old = context.getSharedPreferences("bulk-crash", Context.MODE_PRIVATE).getString("pending", null)!!
        wait(600_000) { completed() == count && database.getSyncState(tag) == SyncState.COMPLETE && workFinished() }
        assertEquals(0, pendingFiles()); assertEquals(0, failed())
        assertEquals(count, origin.totalOriginals())
        assertNotEquals(old, record(count.toLong())!!.localUri)
        context.contentResolver.query(Uri.parse(old), arrayOf(MediaStore.Downloads._ID), null, null, null)?.use {
            assertFalse("Crash cleanup must delete the abandoned pending row", it.moveToFirst())
        }
        verifyAll()
        checkpointEvidence("REAL process death recovered automatically; abandoned pending row removed")
    }

    private fun checkpoint() {
        assertFalse(database.hasArtist(tag))
        addArtist()
        wait { origin.originalCount(count.toLong()) > 0 && record(count.toLong())?.bytesDownloaded?.let { it > 0 } == true }
        pauseFromUi("during-original")
        assertEquals(0, completed())
        assertEquals(0, pendingFiles())
        assertEquals(0L, database.getArtist(tag)!!.lastSeenPostId)
        resumeFromUi()
        wait { origin.detailCount(count.toLong() - 2) > 0 }
        pauseFromUi("during-detail")
        assertEquals(2, completed())
        assertNull(record(count.toLong() - 2))
        assertEquals(0, failed())
        assertEquals(0, pendingFiles())
        assertEquals(0L, database.getArtist(tag)!!.lastSeenPostId)
        assertEquals(1, origin.detailCount(count.toLong()))
        assertEquals(1, origin.detailCount(count.toLong() - 1))
        assertEquals(2, origin.originalCount(count.toLong()))
        capture(phase + "-paused.png")
    }

    private fun resumeAndVerify() {
        assertTrue(database.isPaused(tag))
        val checkpointUris = database.getSavedRecords(tag).filter { it.status == DownloadStatus.DOWNLOADED }.associate { it.postId to it.localUri }
        assertEquals(2, checkpointUris.size)
        resumeFromUi()
        // Repeated callbacks must keep one active queue and preserve completed files.
        scenario!!.onActivity { activity ->
            val model = ViewModelProvider(activity)[com.homura251.rule34downloader.ui.MainViewModel::class.java]
            repeat(3) { model.syncArtist(tag) }
        }
        wait(1_200_000) { completed() == count && database.getSyncState(tag) == SyncState.COMPLETE && workFinished() }
        assertEquals(0, failed()); assertEquals(0, pendingFiles())
        assertEquals(count.toLong(), database.getArtist(tag)!!.lastSeenPostId)
        checkpointUris.forEach { (id, uri) -> assertEquals(uri, record(id)!!.localUri) }
        if (phase == "resume") {
            checkpointUris.keys.forEach { assertEquals(0, origin.originalCount(it)); assertEquals(0, origin.detailCount(it)) }
            assertEquals(count - 2, origin.totalOriginals())
        }
        verifyAll()
        preview()
        val saved = database.getSavedRecords(tag).associate { it.postId to it.localUri }
        val hits = origin.totalOriginals()
        val lists = origin.lists.get()
        syncFromUi()
        wait(600_000) { origin.lists.get() > lists && database.getSyncState(tag) == SyncState.COMPLETE && workFinished() }
        assertEquals(hits, origin.totalOriginals())
        assertEquals(saved, database.getSavedRecords(tag).associate { it.postId to it.localUri })
        assertEquals(0, pendingFiles())
        capture(phase + "-complete.png")
        if (phase == "resume") {
            val id = 13L
            val old = Uri.parse(saved.getValue(id)!!)
            context.contentResolver.openOutputStream(old, "wt")!!.use { it.write(ByteArray(origin.bytes.getValue(id).size) { 42 }) }
            val before = origin.originalCount(id)
            val beforeLists = origin.lists.get()
            syncFromUi()
            wait(600_000) { origin.lists.get() > beforeLists && origin.originalCount(id) == before + 1 &&
                database.getSyncState(tag) == SyncState.COMPLETE && workFinished() }
            assertNotEquals(old.toString(), record(id)!!.localUri)
            assertEquals(saved.filterKeys { it != id }, database.getSavedRecords(tag).filter { it.postId != id }.associate { it.postId to it.localUri })
            context.contentResolver.delete(old, null, null) // Remove this test's deliberately damaged copy.
            verifyAll()
            checkpointEvidence("same-length known URI repaired; all other URIs retained")
        }
    }

    private fun prepareReinstallation() {
        val saved = database.getSavedRecords(tag).associateBy { it.postId }
        for (id in 1L..5L) {
            val bytes = origin.bytes.getValue(id)
            val damaged = when (id) { 1L, 2L -> ByteArray(bytes.size) { 23 }; 3L, 4L -> bytes.copyOf(bytes.size / 2); else -> byteArrayOf() }
            context.contentResolver.openOutputStream(Uri.parse(saved.getValue(id).localUri!!), "wt")!!.use { it.write(damaged) }
        }
        for (id in 6L..7L) context.contentResolver.delete(Uri.parse(saved.getValue(id).localUri!!), null, null)
        for (id in 8L..9L) context.contentResolver.update(Uri.parse(saved.getValue(id).localUri!!),
            ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "$id.png") }, null, null)
        checkpointEvidence("ready for REAL uninstall: 5 damaged, 2 missing, 2 valid legacy names")
    }

    private fun restoreThroughSystemPicker() {
        assertTrue("Reinstall must clear the app database", database.getArtistTags().isEmpty())
        assertNull("Reinstall must clear the selected tree", AppPreferences(context).existingDownloadsTreeUri)
        assertTrue("Reinstall must clear persisted grants", context.contentResolver.persistedUriPermissions.isEmpty())
        pickFolder(false)
        origin.detailPauseId.set(count.toLong() - 40)
        addArtist()
        wait { completed() >= 30 }
        pauseFromUi("during-old-file-reuse")
        val reused = database.getSavedRecords(tag).filter { it.status == DownloadStatus.DOWNLOADED }.associate { it.postId to it.localUri }
        assertTrue(reused.size >= 30)
        assertTrue(reused.values.all { it!!.startsWith("content://com.android.externalstorage.documents/tree/") })
        assertEquals(0, origin.totalOriginals())
        assertEquals(0, failed()); assertEquals(0, pendingFiles())
        pickFolder(true)
        assertTrue("Changing folder must keep the paused state", database.isPaused(tag))
        assertEquals(SyncState.PAUSED, database.getSyncState(tag))
        origin.detailPauseId.set(count.toLong() - reused.size - 40)
        resumeFromUi()
        wait { completed() >= reused.size + 20 }
        pauseFromUi("reuse-after-folder-change")
        reused.forEach { (id, uri) -> assertEquals(uri, record(id)!!.localUri) }
        assertEquals(0, origin.totalOriginals())
        context.contentResolver.persistedUriPermissions.forEach {
            context.contentResolver.releasePersistableUriPermission(it.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        resumeFromUi()
        wait { database.getSyncState(tag) == SyncState.ERROR && tag !in SyncControls.activeTags() }
        assertEquals(0, origin.totalOriginals())
        // Regrant must restart even when WorkManager is waiting in retry backoff.
        pickFolder(true)
        wait(1_200_000) { completed() == count && database.getSyncState(tag) == SyncState.COMPLETE && workFinished() }
        assertEquals(7, origin.totalOriginals())
        for (id in 1L..count.toLong()) assertEquals(if (id <= 7) 1 else 0, origin.originalCount(id))
        assertEquals(0, failed()); assertEquals(0, pendingFiles())
        verifyAll()
        preview()
        val hits = origin.totalOriginals()
        val lists = origin.lists.get()
        val uris = database.getSavedRecords(tag).associate { it.postId to it.localUri }
        syncFromUi()
        wait(600_000) { origin.lists.get() > lists && database.getSyncState(tag) == SyncState.COMPLETE && workFinished() }
        assertEquals(hits, origin.totalOriginals())
        assertEquals(uris, database.getSavedRecords(tag).associate { it.postId to it.localUri })
        capture("restore-complete.png")
        checkpointEvidence("REAL reinstall: " + (count - 7) + " old originals reused, exactly 7 repaired, legacy names reused")
    }

    private fun addArtist() {
        waitUiTag("add-author"); ui.onNodeWithTag("add-author").performClick()
        waitUiTag("author-input"); ui.onNodeWithTag("author-input").performTextInput(tag)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithText("继续").performClick()
        waitUiText("添加并下载"); ui.onNodeWithText("添加并下载").performClick()
        wait { database.hasArtist(tag) }
    }
    private fun pauseFromUi(label: String) {
        wait { database.getSyncState(tag) == SyncState.SYNCING }
        waitUiTag("sync-$tag")
        ui.onNodeWithTag("sync-$tag").performScrollTo().assertIsEnabled().performClick()
        wait { database.getSyncState(tag) == SyncState.PAUSED && workFinished() }
        assertTrue(database.isPaused(tag))
        assertEquals(0, failed()); assertEquals(0, pendingFiles())
        checkpointEvidence(label)
    }
    private fun resumeFromUi() {
        assertEquals(SyncState.PAUSED, database.getSyncState(tag))
        waitUiText("继续")
        ui.onNodeWithTag("sync-$tag").performScrollTo().assertIsEnabled().performClick()
        wait { !database.isPaused(tag) }
    }
    private fun syncFromUi() {
        waitUiText("同步")
        ui.onNodeWithTag("sync-$tag").performScrollTo().assertIsEnabled().performClick()
    }
    private fun preview() {
        waitUiText("查看作品"); ui.onNodeWithText("查看作品").performScrollTo().performClick()
        waitUiText("画师作品 · $count 项")
        ui.onNodeWithText("#$count").performClick()
        ui.waitUntil(20_000) { ui.onAllNodesWithContentDescription("关闭预览").fetchSemanticsNodes().isNotEmpty() }
        capture(phase + "-preview.png")
        ui.onNodeWithContentDescription("关闭预览").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
    }
    private fun pickFolder(child: Boolean) {
        val previous = AppPreferences(context).existingDownloadsTreeUri
        ui.onNodeWithContentDescription("设置").performClick()
        waitUiText("下载与同步设置")
        ui.onNodeWithText(if (previous == null) "关联旧下载目录" else "重新关联旧下载目录").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("(?i)use this folder"))), 15_000))
        val alreadyChild = previous?.let { DocumentsContract.getTreeDocumentId(Uri.parse(it)).endsWith("/" + tag) } == true
        if (child && !alreadyChild) {
            device.wait(Until.findObject(By.text(tag)), 10_000)?.click() ?: error("Artist folder not found in system picker")
        }
        val use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 10_000) ?: error("No folder confirmation")
        assertTrue(use.isEnabled); use.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)?.click() ?: error("No SAF grant confirmation")
        wait { AppPreferences(context).existingDownloadsTreeUri != null && context.contentResolver.persistedUriPermissions.any { it.isReadPermission } }
        if (child) assertTrue(DocumentsContract.getTreeDocumentId(Uri.parse(AppPreferences(context).existingDownloadsTreeUri!!)).endsWith("/" + tag))
        waitUiText("下载与同步设置"); ui.onNodeWithText("取消").performClick()
        capture(phase + "-folder-granted.png")
    }
    private fun verifyAll() {
        val records = database.getSavedRecords(tag)
        assertEquals(count, records.size); assertEquals(count, records.map { it.localUri }.distinct().size)
        var bytes = 0L
        for (r in records) {
            assertEquals(DownloadStatus.DOWNLOADED, r.status)
            assertEquals(origin.hashes.getValue(r.postId), r.verifiedMd5)
            val digest = MessageDigest.getInstance("MD5")
            var read = 0L
            context.contentResolver.openInputStream(Uri.parse(r.localUri!!))!!.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); read += n }
            }
            assertEquals(r.bytesDownloaded, read)
            assertEquals(origin.hashes.getValue(r.postId), digest.digest().joinToString("") { "%02x".format(it) })
            bytes += read
        }
        checkpointEvidence("verified every file: count=$count; bytes=$bytes; unique URIs=$count")
    }
    private fun record(id: Long) = database.getSavedRecords(tag).firstOrNull { it.postId == id }
    private fun completed() = rows("DOWNLOADED")
    private fun failed() = rows("FAILED")
    private fun rows(status: String) = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM downloads WHERE artist_tag = ? AND status = ?", arrayOf(tag, status),
    ).use { it.moveToFirst(); it.getInt(0) }
    private fun pendingFiles() = MediaStoreDownloader.queryIncludingPending(context.contentResolver,
        "relative_path = ? AND is_pending = 1 AND owner_package_name = ?", arrayOf(MediaStoreDownloader.buildRelativePath(tag), context.packageName))!!.use { it.count }
    private fun deleteOwnedFiles() {
        MediaStoreDownloader.queryIncludingPending(context.contentResolver, "relative_path = ? AND owner_package_name = ?",
            arrayOf(MediaStoreDownloader.buildRelativePath(tag), context.packageName))?.use {
            while (it.moveToNext()) context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)), null, null)
        }
    }
    private fun workFinished() = WorkManager.getInstance(context).getWorkInfosForUniqueWork("rule34-sync-$tag")
        .get(10, TimeUnit.SECONDS).all { it.state.isFinished }
    private fun waitUiText(text: String) = ui.waitUntil(20_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitUiTag(tag: String) = ui.waitUntil(20_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun wait(timeout: Long = 60_000, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (!predicate()) {
            if (System.nanoTime() > deadline) error("Timeout in " + phase + "; " + trace)
            Thread.sleep(50)
        }
    }
    private fun checkpointEvidence(message: String) {
        val line = message + "; completed=" + completed() + "; failed=" + failed() + "; originals=" + origin.totalOriginals() + "; state=" + database.getSyncState(tag)
        trace.append(line).append('\n')
        writeTestText(context, scope, phase + "-result.txt", trace.toString())
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", line + "\n") })
    }
    private fun capture(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        writeTestEvidence(context, scope, name, "image/png") { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
