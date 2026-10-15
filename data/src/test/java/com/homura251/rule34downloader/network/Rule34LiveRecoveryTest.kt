package com.homura251.rule34downloader.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.homura251.rule34downloader.data.RoomLibraryRepository
import com.homura251.rule34downloader.data.room.LibraryDatabase
import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.domain.files.SavedFileIdentity
import com.homura251.rule34downloader.domain.files.VerifiedTransfer
import com.homura251.rule34downloader.download.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real API/CDN and Room, with a bounded metadata snapshot and a deliberate scan failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Rule34LiveRecoveryTest {
    @Test fun realOriginalSurvivesScanFailureKnownFileRecoveryAndFullRescan() {
        assumeTrue(System.getenv("RULE34_LIVE_TESTS") == "true")
        val credentials = ApiCredentials(requireNotNull(System.getenv("RULE34_USER_ID")),
            requireNotNull(System.getenv("RULE34_API_KEY")))
        val account = object : AccountRepository {
            override val state = MutableStateFlow(AccountState(AccountStatus.READY, credentials.userId))
            override suspend fun credentials() = credentials
            override suspend fun save(credentials: ApiCredentials) = error("Read-only test account")
            override suspend fun clear() = error("Read-only test account")
            override suspend fun invalidate(expected: ApiCredentials, reason: String) {
                state.value = AccountState(AccountStatus.INVALID, expected.userId, reason)
            }
        }
        val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "live-recovery-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, "live-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val database = LibraryDatabase.open(context, name)
        try {
            runBlocking {
                withTimeout(300_000) {
                    val real = Rule34ApiClient(account, http, pageSize = 8)
                    real.validate(credentials)
                    val post = real.searchPosts("-ai_generated", 0, null).firstOrNull {
                        SavedFileIdentity.extension(it.fileUrl) in setOf("jpg", "jpeg", "png", "webp")
                    } ?: error("Live API did not supply an image original")
                    val repo = RoomLibraryRepository(database)
                    val id = "live_recovery"
                    repo.addCollection(id, post.id)
                    assertTrue(repo.beginRun(id, "seed"))
                    val checkpoint = ScanCheckpoint(0, post.id, post.id)
                    repo.savePage(id, "seed", listOf(post), checkpoint)
                    repo.endRun(id, "seed")
                    var transfers = 0
                    val files = object : MediaDownloader {
                        override suspend fun cleanInterruptedFiles(collectionId: String) = Unit
                        override suspend fun download(record: DownloadRecord, onHeaders: (Long) -> Unit,
                            onProgress: (Long, Long) -> Unit, onPublished: suspend (SavedFile) -> Unit): SavedFile = withContext(Dispatchers.IO) {
                            val target = File(directory, "${record.postId}-${UUID.randomUUID()}.original")
                            val request = Request.Builder().url(record.fileUrl)
                                .header("Referer", "https://rule34.xxx/index.php?page=post&s=view&id=${record.postId}").build()
                            assertTrue("Media requests must omit API credentials", request.url.queryParameter("api_key") == null)
                            val result = http.newCall(request).execute().use { response ->
                                assertTrue("Original HTTP status: ${response.code}", response.isSuccessful)
                                val body = requireNotNull(response.body)
                                onHeaders(body.contentLength())
                                target.outputStream().use { output ->
                                    VerifiedTransfer.copy(body.byteStream(), output, record.md5, body.contentLength(),
                                        onProgress = { onProgress(it, body.contentLength()) })
                                }
                            }
                            transfers++
                            val saved = SavedFile(target.toURI().toString(), result.bytes, result.md5)
                            onPublished(saved)
                            saved
                        }
                    }
                    val reuse = object : FileReuse {
                        override suspend fun lookup(record: DownloadRecord): ReuseResult {
                            val file = record.localUri?.let { File(java.net.URI(it)) } ?: return ReuseResult()
                            if (!file.isFile) return ReuseResult()
                            val bytes = file.inputStream().use { SavedFileIdentity.verify(it, record.md5) }
                            return ReuseResult(bytes?.let { SavedFile(file.toURI().toString(), it, record.md5) })
                        }
                    }
                    val brokenScan = object : CatalogGateway by real {
                        override suspend fun searchPosts(tag: String, afterPostId: Long, beforePostId: Long?): List<Rule34Post> =
                            throw SourceFailure(FailureKind.BAD_RESPONSE, "Injected incomplete response after real metadata was persisted")
                    }
                    assertTrue(SyncEngine(repo, brokenScan, files, reuse).run(id) is SyncOutcome.Retry)
                    val first = repo.records(id).single()
                    assertEquals(DownloadStatus.DOWNLOADED, first.status)
                    assertEquals(1, transfers)
                    assertEquals(0L, repo.getCollection(id)!!.lastSeenPostId)
                    assertEquals(checkpoint, repo.checkpoint(id))

                    val noApi = object : CatalogGateway {
                        override val pageSize = 2
                        override suspend fun validate(credentials: ApiCredentials) = error("Known-file recovery used the API")
                        override suspend fun getPost(postId: Long): Rule34Post = error("Known-file recovery used the API")
                        override suspend fun resolveArtistTags(post: Rule34Post): List<Rule34Tag> = error("Known-file recovery used the API")
                        override suspend fun searchPosts(tag: String, afterPostId: Long, beforePostId: Long?): List<Rule34Post> = error("Known-file recovery used the API")
                    }
                    assertEquals(SyncOutcome.Complete(0, 0), SyncEngine(repo, noApi, files, reuse).run(id, mode = SyncMode.KNOWN_FILES))
                    assertEquals(SyncState.QUEUE_COMPLETE, repo.getCollection(id)!!.syncState)
                    assertEquals(first.localUri, repo.records(id).single().localUri)
                    assertEquals(1, transfers)
                    File(java.net.URI(first.localUri!!)).appendBytes(byteArrayOf(0))
                    assertEquals(SyncOutcome.Complete(1, 0), SyncEngine(repo, noApi, files, reuse).run(id, mode = SyncMode.KNOWN_FILES))
                    val repaired = repo.records(id).single()
                    assertEquals(2, transfers)
                    assertNotEquals(first.localUri, repaired.localUri)
                    assertNotNull(reuse.lookup(repaired).file)

                    assertTrue(repo.restartScan(id))
                    assertNull(repo.checkpoint(id))
                    val snapshot = object : CatalogGateway by noApi {
                        override suspend fun searchPosts(tag: String, afterPostId: Long, beforePostId: Long?) = listOf(post)
                    }
                    assertEquals(SyncOutcome.Complete(0, 0), SyncEngine(repo, snapshot, files, reuse).run(id))
                    assertEquals(SyncState.COMPLETE, repo.getCollection(id)!!.syncState)
                    assertEquals(post.id, repo.getCollection(id)!!.lastSeenPostId)
                    assertEquals(repaired.localUri, repo.records(id).single().localUri)
                    assertEquals(2, transfers)
                    println("LIVE RECOVERY PASS: real metadata ${post.id}; real original verified; scan failure drained saved queue; manual recovery made no API calls; old URI reused; corruption repaired; rescan preserved URI and avoided duplicate transfers")
                }
            }
        } finally {
            database.close(); context.deleteDatabase(name); directory.deleteRecursively()
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow()
        }
    }
}
