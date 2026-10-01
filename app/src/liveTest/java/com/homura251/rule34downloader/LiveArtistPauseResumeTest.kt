package com.homura251.rule34downloader

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.homura251.rule34downloader.data.*
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.storage.MediaStoreDownloader
import com.homura251.rule34downloader.work.SyncScheduler
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Actual source, production browser/downloader/worker, actual pause and resume buttons. */
@RunWith(AndroidJUnit4::class)
class LiveArtistPauseResumeTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun actualArtistOriginalsSurviveUiPauseAndResume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Rule34Database.getInstance(context)
        assertFalse(CredentialsStore(context).isConfigured())
        AppPreferences(context).apply { autoSyncEnabled = false; wifiOnly = false }
        lateinit var network: Rule34Network
        InstrumentationRegistry.getInstrumentation().runOnMainSync { network = Rule34Network.get(context) }
        val source = InstrumentationRegistry.getArguments().getString("livePostId")?.toLong() ?: 18905312L
        val artist = network.htmlClient().getPostWithArtists(source).artists.first()
        val tag = artist.name
        assertFalse(database.hasArtist(tag))
        val relative = MediaStoreDownloader.buildRelativePath(tag)
        var scenario: ActivityScenario<MainActivity>? = null
        fun count() = database.getSavedRecords(tag).count { it.status == DownloadStatus.DOWNLOADED }
        fun finished() = WorkManager.getInstance(context).getWorkInfosForUniqueWork("rule34-sync-$tag")
            .get(10, TimeUnit.SECONDS).all { it.state.isFinished }
        fun waitFor(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            while (!predicate()) {
                if (database.getSyncState(tag) == SyncState.ERROR) error("Actual source failed: sync state ERROR; ${network.browserDiagnostics}")
                check(System.nanoTime() < deadline) { "Actual artist timeout; completed=${count()}; ${network.browserDiagnostics}" }
                Thread.sleep(100)
            }
        }
        fun waitTag(value: String) = ui.waitUntil(20_000) { ui.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty() }
        fun pause() {
            assertEquals(SyncState.SYNCING, database.getSyncState(tag))
            waitTag("sync-$tag")
            ui.onNodeWithTag("sync-$tag").performScrollTo().assertIsEnabled().performClick()
            waitFor { database.getSyncState(tag) == SyncState.PAUSED && finished() }
            assertTrue(database.isPaused(tag))
            assertTrue(database.getSavedRecords(tag).none { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.FAILED })
            MediaStoreDownloader.queryIncludingPending(context.contentResolver,
                "relative_path = ? AND is_pending = 1 AND owner_package_name = ?", arrayOf(relative, context.packageName))!!.use { assertEquals(0, it.count) }
        }
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitTag("add-author"); ui.onNodeWithTag("add-author").performClick()
            waitTag("author-input"); ui.onNodeWithTag("author-input").performTextInput(tag)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            ui.onNodeWithText("继续").performClick()
            ui.waitUntil(20_000) { ui.onAllNodesWithText("添加并下载").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("添加并下载").performClick()
            waitFor { count() >= 3 }
            pause()
            assertEquals(0L, database.getArtist(tag)!!.lastSeenPostId)
            val kept = database.getSavedRecords(tag).filter { it.status == DownloadStatus.DOWNLOADED }.associate { it.postId to it.localUri }
            val before = count()
            ui.onNodeWithTag("sync-$tag").performScrollTo().assertIsEnabled().performClick()
            waitFor { !database.isPaused(tag) && count() >= before + 3 }
            pause()
            val records = database.getSavedRecords(tag).filter { it.status == DownloadStatus.DOWNLOADED }
            val after = records.associate { it.postId to it.localUri }
            kept.forEach { (id, uri) -> assertEquals(uri, after[id]) }
            var total = 0L
            for (record in records) {
                val digest = MessageDigest.getInstance("MD5")
                var size = 0L
                context.contentResolver.openInputStream(Uri.parse(record.localUri!!))!!.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); size += n }
                }
                assertEquals(record.bytesDownloaded, size)
                assertEquals(record.verifiedMd5, digest.digest().joinToString("") { "%02x".format(it) })
                total += size
            }
            writeTestText(context, "live", "actual-ui-pause-resume.txt",
                "PASS actual anonymous artist UI -> production WorkManager -> actual originals -> pause -> resume -> pause\n" +
                    "Artist: $tag; site count: ${artist.count}\nDownloaded and individually read-back verified: ${records.size}\nBytes: $total\n" +
                    "All ${kept.size} completed URIs before pause retained; no incomplete published files\n" +
                    "This is a sample batch, not the full artist collection\n")
        } catch (error: Throwable) {
            writeTestText(context, "live", "actual-ui-pause-resume.txt",
                "FAILED actual anonymous artist UI batch\nArtist: $tag; completed: ${count()}\n$error\n${network.browserDiagnostics}\n")
            throw error
        } finally {
            SyncScheduler.cancelArtistSync(context, tag)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!finished() && System.nanoTime() < deadline) Thread.sleep(100)
            scenario?.close()
            MediaStoreDownloader.queryIncludingPending(context.contentResolver, "relative_path = ? AND owner_package_name = ?",
                arrayOf(relative, context.packageName))?.use { cursor ->
                while (cursor.moveToNext()) context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)), null, null)
            }
            database.removeArtist(tag)
        }
    }
}
