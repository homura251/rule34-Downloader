package com.homura251.rule34downloader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.homura251.rule34downloader.data.room.LibraryDatabase
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hash = "0123456789abcdef0123456789abcdef"

    @Test fun allFiveLegacyVersionsPreserveUrisPauseIntentAndPoolOrdering() = runBlocking {
        for (version in 1..5) {
            val name = "legacy-$version.db"
            context.deleteDatabase(name)
            createLegacy(name, version)
            val db = LibraryDatabase.open(context, name)
            try {
                val repo = RoomLibraryRepository(db)
                // The first query opens Room and validates the migrated schema against all six entities.
                val artist = repo.getCollection("artist")!!
                assertEquals(7L, artist.lastSeenPostId)
                assertEquals(version >= 3, artist.paused)
                assertEquals(if (version >= 3) SyncState.PAUSED else SyncState.IDLE, artist.syncState)
                val records = repo.records("artist")
                val saved = records.single { it.postId == 1L }
                assertEquals("content://legacy/artist/1", saved.localUri)
                assertEquals(if (version == 5) hash else null, saved.verifiedMd5)
                assertEquals(if (version == 5) DownloadStatus.DOWNLOADED else DownloadStatus.PENDING, saved.status)
                val partial = records.single { it.postId == 3L }
                assertEquals(DownloadStatus.PENDING, partial.status)
                assertEquals(0L, partial.bytesDownloaded)
                assertNull(db.libraryDao().collection("artist")!!.runId)
                if (version >= 4) {
                    val pool = repo.getCollection("pool_42")!!
                    assertEquals(SyncState.ARCHIVED, pool.syncState)
                    assertEquals("Legacy ordered pool", pool.displayName)
                    val gallery = repo.gallery(GalleryQuery("pool_42"), 0, 60)
                    assertEquals(listOf(2L, 1L), gallery.posts.map { it.postId })
                    assertEquals("content://legacy/pool/1", gallery.posts.last().localUri)
                    assertFalse(repo.beginRun("pool_42", "disallowed"))
                }
                val global = repo.gallery(GalleryQuery(), 0, 60)
                assertEquals(global.posts.size, global.posts.map { it.postId }.distinct().size)
                // Cascades remove membership, tasks and file references without touching a second collection.
                repo.removeCollection("artist")
                assertTrue(repo.records("artist").isEmpty())
                if (version >= 4) assertEquals(2, repo.records("pool_42").size)
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun obsoleteRunCannotOverwriteItsReplacementAndPublishedFileSurvivesPause() = runBlocking {
        val name = "lease.db"; context.deleteDatabase(name)
        val db = LibraryDatabase.open(context, name)
        try {
            val repo = RoomLibraryRepository(db)
            repo.addCollection("artist", 0)
            assertTrue(repo.beginRun("artist", "old"))
            repo.savePage("artist", "old", listOf(post(1)), ScanCheckpoint(0, 1, 1))
            assertTrue(repo.beginRun("artist", "new"))
            repo.savePage("artist", "old", listOf(post(2)), ScanCheckpoint(0, 2, 2))
            repo.commitScan("artist", "old", 999)
            repo.markDownloaded("artist", "old", 1, SavedFile("content://obsolete", 10, hash))
            repo.setState("artist", "old", SyncState.COMPLETE)
            repo.endRun("artist", "old")
            assertEquals("new", db.libraryDao().collection("artist")!!.runId)
            assertEquals(listOf(1L), repo.records("artist").map { it.postId })
            assertEquals(0L, repo.getCollection("artist")!!.lastSeenPostId)
            assertNull(repo.records("artist").single().localUri)
            repo.markDownloading("artist", "new", 1, 10)
            repo.requestPause("artist")
            repo.markDownloaded("artist", "new", 1, SavedFile("content://published", 10, hash))
            repo.endRun("artist", "new")
            assertEquals(SyncState.PAUSED, repo.getCollection("artist")!!.syncState)
            assertEquals("content://published", repo.records("artist").single().localUri)
            repo.recoverInterruptedRuns()
            assertTrue(repo.getCollection("artist")!!.paused)
            assertEquals(DownloadStatus.DOWNLOADED, repo.records("artist").single().status)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun pageFailureRollsBackMetadataQueueAndCheckpointTogether() = runBlocking {
        val name = "atomic.db"; context.deleteDatabase(name)
        val db = LibraryDatabase.open(context, name)
        try {
            val repo = RoomLibraryRepository(db)
            repo.addCollection("artist", 0); repo.beginRun("artist", "run")
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_second BEFORE INSERT ON download_tasks
                WHEN NEW.post_id = 2 BEGIN SELECT RAISE(ABORT, 'test interruption'); END""")
            var interrupted = false
            try { repo.savePage("artist", "run", listOf(post(1), post(2)), ScanCheckpoint(0, 1, 2)) }
            catch (_: Exception) { interrupted = true }
            assertTrue(interrupted)
            assertTrue(repo.records("artist").isEmpty())
            assertNull(repo.checkpoint("artist"))
            assertNull(db.libraryDao().post(1))
            assertEquals(0L, repo.getCollection("artist")!!.lastSeenPostId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun post(id: Long) = Rule34Post(id, "https://wimg.rule34.xxx/$hash.png", hash, listOf("artist"))
    private fun createLegacy(name: String, version: Int) {
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val extraArtist = (if (version >= 3) ", sync_paused INTEGER NOT NULL DEFAULT 0" else "") +
                (if (version >= 4) ", pool_id INTEGER, display_name TEXT" else "")
            db.execSQL("""CREATE TABLE artists (tag TEXT PRIMARY KEY NOT NULL, source_post_id INTEGER NOT NULL,
                last_seen_post_id INTEGER NOT NULL DEFAULT 0, sync_state TEXT NOT NULL DEFAULT 'IDLE',
                current_post_id INTEGER, last_error TEXT, last_sync_at INTEGER, created_at INTEGER NOT NULL $extraArtist)""")
            val extraDownload = (if (version >= 2) ", preview_url TEXT" else "") + (if (version >= 5) ", verified_md5 TEXT" else "")
            db.execSQL("""CREATE TABLE downloads (artist_tag TEXT NOT NULL, post_id INTEGER NOT NULL,
                file_url TEXT NOT NULL, md5 TEXT NOT NULL DEFAULT '', status TEXT NOT NULL,
                bytes_downloaded INTEGER NOT NULL DEFAULT 0, total_bytes INTEGER NOT NULL DEFAULT 0,
                local_uri TEXT, error TEXT, updated_at INTEGER NOT NULL $extraDownload,
                PRIMARY KEY(artist_tag, post_id), FOREIGN KEY(artist_tag) REFERENCES artists(tag) ON DELETE CASCADE)""")
            fun artist(tag: String, pool: Boolean = false) {
                db.insertOrThrow("artists", null, ContentValues().apply {
                    put("tag", tag); put("source_post_id", 1); put("last_seen_post_id", 7)
                    put("sync_state", "SYNCING"); put("created_at", 123)
                    if (version >= 3) put("sync_paused", if (pool) 0 else 1)
                    if (pool) { put("pool_id", 42); put("display_name", "Legacy ordered pool") }
                })
            }
            fun file(tag: String, id: Long, status: String, uri: String?) {
                db.insertOrThrow("downloads", null, ContentValues().apply {
                    put("artist_tag", tag); put("post_id", id); put("file_url", "https://wimg.rule34.xxx/$hash.png")
                    put("md5", hash); put("status", status); put("bytes_downloaded", 10); put("total_bytes", 20)
                    put("local_uri", uri); put("updated_at", 456)
                    if (version >= 2) put("preview_url", "https://wimg.rule34.xxx/preview.png")
                    if (version >= 5 && uri != null) put("verified_md5", hash)
                })
            }
            artist("artist"); file("artist", 1, "DOWNLOADED", "content://legacy/artist/1"); file("artist", 3, "DOWNLOADING", null)
            if (version >= 4) {
                db.execSQL("CREATE TABLE pool_posts (pool_tag TEXT NOT NULL, post_id INTEGER NOT NULL, position INTEGER NOT NULL, preview_url TEXT, PRIMARY KEY(pool_tag,post_id))")
                artist("pool_42", true)
                file("pool_42", 1, "DOWNLOADED", "content://legacy/pool/1"); file("pool_42", 2, "PENDING", null)
                db.execSQL("INSERT INTO pool_posts VALUES ('pool_42', 2, 0, NULL), ('pool_42', 1, 1, NULL)")
            }
            db.version = version
        }
    }
}
