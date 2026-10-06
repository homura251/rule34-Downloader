package com.homura251.rule34downloader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.homura251.rule34downloader.network.PoolPost
import com.homura251.rule34downloader.storage.FileChecksum

class Rule34Database private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val changes = MutableStateFlow(0L)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE artists (
                tag TEXT PRIMARY KEY NOT NULL,
                source_post_id INTEGER NOT NULL,
                last_seen_post_id INTEGER NOT NULL DEFAULT 0,
                sync_state TEXT NOT NULL DEFAULT 'IDLE',
                sync_paused INTEGER NOT NULL DEFAULT 0,
                pool_id INTEGER,
                display_name TEXT,
                current_post_id INTEGER,
                last_error TEXT,
                last_sync_at INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE downloads (
                artist_tag TEXT NOT NULL,
                post_id INTEGER NOT NULL,
                file_url TEXT NOT NULL,
                preview_url TEXT,
                md5 TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL,
                bytes_downloaded INTEGER NOT NULL DEFAULT 0,
                total_bytes INTEGER NOT NULL DEFAULT 0,
                local_uri TEXT,
                verified_md5 TEXT,
                error TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (artist_tag, post_id),
                FOREIGN KEY (artist_tag) REFERENCES artists(tag) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_downloads_artist_status ON downloads(artist_tag, status)")
        createPoolTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE downloads ADD COLUMN preview_url TEXT")
        if (oldVersion < 3) db.execSQL("ALTER TABLE artists ADD COLUMN sync_paused INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE artists ADD COLUMN pool_id INTEGER")
            db.execSQL("ALTER TABLE artists ADD COLUMN display_name TEXT")
            createPoolTable(db)
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN verified_md5 TEXT")
            // Keep original URIs/files, but recheck completions made by older versions.
            db.execSQL("UPDATE downloads SET status = 'PENDING' WHERE status = 'DOWNLOADED'")
        }
    }

    private fun createPoolTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE pool_posts (
                pool_tag TEXT NOT NULL,
                post_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                preview_url TEXT,
                PRIMARY KEY (pool_tag, post_id),
                FOREIGN KEY (pool_tag) REFERENCES artists(tag) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_pool_posts_order ON pool_posts(pool_tag, position)")
    }

    fun observeArtistSummaries(): Flow<List<ArtistSummary>> =
        changes
            .map { listArtistSummaries() }
            .flowOn(Dispatchers.IO)

    fun observeGallery(tag: String, limit: Int): Flow<GalleryPage> = changes
        .map { getGalleryPage(tag, limit) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    private fun getGalleryPage(tag: String, limit: Int): GalleryPage {
        val db = readableDatabase
        val artist = getArtist(tag)
        val pool = artist?.poolId != null
        val join = if (pool) " JOIN pool_posts p ON p.pool_tag = d.artist_tag AND p.post_id = d.post_id" else ""
        val preview = if (pool) "COALESCE(p.preview_url, d.preview_url)" else "d.preview_url"
        val order = if (pool) "p.position ASC" else "d.post_id DESC"
        val count = db.rawQuery("SELECT COUNT(*) FROM downloads d$join WHERE d.artist_tag = ?", arrayOf(tag))
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        val posts = db.rawQuery(
            """
            SELECT d.artist_tag, d.post_id, d.file_url, $preview, d.local_uri, d.status, d.error
            FROM downloads d$join WHERE d.artist_tag = ? ORDER BY $order LIMIT ?
            """.trimIndent(),
            arrayOf(tag, limit.coerceAtLeast(1).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(GalleryPost(
                    artistTag = cursor.getString(0),
                    postId = cursor.getLong(1),
                    fileUrl = cursor.getString(2),
                    previewUrl = if (cursor.isNull(3)) null else cursor.getString(3),
                    localUri = if (cursor.isNull(4)) null else cursor.getString(4),
                    status = DownloadStatus.valueOf(cursor.getString(5)),
                    error = if (cursor.isNull(6)) null else cursor.getString(6),
                ))
            }
        }
        return GalleryPage(posts, count, artist?.displayName ?: tag, artist?.poolId)
    }

    @Synchronized
    fun addArtist(tag: String, sourcePostId: Long, poolId: Long? = null, displayName: String? = null): Boolean {
        val values = ContentValues().apply {
            put("tag", tag)
            put("source_post_id", sourcePostId)
            if (poolId != null) put("pool_id", poolId)
            if (displayName != null) put("display_name", displayName)
            put("last_seen_post_id", 0L)
            put("sync_state", SyncState.IDLE.name)
            put("created_at", System.currentTimeMillis())
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "artists",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        if (inserted) signalChanged()
        return inserted
    }

    @Synchronized
    fun removeArtist(tag: String) {
        writableDatabase.delete("artists", "tag = ?", arrayOf(tag))
        signalChanged()
    }

    fun hasArtist(tag: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM artists WHERE tag = ? LIMIT 1",
        arrayOf(tag),
    ).use { it.moveToFirst() }

    fun getArtist(tag: String): ArtistRecord? = readableDatabase.rawQuery(
        "SELECT tag, source_post_id, last_seen_post_id, sync_paused, pool_id, display_name FROM artists WHERE tag = ? LIMIT 1",
        arrayOf(tag),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        ArtistRecord(
            tag = cursor.getString(0),
            sourcePostId = cursor.getLong(1),
            lastSeenPostId = cursor.getLong(2),
            paused = cursor.getInt(3) != 0,
            poolId = if (cursor.isNull(4)) null else cursor.getLong(4),
            displayName = if (cursor.isNull(5)) null else cursor.getString(5),
        )
    }

    fun getArtistTags(): List<String> = readableDatabase.rawQuery(
        "SELECT tag FROM artists ORDER BY created_at ASC",
        emptyArray(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    fun getKnownPostIds(tag: String): Set<Long> = readableDatabase.rawQuery(
        "SELECT post_id FROM downloads WHERE artist_tag = ?", arrayOf(tag),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    @Synchronized
    fun replacePoolMembership(tag: String, title: String, posts: List<PoolPost>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("pool_posts", "pool_tag = ?", arrayOf(tag))
            db.compileStatement("INSERT INTO pool_posts(pool_tag, post_id, position, preview_url) VALUES (?, ?, ?, ?)").use { statement ->
                posts.forEachIndexed { index, post ->
                    statement.clearBindings()
                    statement.bindString(1, tag)
                    statement.bindLong(2, post.id)
                    statement.bindLong(3, index.toLong())
                    if (post.previewUrl == null) statement.bindNull(4) else statement.bindString(4, post.previewUrl)
                    statement.executeInsert()
                }
            }
            db.update("artists", ContentValues().apply { put("display_name", title) }, "tag = ?", arrayOf(tag))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        signalChanged()
    }

    @Synchronized
    fun setSyncState(
        tag: String,
        state: SyncState,
        currentPostId: Long? = null,
        error: String? = null,
        markSyncTime: Boolean = false,
    ): Boolean {
        val values = ContentValues().apply {
            put("sync_state", state.name)
            if (currentPostId == null) putNull("current_post_id") else put("current_post_id", currentPostId)
            if (error == null) putNull("last_error") else put("last_error", error.take(500))
            if (markSyncTime) put("last_sync_at", System.currentTimeMillis())
        }
        val updated = writableDatabase.update("artists", values, "tag = ? AND sync_paused = 0", arrayOf(tag))
        if (updated > 0) signalChanged()
        return updated > 0
    }

    fun isPaused(tag: String): Boolean = getArtist(tag)?.paused == true

    fun getSyncState(tag: String): SyncState? = readableDatabase.rawQuery(
        "SELECT sync_state FROM artists WHERE tag = ?", arrayOf(tag),
    ).use { if (it.moveToFirst()) SyncState.valueOf(it.getString(0)) else null }

    @Synchronized
    fun requestPause(tag: String) {
        writableDatabase.execSQL("UPDATE artists SET sync_paused = 1, sync_state = 'PAUSING', last_error = NULL WHERE tag = ?", arrayOf(tag))
        signalChanged()
    }

    @Synchronized
    fun finishPaused(tag: String) {
        resetInProgress(tag)
        writableDatabase.execSQL("UPDATE artists SET sync_state = 'PAUSED', current_post_id = NULL WHERE tag = ? AND sync_paused = 1", arrayOf(tag))
        signalChanged()
    }

    @Synchronized
    fun resumeSync(tag: String): Boolean {
        val values = ContentValues().apply { put("sync_paused", 0); put("sync_state", "IDLE"); putNull("last_error") }
        val count = writableDatabase.update("artists", values, "tag = ? AND sync_paused = 1 AND sync_state = 'PAUSED'", arrayOf(tag))
        if (count > 0) signalChanged()
        return count > 0
    }

    @Synchronized
    fun resetInProgress(tag: String) {
        writableDatabase.execSQL("UPDATE downloads SET status = 'PENDING', bytes_downloaded = 0, error = NULL WHERE artist_tag = ? AND status = 'DOWNLOADING'", arrayOf(tag))
        signalChanged()
    }

    @Synchronized
    fun updateLastSeenPostId(tag: String, postId: Long) {
        writableDatabase.execSQL(
            "UPDATE artists SET last_seen_post_id = MAX(last_seen_post_id, ?) WHERE tag = ?",
            arrayOf<Any>(postId, tag),
        )
        signalChanged()
    }

    @Synchronized
    fun upsertDiscoveredPosts(tag: String, posts: List<Rule34Post>): Int {
        if (posts.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            val insert = db.compileStatement(
                """
                INSERT OR IGNORE INTO downloads(
                    artist_tag, post_id, file_url, md5, preview_url, status,
                    bytes_downloaded, total_bytes, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?)
                """.trimIndent(),
            )
            val now = System.currentTimeMillis()
            posts.forEach { post ->
                insert.clearBindings()
                insert.bindString(1, tag)
                insert.bindLong(2, post.id)
                insert.bindString(3, post.fileUrl)
                insert.bindString(4, post.md5)
                if (post.previewUrl == null) insert.bindNull(5) else insert.bindString(5, post.previewUrl)
                insert.bindString(6, DownloadStatus.PENDING.name)
                insert.bindLong(7, now)
                if (insert.executeInsert() != -1L) {
                    inserted++
                } else {
                    val values = ContentValues().apply {
                        put("file_url", post.fileUrl)
                        if (FileChecksum.normalize(post.md5) != null) put("md5", post.md5.lowercase())
                        if (!post.previewUrl.isNullOrBlank()) put("preview_url", post.previewUrl)
                        put("updated_at", now)
                    }
                    db.update(
                        "downloads",
                        values,
                        "artist_tag = ? AND post_id = ?",
                        arrayOf(tag, post.id.toString()),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        signalChanged()
        return inserted
    }

    @Deprecated("Use upsertDiscoveredPosts so API metadata can repair older records.")
    fun insertDiscoveredPosts(tag: String, posts: List<Rule34Post>): Int =
        upsertDiscoveredPosts(tag, posts)

    fun getDownloadQueue(tag: String): List<DownloadRecord> = getSavedRecords(tag, unfinishedOnly = true)

    fun getSavedRecords(tag: String, unfinishedOnly: Boolean = false): List<DownloadRecord> {
        val pool = getArtist(tag)?.poolId != null
        val join = if (pool) " JOIN pool_posts p ON p.pool_tag = d.artist_tag AND p.post_id = d.post_id" else ""
        val order = if (pool) "p.position ASC" else "d.post_id ASC"
        val filter = if (unfinishedOnly) " AND d.status IN ('PENDING', 'FAILED', 'DOWNLOADING')" else ""
        return readableDatabase.rawQuery(
        """
        SELECT d.artist_tag, d.post_id, d.file_url, d.md5, d.status, d.bytes_downloaded, d.total_bytes, d.local_uri, d.verified_md5
        FROM downloads d$join
        WHERE d.artist_tag = ?$filter
        ORDER BY $order
        """.trimIndent(),
        arrayOf(tag),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    DownloadRecord(
                        artistTag = cursor.getString(0),
                        postId = cursor.getLong(1),
                        fileUrl = cursor.getString(2),
                        md5 = cursor.getString(3),
                        status = DownloadStatus.valueOf(cursor.getString(4)),
                        bytesDownloaded = cursor.getLong(5),
                        totalBytes = cursor.getLong(6),
                        localUri = if (cursor.isNull(7)) null else cursor.getString(7),
                        verifiedMd5 = if (cursor.isNull(8)) null else cursor.getString(8),
                    ),
                )
            }
        }
    }
    }

    @Synchronized
    fun markDownloading(tag: String, postId: Long, totalBytes: Long) {
        val values = ContentValues().apply {
            put("status", DownloadStatus.DOWNLOADING.name)
            put("bytes_downloaded", 0L)
            put("total_bytes", totalBytes.coerceAtLeast(0L))
            putNull("error")
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            "downloads",
            values,
            "artist_tag = ? AND post_id = ?",
            arrayOf(tag, postId.toString()),
        )
        writableDatabase.execSQL(
            "UPDATE artists SET current_post_id = ? WHERE tag = ?",
            arrayOf<Any>(postId, tag),
        )
        signalChanged()
    }

    @Synchronized
    fun updateDownloadProgress(tag: String, postId: Long, bytesDownloaded: Long, totalBytes: Long) {
        val values = ContentValues().apply {
            put("bytes_downloaded", bytesDownloaded.coerceAtLeast(0L))
            if (totalBytes > 0) put("total_bytes", totalBytes)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            "downloads",
            values,
            "artist_tag = ? AND post_id = ?",
            arrayOf(tag, postId.toString()),
        )
        signalChanged()
    }

    @Synchronized
    fun markDownloaded(tag: String, postId: Long, localUri: String, bytes: Long, verifiedMd5: String) {
        require(bytes > 0 && FileChecksum.normalize(verifiedMd5) != null)
        val values = ContentValues().apply {
            put("status", DownloadStatus.DOWNLOADED.name)
            put("local_uri", localUri)
            put("bytes_downloaded", bytes)
            put("total_bytes", bytes)
            put("verified_md5", verifiedMd5.lowercase())
            putNull("error")
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            "downloads",
            values,
            "artist_tag = ? AND post_id = ?",
            arrayOf(tag, postId.toString()),
        )
        signalChanged()
    }

    @Synchronized
    fun invalidateSavedFile(tag: String, postId: Long) {
        writableDatabase.execSQL("UPDATE downloads SET status = 'PENDING', local_uri = NULL, verified_md5 = NULL, bytes_downloaded = 0, total_bytes = 0 WHERE artist_tag = ? AND post_id = ?", arrayOf<Any>(tag, postId))
        signalChanged()
    }

    @Synchronized
    fun markFailed(tag: String, postId: Long, error: String) {
        val values = ContentValues().apply {
            put("status", DownloadStatus.FAILED.name)
            put("error", error.take(500))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            "downloads",
            values,
            "artist_tag = ? AND post_id = ?",
            arrayOf(tag, postId.toString()),
        )
        signalChanged()
    }

    private fun listArtistSummaries(): List<ArtistSummary> = readableDatabase.rawQuery(
        """
        SELECT
            a.tag,
            a.source_post_id,
            a.last_seen_post_id,
            a.sync_state,
            a.current_post_id,
            a.last_error,
            a.last_sync_at,
            a.created_at,
            COALESCE(SUM(CASE WHEN d.status = 'DOWNLOADED' THEN 1 ELSE 0 END), 0) AS downloaded_count,
            COALESCE(SUM(CASE WHEN d.status IN ('PENDING', 'DOWNLOADING') THEN 1 ELSE 0 END), 0) AS pending_count,
            COALESCE(SUM(CASE WHEN d.status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count,
            COALESCE(MAX(CASE WHEN d.status = 'DOWNLOADING' THEN d.bytes_downloaded ELSE 0 END), 0) AS current_bytes,
            COALESCE(MAX(CASE WHEN d.status = 'DOWNLOADING' THEN d.total_bytes ELSE 0 END), 0) AS current_total,
            a.pool_id, a.display_name
        FROM artists a
        LEFT JOIN downloads d ON a.tag = d.artist_tag AND (
            a.pool_id IS NULL OR EXISTS (
                SELECT 1 FROM pool_posts p WHERE p.pool_tag = a.tag AND p.post_id = d.post_id
            )
        )
        GROUP BY a.tag
        ORDER BY a.created_at DESC
        """.trimIndent(),
        emptyArray(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ArtistSummary(
                        tag = cursor.getString(0),
                        sourcePostId = cursor.getLong(1),
                        lastSeenPostId = cursor.getLong(2),
                        syncState = runCatching { SyncState.valueOf(cursor.getString(3)) }
                            .getOrDefault(SyncState.IDLE),
                        currentPostId = if (cursor.isNull(4)) null else cursor.getLong(4),
                        lastError = if (cursor.isNull(5)) null else cursor.getString(5),
                        lastSyncAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                        createdAt = cursor.getLong(7),
                        downloadedCount = cursor.getInt(8),
                        pendingCount = cursor.getInt(9),
                        failedCount = cursor.getInt(10),
                        currentBytes = cursor.getLong(11),
                        currentTotalBytes = cursor.getLong(12),
                        poolId = if (cursor.isNull(13)) null else cursor.getLong(13),
                        displayName = if (cursor.isNull(14)) null else cursor.getString(14),
                    ),
                )
            }
        }
    }

    private fun signalChanged() {
        changes.value = changes.value + 1L
    }

    companion object {
        private const val DATABASE_NAME = "rule34_downloader.db"
        private const val DATABASE_VERSION = 5

        @Volatile
        private var instance: Rule34Database? = null

        fun getInstance(context: Context): Rule34Database = instance ?: synchronized(this) {
            instance ?: Rule34Database(context.applicationContext).also { instance = it }
        }
    }
}
