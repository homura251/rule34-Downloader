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
                error TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (artist_tag, post_id),
                FOREIGN KEY (artist_tag) REFERENCES artists(tag) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_downloads_artist_status ON downloads(artist_tag, status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE downloads ADD COLUMN preview_url TEXT")
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
        val count = db.rawQuery("SELECT COUNT(*) FROM downloads WHERE artist_tag = ?", arrayOf(tag))
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        val posts = db.rawQuery(
            """
            SELECT artist_tag, post_id, file_url, preview_url, local_uri, status
            FROM downloads WHERE artist_tag = ? ORDER BY post_id DESC LIMIT ?
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
                ))
            }
        }
        return GalleryPage(posts, count)
    }

    @Synchronized
    fun addArtist(tag: String, sourcePostId: Long): Boolean {
        val values = ContentValues().apply {
            put("tag", tag)
            put("source_post_id", sourcePostId)
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
        "SELECT tag, source_post_id, last_seen_post_id FROM artists WHERE tag = ? LIMIT 1",
        arrayOf(tag),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        ArtistRecord(
            tag = cursor.getString(0),
            sourcePostId = cursor.getLong(1),
            lastSeenPostId = cursor.getLong(2),
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

    @Synchronized
    fun setSyncState(
        tag: String,
        state: SyncState,
        currentPostId: Long? = null,
        error: String? = null,
        markSyncTime: Boolean = false,
    ) {
        val values = ContentValues().apply {
            put("sync_state", state.name)
            if (currentPostId == null) putNull("current_post_id") else put("current_post_id", currentPostId)
            if (error == null) putNull("last_error") else put("last_error", error.take(500))
            if (markSyncTime) put("last_sync_at", System.currentTimeMillis())
        }
        writableDatabase.update("artists", values, "tag = ?", arrayOf(tag))
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
    fun insertDiscoveredPosts(tag: String, posts: List<Rule34Post>): Int {
        if (posts.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            val statement = db.compileStatement(
                """
                INSERT OR IGNORE INTO downloads(
                    artist_tag, post_id, file_url, md5, preview_url, status,
                    bytes_downloaded, total_bytes, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?)
                """.trimIndent(),
            )
            val now = System.currentTimeMillis()
            posts.forEach { post ->
                statement.clearBindings()
                statement.bindString(1, tag)
                statement.bindLong(2, post.id)
                statement.bindString(3, post.fileUrl)
                statement.bindString(4, post.md5)
                if (post.previewUrl == null) statement.bindNull(5) else statement.bindString(5, post.previewUrl)
                statement.bindString(6, DownloadStatus.PENDING.name)
                statement.bindLong(7, now)
                if (statement.executeInsert() != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        signalChanged()
        return inserted
    }

    fun getDownloadQueue(tag: String): List<DownloadRecord> = readableDatabase.rawQuery(
        """
        SELECT artist_tag, post_id, file_url, md5, status, bytes_downloaded, total_bytes
        FROM downloads
        WHERE artist_tag = ? AND status IN ('PENDING', 'FAILED', 'DOWNLOADING')
        ORDER BY post_id ASC
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
                    ),
                )
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
    fun markDownloaded(tag: String, postId: Long, localUri: String) {
        val values = ContentValues().apply {
            put("status", DownloadStatus.DOWNLOADED.name)
            put("local_uri", localUri)
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
            COALESCE(MAX(CASE WHEN d.status = 'DOWNLOADING' THEN d.total_bytes ELSE 0 END), 0) AS current_total
        FROM artists a
        LEFT JOIN downloads d ON a.tag = d.artist_tag
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
        private const val DATABASE_VERSION = 2

        @Volatile
        private var instance: Rule34Database? = null

        fun getInstance(context: Context): Rule34Database = instance ?: synchronized(this) {
            instance ?: Rule34Database(context.applicationContext).also { instance = it }
        }
    }
}
