package com.homura251.rule34downloader.network

import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.domain.files.FileChecksum
import com.homura251.rule34downloader.domain.files.SavedFileIdentity
import com.homura251.rule34downloader.domain.files.VerifiedTransfer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit opt-in. Never embeds credentials, logs request URLs or renders downloaded media. */
class Rule34LiveApiTest {
    @Test fun realApiPaginationEmptyIncrementalArtistResolutionAndOriginalIntegrity() {
        assumeTrue("Set RULE34_LIVE_TESTS=true to contact the production API",
            System.getenv("RULE34_LIVE_TESTS") == "true")
        val credentials = ApiCredentials(
            requireNotNull(System.getenv("RULE34_USER_ID")) { "RULE34_USER_ID is required" },
            requireNotNull(System.getenv("RULE34_API_KEY")) { "RULE34_API_KEY is required" })
        val account = LiveAccount(credentials)
        val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "rule34-Downloader/live-regression").build()) }.build()
        try {
            runBlocking {
                withTimeout(240_000) {
                    val catalog = Rule34ApiClient(account, http, pageSize = 2)
                    catalog.validate(credentials)
                    val first = catalog.searchPosts("-ai_generated", 0, null)
                    assertEquals("Live search must exercise a full page", 2, first.size)
                    assertTrue(first[0].id > first[1].id)
                    assertTrue(first.all { FileChecksum.normalize(it.md5) != null })
                    val second = catalog.searchPosts("-ai_generated", 0, first.last().id)
                    assertEquals(2, second.size)
                    assertTrue(second.all { it.id < first.last().id })
                    assertTrue((first + second).map { it.id }.distinct().size == 4)

                    // A future watermark isolates the empty incremental case from concurrent uploads.
                    val fixedTag = "id:${first.first().id}"
                    assertEquals(listOf(first.first().id), catalog.searchPosts(fixedTag, 0, null).map { it.id })
                    assertTrue(catalog.searchPosts("-ai_generated", Int.MAX_VALUE.toLong(), null).isEmpty())
                    assertTrue(catalog.searchPosts("downloader_absent_${UUID.randomUUID().toString().replace("-", "")}", 0, null).isEmpty())
                    try {
                        catalog.getPost(Int.MAX_VALUE.toLong())
                        fail("Missing post must report NOT_FOUND")
                    } catch (e: SourceFailure) { assertEquals(FailureKind.NOT_FOUND, e.kind) }

                    val detailed = (first + second).map { catalog.getPost(it.id) }
                    val artistPost = detailed.firstOrNull { post -> post.tagInfo.orEmpty().any { it.type == 1 } }
                        ?: error("No artist tag was supplied by the live API for the sampled posts")
                    val artists = catalog.resolveArtistTags(artistPost)
                    assertTrue(artists.isNotEmpty())
                    assertTrue(artists.all { it.name in artistPost.tags && it.type == 1 })
                    val artistPage = catalog.searchPosts(artists.first().name, 0, null)
                    assertTrue(artistPage.isNotEmpty())
                    assertTrue(artistPage.all { artists.first().name in it.tags })

                    // Exercise the same streaming checksum and read-back verification used by the app.
                    // The media client carries no API query parameters or Authorization header.
                    val original = first.first()
                    val file = File.createTempFile("rule34-live-", ".original")
                    try {
                        val request = Request.Builder().url(original.fileUrl)
                            .header("Referer", "https://rule34.xxx/").build()
                        assertNull(request.url.queryParameter("api_key"))
                        assertNull(request.header("Authorization"))
                        val result = http.newCall(request).execute().use { response ->
                            assertTrue("Original HTTP status: ${response.code}", response.isSuccessful)
                            val body = requireNotNull(response.body)
                            assertFalse(body.contentType()?.subtype.equals("html", true))
                            file.outputStream().use { output ->
                                VerifiedTransfer.copy(body.byteStream(), output, original.md5, body.contentLength())
                            }
                        }
                        val reusedBytes = file.inputStream().use { SavedFileIdentity.verify(it, original.md5) }
                        assertEquals(result.bytes, reusedBytes)
                        file.appendBytes(byteArrayOf(0))
                        assertNull(file.inputStream().use { SavedFileIdentity.verify(it, original.md5) })
                        println("LIVE PASS: authenticated; 2 disjoint pages; empty incremental and missing post confirmed; artist resolved; original ${original.id} verified (${result.bytes} bytes); old-file read-back accepted; corruption rejected")
                    } finally { file.delete() }
                }
            }
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }
}

private class LiveAccount(private val value: ApiCredentials) : AccountRepository {
    override val state = MutableStateFlow(AccountState(AccountStatus.READY, value.userId))
    override suspend fun credentials() = value
    override suspend fun save(credentials: ApiCredentials) = error("Live test cannot change credentials")
    override suspend fun clear() = error("Live test cannot change credentials")
    override suspend fun invalidate(expected: ApiCredentials, reason: String) {
        state.value = AccountState(AccountStatus.INVALID, expected.userId, reason)
    }
}
