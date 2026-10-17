package com.homura251.rule34downloader

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Real WorkManager, Room and files; HTTP is deterministic so transfer counts are assertable. */
@RunWith(AndroidJUnit4::class)
class BusinessFlowsEndToEndTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Rule34Application>()
    private val graph get() = app.graph
    private val origin get() = ApiTestEnvironment.origin
    private val manager get() = WorkManager.getInstance(app)
    private val tags = listOf("test_business_network", "test_business_paused", "test_business_unlink", "test_business_incremental", "test_business_running_network")
    private fun <T> read(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @Before fun prepare() {
        wait { graph.startup.value.status == StartupStatus.READY }
        read {
            graph.account.save(ApiCredentials("42", "fixture-key"))
            graph.background.authenticationChanged()
            for (collection in graph.library.getCollections()) graph.background.remove(collection.tag)
            graph.settings.save(AppSettings())
            graph.background.updateSchedule()
        }
        ui.waitUntil(20_000) { ui.onAllNodesWithTag("nav-subscriptions").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun clean() {
        origin.brokenLists.removeAll(tags.toSet())
        tags.forEach { origin.visibleCounts.remove(it) }
        read {
            for (tag in tags) {
                graph.background.pause(tag)
                graph.library.records(tag).mapNotNull { it.localUri }.forEach { app.contentResolver.delete(Uri.parse(it), null, null) }
                graph.background.remove(tag)
            }
            graph.settings.save(AppSettings())
            graph.background.updateSchedule()
        }
    }

    @Test fun savedNetworkPolicyUpdatesExistingRetryAndPreservesPauseAndDeadline() {
        val tag = tags[0]
        origin.brokenLists.add(tag)
        read {
            graph.settings.save(AppSettings(wifiOnly = true))
            graph.library.addCollection(tag, 0)
            graph.library.addCollection(tags[1], 0)
            graph.background.pause(tags[1])
            graph.background.sync(tag)
        }
        wait { read { graph.library.getCollection(tag) }?.syncState == SyncState.WAITING_RETRY && pending(tag).singleOrNull()?.state == WorkInfo.State.ENQUEUED }
        val before = pending(tag).single()
        assertEquals(NetworkType.UNMETERED, before.constraints.requiredNetworkType)
        val deadline = before.nextScheduleTimeMillis
        val requests = origin.lists.get()
        read {
            graph.settings.save(AppSettings(wifiOnly = false))
            graph.background.updateSchedule()
        }
        wait(10_000) { pending(tag).singleOrNull()?.constraints?.requiredNetworkType == NetworkType.CONNECTED }
        val after = pending(tag).single()
        assertTrue("Changing network policy must keep server/backoff delay", after.nextScheduleTimeMillis >= deadline - 1_000)
        assertEquals(requests, origin.lists.get())
        assertEquals(SyncState.PAUSED, read { graph.library.getCollection(tags[1]) }!!.syncState)
        assertTrue(pending(tags[1]).isEmpty())
        assertEquals(0L, read { graph.library.getCollection(tag) }!!.lastSeenPostId)
        writeTestText(app, "controlled", "network-policy-result.txt", "PASS: existing delayed work adopted CONNECTED without early retry, watermark advance or unpausing another subscription")
    }

    @Test fun unavailableOldFolderCanBeDisconnectedAndDownloadsRecoverThroughSettings() {
        val tag = tags[2]
        read {
            graph.settings.save(AppSettings(existingDownloadsTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMissing%20Rule34%20Old%20Folder"))
            graph.library.addCollection(tag, 0)
            graph.background.sync(tag)
        }
        wait { read { graph.library.getCollection(tag) }?.syncState in setOf(SyncState.BLOCKED, SyncState.ERROR) && pending(tag).isEmpty() }
        ui.onNodeWithTag("nav-settings").performClick()
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("unlink-downloads"))
        ui.onNodeWithTag("unlink-downloads").performClick()
        wait(60_000) { read { graph.library.getCollection(tag) }?.syncState == SyncState.COMPLETE && pending(tag).isEmpty() }
        assertNull(read { graph.settings.snapshot() }.existingDownloadsTreeUri)
        verify(tag, 3)
        writeTestText(app, "controlled", "unlink-folder-result.txt", "PASS: missing/revoked old directory did not require reinstall; user disconnected it in Settings and all 3 originals downloaded and verified")
    }

    @Test fun stricterNetworkPolicyRestartsActiveTransferWithoutDuplicateOrPartialFiles() {
        val tag = tags[4]
        val transfers = origin.totalOriginals()
        origin.mediaPauseId.set(1)
        read { graph.library.addCollection(tag, 0); graph.background.sync(tag) }
        wait { read { graph.library.records(tag) }.any { it.bytesDownloaded > 0 } && pending(tag).any { it.state == WorkInfo.State.RUNNING } }
        read {
            graph.settings.save(AppSettings(wifiOnly = true))
            graph.background.updateSchedule()
        }
        complete(tag)
        verify(tag, 3)
        assertEquals(transfers + 4, origin.totalOriginals())
        assertEquals(3, read { graph.library.records(tag) }.map { it.localUri }.distinct().size)
        app.contentResolver.query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Downloads._ID, android.provider.MediaStore.Downloads.IS_PENDING),
            android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ? AND owner_package_name = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("Download/Rule34 Downloader/$tag/", app.packageName))
                putInt(android.provider.MediaStore.QUERY_ARG_MATCH_PENDING, android.provider.MediaStore.MATCH_INCLUDE)
            }, null)!!.use { cursor ->
            assertEquals(3, cursor.count)
            while (cursor.moveToNext()) assertEquals(0, cursor.getInt(1))
        }
        writeTestText(app, "controlled", "active-network-policy-result.txt", "PASS: restricting a real active HTTP transfer changed its WorkManager generation safely; exactly one interrupted original restarted; all 3 published files match MD5; no duplicate or pending MediaStore rows")
    }

    @Test fun genuinelyNewPostIsIncrementalAndRemoveReaddReusesFiles() {
        val tag = tags[3]
        origin.visibleCounts[tag] = 2
        val transfers = origin.totalOriginals()
        read { graph.library.addCollection(tag, 0); graph.background.sync(tag) }
        complete(tag)
        verify(tag, 2)
        val oldUris = read { graph.library.records(tag) }.associate { it.postId to it.localUri }
        assertEquals(transfers + 2, origin.totalOriginals())
        origin.visibleCounts[tag] = 3
        read { graph.background.sync(tag) }
        complete(tag)
        verify(tag, 3)
        assertEquals(3L, read { graph.library.getCollection(tag) }!!.lastSeenPostId)
        assertEquals(transfers + 3, origin.totalOriginals())
        assertEquals(oldUris, read { graph.library.records(tag) }.filter { it.postId <= 2 }.associate { it.postId to it.localUri })
        val allUris = read { graph.library.records(tag) }.map { it.localUri }
        read { graph.background.sync(tag) }; complete(tag)
        read { graph.background.rescan(tag) }; complete(tag)
        assertEquals(allUris, read { graph.library.records(tag) }.map { it.localUri })
        assertEquals(transfers + 3, origin.totalOriginals())
        read { graph.background.remove(tag) }
        assertNull(read { graph.library.getCollection(tag) })
        assertEquals(0, read { graph.library.gallery(GalleryQuery(), 0, 10) }.totalCount)
        allUris.forEach { assertNotNull(app.contentResolver.openInputStream(Uri.parse(it)).use { stream -> stream?.read() }) }
        read { graph.library.addCollection(tag, 0); graph.background.sync(tag) }; complete(tag)
        verify(tag, 3)
        assertEquals(allUris, read { graph.library.records(tag) }.map { it.localUri })
        assertEquals(transfers + 3, origin.totalOriginals())
        assertEquals(0, origin.authenticationLeaks.get())
        writeTestText(app, "controlled", "incremental-result.txt", "PASS: 2 -> 3 posts transferred exactly 1 new original; unchanged incremental sync, full rescan and remove/readd reused every URI and original; hashes read back; no credentials leaked to media")
    }

    private fun pending(tag: String) = manager.getWorkInfosForUniqueWork("rule34-sync-$tag").get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }
    private fun complete(tag: String) = wait(90_000) { read { graph.library.getCollection(tag) }?.syncState == SyncState.COMPLETE && pending(tag).isEmpty() }
    private fun verify(tag: String, count: Int) {
        val records = read { graph.library.records(tag) }
        assertEquals(count, records.size)
        assertTrue(records.all { it.status == DownloadStatus.DOWNLOADED })
        for (record in records) assertEquals(origin.hashes.getValue(record.postId), TestApiOrigin.md5(app.contentResolver.openInputStream(Uri.parse(record.localUri!!))!!.use { it.readBytes() }))
    }
    private fun wait(timeout: Long = 30_000, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (!predicate()) { check(System.nanoTime() < until) { "Business flow did not converge: ${read { graph.library.getCollections() }}" }; Thread.sleep(50) }
    }
}
