package com.homura251.rule34downloader.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CollectionEntity::class, PostEntity::class, MembershipEntity::class, TaskEntity::class,
    LocalFileEntity::class, CheckpointEntity::class], version = 7, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        fun open(context: Context, name: String = "rule34_downloader.db"): LibraryDatabase =
            Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, name)
                .addMigrations(*(1..5).map { LegacyMigration(it) }.toTypedArray(), SYNC_MODE_MIGRATION).build()
    }
}

private val SYNC_MODE_MIGRATION = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE collections ADD COLUMN sync_mode TEXT NOT NULL DEFAULT 'FULL'")
    }
}

/** Every supported legacy version migrates in the same transaction; URI and user intent are retained. */
class LegacyMigration(from: Int) : Migration(from, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (startVersion < 2) db.execSQL("ALTER TABLE downloads ADD COLUMN preview_url TEXT")
        if (startVersion < 3) db.execSQL("ALTER TABLE artists ADD COLUMN sync_paused INTEGER NOT NULL DEFAULT 0")
        if (startVersion < 4) {
            db.execSQL("ALTER TABLE artists ADD COLUMN pool_id INTEGER")
            db.execSQL("ALTER TABLE artists ADD COLUMN display_name TEXT")
            db.execSQL("CREATE TABLE pool_posts (pool_tag TEXT NOT NULL, post_id INTEGER NOT NULL, position INTEGER NOT NULL, preview_url TEXT, PRIMARY KEY(pool_tag, post_id))")
        }
        if (startVersion < 5) db.execSQL("ALTER TABLE downloads ADD COLUMN verified_md5 TEXT")

        db.execSQL("""CREATE TABLE collections (
            id TEXT NOT NULL PRIMARY KEY, source_post_id INTEGER NOT NULL, last_seen_post_id INTEGER NOT NULL,
            user_paused INTEGER NOT NULL, pool_id INTEGER, display_name TEXT, state TEXT NOT NULL, run_id TEXT,
            current_post_id INTEGER, last_error TEXT, last_sync_at INTEGER, next_retry_at INTEGER, created_at INTEGER NOT NULL, retry_attempts INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE posts (id INTEGER NOT NULL PRIMARY KEY, file_url TEXT NOT NULL,
            preview_url TEXT, md5 TEXT NOT NULL, width INTEGER, height INTEGER, tags TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE collection_posts (collection_id TEXT NOT NULL, post_id INTEGER NOT NULL,
            position INTEGER, preview_url TEXT, PRIMARY KEY(collection_id, post_id),
            FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE,
            FOREIGN KEY(post_id) REFERENCES posts(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_collection_posts_post_id ON collection_posts(post_id)")
        db.execSQL("CREATE INDEX index_collection_posts_collection_id_position ON collection_posts(collection_id, position)")
        db.execSQL("""CREATE TABLE download_tasks (collection_id TEXT NOT NULL, post_id INTEGER NOT NULL, status TEXT NOT NULL,
            bytes_downloaded INTEGER NOT NULL, total_bytes INTEGER NOT NULL, attempts INTEGER NOT NULL, error TEXT,
            updated_at INTEGER NOT NULL, PRIMARY KEY(collection_id, post_id),
            FOREIGN KEY(collection_id, post_id) REFERENCES collection_posts(collection_id, post_id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_download_tasks_collection_id_status ON download_tasks(collection_id, status)")
        db.execSQL("""CREATE TABLE local_files (collection_id TEXT NOT NULL, post_id INTEGER NOT NULL, uri TEXT NOT NULL,
            bytes INTEGER NOT NULL, verified_md5 TEXT, PRIMARY KEY(collection_id, post_id),
            FOREIGN KEY(collection_id, post_id) REFERENCES collection_posts(collection_id, post_id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE sync_checkpoints (collection_id TEXT NOT NULL PRIMARY KEY, lower_watermark INTEGER NOT NULL,
            before_id INTEGER, max_seen_id INTEGER NOT NULL,
            FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE)""")

        db.execSQL("""INSERT INTO collections SELECT tag, source_post_id, last_seen_post_id, sync_paused, pool_id,
            display_name, CASE WHEN pool_id IS NOT NULL THEN 'ARCHIVED' WHEN sync_paused = 1 THEN 'PAUSED'
            WHEN sync_state IN ('SYNCING', 'PAUSING') THEN 'IDLE' ELSE sync_state END,
            NULL, NULL, last_error, last_sync_at, NULL, created_at, 0 FROM artists""")
        // A post can belong to several folders. Prefer complete metadata, while preserving each file separately.
        db.execSQL("""INSERT OR IGNORE INTO posts SELECT post_id, file_url, preview_url, md5, NULL, NULL, ''
            FROM downloads ORDER BY CASE WHEN LENGTH(md5) = 32 THEN 0 ELSE 1 END, updated_at DESC""")
        db.execSQL("""INSERT INTO collection_posts SELECT d.artist_tag, d.post_id, p.position, p.preview_url
            FROM downloads d LEFT JOIN pool_posts p ON p.pool_tag = d.artist_tag AND p.post_id = d.post_id""")
        db.execSQL("""INSERT INTO download_tasks SELECT artist_tag, post_id,
            CASE WHEN status = 'DOWNLOADING' THEN 'PENDING' ELSE status END,
            CASE WHEN status = 'DOWNLOADING' THEN 0 ELSE bytes_downloaded END, total_bytes, 0, error, updated_at FROM downloads""")
        db.execSQL("""INSERT INTO local_files SELECT artist_tag, post_id, local_uri, bytes_downloaded, verified_md5
            FROM downloads WHERE local_uri IS NOT NULL""")
        db.execSQL("DROP TABLE pool_posts")
        db.execSQL("DROP TABLE downloads")
        db.execSQL("DROP TABLE artists")
    }
}
