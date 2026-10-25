package com.homura251.rule34downloader

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.domain.files.FileIntegrityException
import com.homura251.rule34downloader.network.HttpMediaTransport
import com.homura251.rule34downloader.storage.*
import com.homura251.rule34downloader.work.SyncControl
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadReliabilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
    private val hash = TestApiOrigin.md5(payload)
    private val tag = "test_storage_${UUID.randomUUID()}"
    private fun record() = DownloadRecord(tag, 1, "https://wimg.rule34.xxx/$hash.png", hash, DownloadStatus.PENDING, 0, 0)
    private val source = object : MediaTransport {
        override suspend fun open(request: MediaRequest) = MediaStream(ByteArrayInputStream(payload), "image/png", payload.size.toLong()) { }
    }

    @Test fun actualMediaStorePublicationAndBasicLengthReuse() = runBlocking {
        val downloader = MediaStoreDownloader(context, source)
        val saved = downloader.download(record(), {}, { _, _ -> }, {})
        val uri = Uri.parse(saved.uri)
        try {
            assertEquals(hash, saved.verifiedMd5)
            assertEquals(hash, TestApiOrigin.md5(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }))
            assertEquals(0, pending())
            val r = record().copy(localUri = saved.uri, status = DownloadStatus.DOWNLOADED, totalBytes = payload.size.toLong(), verifiedMd5 = saved.verifiedMd5)
            assertNotNull(ExistingDownloads(context, tag, {}).lookup(r, {}).match)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(ByteArray(2)) }
            assertNull(ExistingDownloads(context, tag, {}).lookup(r, {}).match)
            assertTrue(context.contentResolver.openInputStream(uri)!!.use { it.read() } >= 0)
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun missingMd5DoesNotBlockDownloads() = runBlocking {
        for (md5 in listOf("", "invalid")) {
            var published = false
            val saved = MediaStoreDownloader(context, source).download(record().copy(md5 = md5, fileUrl = "https://wimg.rule34.xxx/original.png"), {}, { _, _ -> }, { published = true })
            try {
                assertTrue(published)
                assertNull(saved.verifiedMd5)
                assertEquals(payload.size.toLong(), saved.bytes)
                assertEquals(0, pending())
            } finally { context.contentResolver.delete(Uri.parse(saved.uri), null, null) }
        }
    }

    @Test fun knownMd5MismatchPreventsPublication() {
        var called = false
        assertThrows(FileIntegrityException::class.java) {
            runBlocking {
                MediaStoreDownloader(context, source).download(
                    record().copy(md5 = "f".repeat(32), fileUrl = "https://wimg.rule34.xxx/original.png"),
                    {}, { _, _ -> }, { called = true })
            }
        }
        assertFalse(called)
        assertEquals(0, rows())
    }

    @Test fun oldSameLengthCorruptionRejectedWhenPreviouslyVerified() = runBlocking {
        val saved = MediaStoreDownloader(context, source).download(record(), {}, { _, _ -> }, {})
        val uri = Uri.parse(saved.uri)
        try {
            val damaged = payload.copyOf()
            damaged[0] = (damaged[0].toInt() xor 1).toByte()
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(damaged) }
            val old = record().copy(localUri = saved.uri, status = DownloadStatus.PENDING,
                verifiedMd5 = hash, totalBytes = payload.size.toLong())
            assertNull(ExistingDownloads(context, tag, {}).lookup(old, {}).match)
            val fast = ExistingDownloads(context, tag, {}).lookup(old.copy(verifiedMd5 = null), {}).match
            assertEquals(payload.size.toLong(), fast?.bytes)
            assertNull(fast?.verifiedMd5)
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun historicalVerifiedChecksumCanRestoreAMissingFileWithoutNewApiMetadata() = runBlocking {
        val legacy = record().copy(fileUrl = "https://wimg.rule34.xxx/legacy/original.png", md5 = "", verifiedMd5 = hash)
        val saved = MediaStoreDownloader(context, source).download(legacy, {}, { _, _ -> }, {})
        try {
            assertEquals(hash, saved.verifiedMd5)
            assertEquals(hash, TestApiOrigin.md5(context.contentResolver.openInputStream(Uri.parse(saved.uri))!!.use { it.readBytes() }))
            assertEquals(0, pending())
        } finally { context.contentResolver.delete(Uri.parse(saved.uri), null, null) }
    }

    @Test fun cancellationAtPublicationStillFinishesPersistenceCallback() = runBlocking {
        val published = CompletableDeferred<SavedFile>()
        val finish = CompletableDeferred<Unit>()
        var persisted = false
        val job = launch(Dispatchers.IO) {
            MediaStoreDownloader(context, source).download(record(), {}, { _, _ -> }, { saved ->
                published.complete(saved); finish.await(); persisted = true
            })
        }
        val saved = withTimeout(15_000) { published.await() }
        job.cancel(); finish.complete(Unit); job.join()
        val uri = Uri.parse(saved.uri)
        try {
            assertTrue(persisted)
            assertEquals(hash, TestApiOrigin.md5(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }))
            assertEquals(0, pending())
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun actualHtmlMediaResponseCreatesNoDownloadRow() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Challenge</html>"))
            val transport = HttpMediaTransport(OkHttpClient(), SyncControl {})
            try { MediaStoreDownloader(context, transport).download(record().copy(fileUrl = server.url("/$hash.png").toString()), {}, { _, _ -> }, {}); fail("Challenge must be rejected") }
            catch (e: SourceFailure) { assertEquals(FailureKind.BLOCKED, e.kind) }
            assertEquals(0, rows())
        }
    }

    private fun query(): android.database.Cursor? = context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.IS_PENDING), Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ? AND owner_package_name = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(MediaStoreDownloader.buildRelativePath(tag), context.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }, null)
    private fun pending() = query()!!.use { c -> var n = 0; while (c.moveToNext()) if (c.getInt(1) == 1) n++; n }
    private fun rows() = query()!!.use { it.count }
}
