package com.homura251.rule34downloader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.homura251.rule34downloader.data.room.LibraryDatabase
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hash = "0123456789abcdef0123456789abcdef"

    @Test fun aSecondRunnerCannotStealAnActiveLease() = runBlocking {
        val name = "exclusive-lease.db"; context.deleteDatabase(name)
        val db = LibraryDatabase.open(context, name)
        try {
            val repo = RoomLibraryRepository(db)
            repo.addCollection("artist", 0)
            assertTrue(repo.beginRun("artist", "first"))
            assertFalse(repo.beginRun("artist", "second"))
            assertEquals("first", db.libraryDao().collection("artist")!!.runId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun schemaSixMigratesWithoutLosingItsUrisOrWatermark() = runBlocking {
        val name = "schema-six.db"; context.deleteDatabase(name)
        val schema = java.io.File(requireNotNull(System.getProperty("rule34.schemaDirectory")),
            "com.homura251.rule34downloader.data.room.LibraryDatabase/6.json").inputStream().use {
            JSONObject(it.bufferedReader().readText()).getJSONObject("database")
        }
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                for (i in 0 until (indices?.length() ?: 0)) db.execSQL(indices!!.getJSONObject(i).getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.execSQL("INSERT INTO collections (id, source_post_id, last_seen_post_id, user_paused, state, created_at, retry_attempts) VALUES ('artist', 1, 77, 1, 'PAUSED', 123, 0)")
            db.execSQL("INSERT INTO posts (id, file_url, md5, tags) VALUES (1, 'https://wimg.rule34.xxx/one.png', '$hash', 'artist')")
            db.execSQL("INSERT INTO collection_posts (collection_id, post_id) VALUES ('artist', 1)")
            db.execSQL("INSERT INTO download_tasks (collection_id, post_id, status, bytes_downloaded, total_bytes, attempts, updated_at) VALUES ('artist', 1, 'DOWNLOADED', 10, 10, 0, 123)")
            db.execSQL("INSERT INTO local_files (collection_id, post_id, uri, bytes, verified_md5) VALUES ('artist', 1, 'content://old/1', 10, '$hash')")
            db.version = 6
        }
        val db = LibraryDatabase.open(context, name)
        try {
            val repo = RoomLibraryRepository(db)
            val collection = repo.getCollection("artist")!!
            assertEquals(SyncMode.FULL, collection.syncMode)
            assertTrue(collection.paused)
            assertEquals(77L, collection.lastSeenPostId)
            assertEquals("content://old/1", repo.records("artist").single().localUri)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun knownFileModeSurvivesPauseAndReopenAndIsIndependentOfApiAuthentication() = runBlocking {
        val name = "persistent-mode.db"; context.deleteDatabase(name)
        var db = LibraryDatabase.open(context, name)
        try {
            var repo = RoomLibraryRepository(db)
            for (id in listOf("known", "api")) repo.addCollection(id, 0)
            repo.setSyncMode("known", SyncMode.KNOWN_FILES)
            repo.beginRun("known", "known-run"); repo.beginRun("api", "api-run")
            repo.savePage("known", "known-run", listOf(post(1)), ScanCheckpoint(0, 1, 1))
            repo.markDownloading("known", "known-run", 1, 10)
            repo.suspendForAuthentication()
            assertEquals("known-run", db.libraryDao().collection("known")!!.runId)
            assertEquals(DownloadStatus.DOWNLOADING, repo.records("known").single().status)
            assertEquals(SyncState.WAITING_AUTH, repo.getCollection("api")!!.syncState)
            repo.requestPause("known"); repo.endRun("known", "known-run")
            db.close(); db = LibraryDatabase.open(context, name); repo = RoomLibraryRepository(db)
            assertEquals(SyncState.PAUSED, repo.getCollection("known")!!.syncState)
            assertEquals(SyncMode.KNOWN_FILES, repo.getCollection("known")!!.syncMode)
            assertTrue(repo.resume("known"))
            assertEquals(SyncMode.KNOWN_FILES, repo.getCollection("known")!!.syncMode)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun legacyDownloadSettingsMigrateAndFolderSelectionPersists() = runBlocking {
        val legacy = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        legacy.edit().putBoolean("auto_sync_enabled", true).putLong("sync_interval_minutes", 60)
            .putBoolean("wifi_only", true).putString("existing_downloads_tree_uri", "content://legacy/tree/root").commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = DataStoreSettingsRepository(context, scope)
            assertEquals(AppSettings(true, 60, true, "content://legacy/tree/root"), repo.snapshot())
            repo.save(repo.snapshot().copy(syncIntervalMinutes = 1, existingDownloadsTreeUri = "content://new/tree/child"))
            assertEquals(AppSettings(true, 15, true, "content://new/tree/child"), repo.snapshot())
            assertFalse(legacy.contains("existing_downloads_tree_uri"))
        } finally { scope.cancel() }
    }

    @Test fun galleryFiltersPageDistinctPostsAndPreferLocalCompletion() = runBlocking {
        val name = "gallery.db"; context.deleteDatabase(name)
        val db = LibraryDatabase.open(context, name)
        try {
            val repo = RoomLibraryRepository(db)
            for (id in listOf("one", "two")) { repo.addCollection(id, 0); repo.beginRun(id, id) }
            val posts = listOf(post(1), post(2).copy(fileUrl = "https://wimg.rule34.xxx/movie.M4V?token=fixture"), post(3))
            repo.savePage("one", "one", posts, ScanCheckpoint(0, 1, 3))
            repo.savePage("two", "two", listOf(post(1)), ScanCheckpoint(0, 1, 1))
            repo.markFailed("one", "one", 1, "bad copy")
            repo.markDownloaded("two", "two", 1, SavedFile("content://local/two/1", 10, hash))
            val page = repo.gallery(GalleryQuery(), 0, 2)
            assertEquals(3, page.totalCount)
            assertEquals(listOf(3L, 2L), page.posts.map { it.postId })
            val next = repo.gallery(GalleryQuery(), 2, 2).posts.single()
            assertEquals(DownloadStatus.DOWNLOADED, next.status)
            assertEquals("content://local/two/1", next.localUri)
            assertNull(next.error)
            assertEquals(listOf(2L), repo.gallery(GalleryQuery(filter = GalleryFilter.VIDEOS), 0, 60).posts.map { it.postId })
            assertEquals(listOf(3L, 1L), repo.gallery(GalleryQuery(filter = GalleryFilter.IMAGES), 0, 60).posts.map { it.postId })
            assertEquals(listOf(1L), repo.gallery(GalleryQuery(filter = GalleryFilter.DOWNLOADED), 0, 60).posts.map { it.postId })
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun failuresKeepCollectionIdentityAndSelectedRetryDoesNotResetOtherFiles() = runBlocking {
        val name = "file-failures.db"; context.deleteDatabase(name)
        var db = LibraryDatabase.open(context, name)
        try {
            var repo = RoomLibraryRepository(db)
            for (id in listOf("one", "two")) {
                repo.addCollection(id, 0); repo.beginRun(id, id)
                repo.savePage(id, id, listOf(post(1).copy(md5 = ""), post(2)), ScanCheckpoint(0, 1, 2))
                repeat(3) { repo.markFailed(id, id, 1, "HTTP 403") }
                repo.markFailed(id, id, 2, "network interrupted", countAttempt = false)
                repo.endRun(id, id)
            }
            assertEquals(4, repo.failedDownloads(null, 0, 1).totalCount)
            assertEquals(1, repo.failedDownloads(null, 0, 1).files.size)
            assertEquals(2, repo.failedDownloads("one", 0, 20).totalCount)
            db.close(); db = LibraryDatabase.open(context, name); repo = RoomLibraryRepository(db)
            assertEquals(4, repo.failedDownloads(null, 0, 20).files.size)
            repo.retryFiles(listOf(DownloadFileKey("one", 1)))
            assertEquals(0, repo.records("one").single { it.postId == 1L }.attempts)
            assertEquals(3, repo.records("two").single { it.postId == 1L }.attempts)
            assertEquals(0, repo.records("two").single { it.postId == 2L }.attempts)
            repo.beginRun("one", "success")
            repo.markDownloaded("one", "success", 1, SavedFile("content://new/1", 10))
            assertNull(repo.records("one").single { it.postId == 1L }.verifiedMd5)
            assertEquals(3, repo.failedDownloads(null, 0, 20).totalCount)
            assertEquals("HTTP 403", repo.failedDownloads("two", 0, 20).files.single { it.key.postId == 1L }.post.error)
        } finally { db.close(); context.deleteDatabase(name) }
    }

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
                assertEquals(DownloadStatus.DOWNLOADED, saved.status)
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
            assertFalse(repo.beginRun("artist", "new"))
            repo.endRun("artist", "old")
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

    @Test fun manualRescanResetsDiscoveryAtomicallyAndPreservesEverySavedFile() = runBlocking {
        val name = "rescan.db"; context.deleteDatabase(name)
        val db = LibraryDatabase.open(context, name)
        try {
            val repo = RoomLibraryRepository(db)
            repo.addCollection("artist", 0); repo.beginRun("artist", "run")
            repo.savePage("artist", "run", listOf(post(1), post(2)), ScanCheckpoint(0, 1, 2))
            repo.commitScan("artist", "run", 2)
            repo.savePage("artist", "run", listOf(post(3)), ScanCheckpoint(2, 3, 3))
            repo.markDownloaded("artist", "run", 1, SavedFile("content://old/1", 10, hash))
            repo.markFailed("artist", "run", 2, "old failure")
            assertFalse(repo.restartScan("artist"))
            assertEquals(2L, repo.getCollection("artist")!!.lastSeenPostId)
            assertNotNull(repo.checkpoint("artist"))
            repo.endRun("artist", "run")
            assertTrue(repo.restartScan("artist"))
            assertEquals(0L, repo.getCollection("artist")!!.lastSeenPostId)
            assertNull(repo.checkpoint("artist"))
            val rows = repo.records("artist")
            assertEquals(listOf(1L, 2L, 3L), rows.map { it.postId })
            assertEquals("content://old/1", rows.first().localUri)
            assertEquals(DownloadStatus.DOWNLOADED, rows.first().status)
            assertEquals(0, rows[1].attempts)
            repo.requestPause("artist")
            assertFalse(repo.restartScan("artist"))
            assertTrue(repo.getCollection("artist")!!.paused)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun invalidatingALegacyFileKeepsItsTrustedChecksumForDownloadRecovery() = runBlocking {
        val name = "verified-metadata.db"; context.deleteDatabase(name)
        val db = LibraryDatabase.open(context, name)
        try {
            val repo = RoomLibraryRepository(db)
            repo.addCollection("artist", 0); repo.beginRun("artist", "run")
            repo.savePage("artist", "run", listOf(post(1).copy(md5 = "", fileUrl = "https://wimg.rule34.xxx/old/original.png")), ScanCheckpoint(0, 1, 1))
            repo.markDownloaded("artist", "run", 1, SavedFile("content://missing", 10, hash))
            repo.invalidateFile("artist", "run", 1)
            val record = repo.records("artist").single()
            assertEquals(hash, record.md5)
            assertNull(record.localUri)
            assertEquals(DownloadStatus.PENDING, record.status)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun anUnavailablePostIsSavedAsItsOwnFailureWithoutDiscardingHealthyMetadata() = runBlocking {
        val name = "unavailable.db"; context.deleteDatabase(name)
        val db = LibraryDatabase.open(context, name)
        try {
            val repo = RoomLibraryRepository(db)
            repo.addCollection("artist", 0); repo.beginRun("artist", "run")
            repo.savePage("artist", "run", listOf(post(1)), ScanCheckpoint(0, 1, 1))
            repo.markDownloaded("artist", "run", 1, SavedFile("content://old/1", 10, hash))
            val unavailable = post(2).copy(fileUrl = "", availabilityError = "Original temporarily unavailable")
            repo.savePage("artist", "run", listOf(unavailable, post(1).copy(fileUrl = "", availabilityError = "unavailable")), ScanCheckpoint(0, 1, 2))
            val records = repo.records("artist")
            assertEquals(listOf(1L, 2L), records.map { it.postId })
            assertEquals("content://old/1", records.first().localUri)
            assertEquals(post(1).fileUrl, records.first().fileUrl)
            assertEquals(DownloadStatus.DOWNLOADED, records.first().status)
            assertEquals(DownloadStatus.FAILED, records.last().status)
            assertNotNull(records.last().error)
            assertEquals(ScanCheckpoint(0, 1, 2), repo.checkpoint("artist"))
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
