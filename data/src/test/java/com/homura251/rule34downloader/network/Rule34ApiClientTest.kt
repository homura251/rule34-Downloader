package com.homura251.rule34downloader.network

import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class Rule34ApiClientTest {
    private val server = MockWebServer()
    private val account = FakeAccount()
    private lateinit var client: Rule34ApiClient
    @Before fun start() {
        server.start()
        client = Rule34ApiClient(account, OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).followRedirects(false).build(), ApiRateLimiter(0), server.url("/index.php"), 2)
    }
    @After fun close() { server.shutdown() }

    @Test fun missingCredentialsNeverSendARequest() = runTest {
        account.clear()
        val failure = failure { client.searchPosts("artist", 0, null) }
        assertEquals(FailureKind.AUTH, failure.kind)
        assertEquals(0, server.requestCount)
    }
    @Test fun canonicalEmptyArrayIsAnEmptyPage() = runTest {
        server.enqueue(json("[]"))
        assertTrue(client.searchPosts("artist", 0, null).isEmpty())
        val url = server.takeRequest().requestUrl!!
        assertEquals("42", url.queryParameter("user_id"))
        assertEquals("fixture-key", url.queryParameter("api_key"))
        assertEquals("0", url.queryParameter("pid"))
        assertTrue(url.queryParameter("tags")!!.contains("sort:id:desc"))
    }
    @Test fun beforeBoundaryDoesNotSendConflictingIdMetatags() = runTest {
        server.enqueue(json("[]"))
        client.searchPosts("artist", 20, 50)
        assertEquals("artist sort:id:desc id:<50", server.takeRequest().requestUrl!!.queryParameter("tags"))
    }
    @Test fun unavailableOriginalRemainsAnExplicitPostInsteadOfBlockingItsWholePage() = runTest {
        server.enqueue(json("""[{"id":1,"file_url":"https://wimg.rule34.xxx/one.png"},{"id":2}]"""))
        val posts = client.searchPosts("artist", 0, null)
        assertEquals(listOf(1L, 2L), posts.map { it.id })
        assertNull(posts.first().availabilityError)
        assertNotNull(posts.last().availabilityError)
        assertEquals("", posts.last().fileUrl)
    }
    @Test fun aPostWithoutIdentityCannotBeSilentlyDropped() = runTest {
        server.enqueue(json("""[{"id":1,"file_url":"https://wimg.rule34.xxx/one.png"},{"file_url":"https://wimg.rule34.xxx/two.png"}]"""))
        assertEquals(FailureKind.BAD_RESPONSE, failure { client.searchPosts("artist", 0, null) }.kind)
    }
    @Test fun ignoredPostIdCannotReplaceMetadataWithADifferentOriginal() = runTest {
        server.enqueue(json("""[{"id":2,"file_url":"https://wimg.rule34.xxx/two.png"}]"""))
        assertEquals(FailureKind.BAD_RESPONSE, failure { client.getPost(1) }.kind)
    }
    @Test fun challengeAndEmptyBodyCannotBeMistakenForExhaustedSearch() = runTest {
        for (body in listOf("<html><title>Just a moment...</title></html>", "{}")) {
            server.enqueue(json(body))
            val failure = failure { client.searchPosts("artist", 0, null) }
            assertTrue(failure.kind in setOf(FailureKind.BLOCKED, FailureKind.BAD_RESPONSE))
        }
    }
    @Test fun realDapiEmptyJsonIsConfirmedThroughXmlWithTheSameSearch() = runTest {
        server.enqueue(json(""))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/xml;charset=UTF-8")
            .setBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?><posts count=\"0\" offset=\"0\"></posts>"))
        assertTrue(client.searchPosts("artist", 42, null).isEmpty())
        val first = server.takeRequest().requestUrl!!
        val second = server.takeRequest().requestUrl!!
        assertEquals("1", first.queryParameter("json"))
        assertNull(second.queryParameter("json"))
        assertEquals(first.newBuilder().removeAllQueryParameters("json").build(), second)
        assertEquals("artist sort:id:desc id:>42", second.queryParameter("tags"))
        assertEquals(AccountStatus.READY, account.state.value.status)
    }
    @Test fun emptyJsonDoesNotDropPostsReturnedByTheXmlConfirmation() = runTest {
        server.enqueue(json(""))
        server.enqueue(MockResponse().setBody("<posts count=\"1\"><post id=\"1\" file_url=\"https://wimg.rule34.xxx/one.png\" md5=\"0123456789abcdef0123456789abcdef\" tags=\"artist\"/></posts>"))
        assertEquals(listOf(1L), client.searchPosts("artist", 0, null).map { it.id })
    }
    @Test fun unconfirmedEmptyResponsesCannotAdvanceAScan() = runTest {
        for (confirmation in listOf("", "[]", "<posts/>", "<posts count=\"2\"/>", "<posts count=\"0\">", "<response count=\"0\"/>")) {
            server.enqueue(json(""))
            server.enqueue(MockResponse().setBody(confirmation))
            assertEquals(FailureKind.BAD_RESPONSE, failure { client.searchPosts("artist", 0, null) }.kind)
        }
        assertEquals(12, server.requestCount)
    }
    @Test fun xmlConfirmationKeepsAuthenticationAndRetryHandling() = runTest {
        server.enqueue(json(""))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "12"))
        val limited = failure { client.searchPosts("artist", 0, null) }
        assertEquals(FailureKind.RATE_LIMIT, limited.kind)
        assertEquals(12_000L, limited.retryAfterMillis)
        server.enqueue(json(""))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(FailureKind.AUTH, failure { client.searchPosts("artist", 0, null) }.kind)
        assertEquals(AccountStatus.INVALID, account.state.value.status)
    }
    @Test fun deletedPostUsesTheConfirmedEmptyResponseAsNotFound() = runTest {
        server.enqueue(json(""))
        server.enqueue(MockResponse().setBody("<posts count=\"0\"/>"))
        assertEquals(FailureKind.NOT_FOUND, failure { client.getPost(123) }.kind)
    }
    @Test fun realDapiHashFieldIsUsedForFileIntegrity() = runTest {
        server.enqueue(json("""[{"id":1,"file_url":"https://wimg.rule34.xxx/file.png","hash":"0123456789ABCDEF0123456789ABCDEF","tags":"artist"}]"""))
        assertEquals("0123456789abcdef0123456789abcdef", client.getPost(1).md5)
    }
    @Test fun realDapiTagInfoUsesTagNamesAndTextTypesWithoutExtraRequests() = runTest {
        server.enqueue(json("""[{"id":1,"file_url":"https://wimg.rule34.xxx/one.png","tags":"artist general character copyright meta","tag_info":[{"tag":"artist","type":"artist","count":61},{"tag":"general","type":"tag"},{"tag":"character","type":"character"},{"tag":"copyright","type":"copyright"},{"tag":"meta","type":"metadata"}]}]"""))
        val post = client.getPost(1)
        assertEquals(listOf(Rule34Tag("artist", 1, 61)), client.resolveArtistTags(post))
        assertEquals(1, server.requestCount)
    }
    @Test fun authenticationFailureInvalidatesOnlyCurrentCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(FailureKind.AUTH, failure { client.searchPosts("artist", 0, null) }.kind)
        assertEquals(AccountStatus.INVALID, account.state.value.status)
    }
    @Test fun draftValidationCannotInvalidateAnExistingAccount() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        failure { client.validate(ApiCredentials("100", "wrong-key")) }
        assertEquals(AccountStatus.READY, account.state.value.status)
    }
    @Test fun htmlForbiddenDoesNotInvalidateCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/html").setBody("<html>Challenge</html>"))
        assertEquals(FailureKind.BLOCKED, failure { client.searchPosts("artist", 0, null) }.kind)
        assertEquals(AccountStatus.READY, account.state.value.status)
    }
    @Test fun apiRedirectCannotLeakCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other").toString()))
        assertEquals(FailureKind.BAD_RESPONSE, failure { client.searchPosts("artist", 0, null) }.kind)
        assertEquals(1, server.requestCount)
    }
    @Test fun rateLimitCarriesServerRetryDelay() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val error = failure { client.searchPosts("artist", 0, null) }
        assertEquals(FailureKind.RATE_LIMIT, error.kind)
        assertEquals(120_000L, error.retryAfterMillis)
        assertEquals(AccountStatus.READY, account.state.value.status)
    }
    @Test fun completeTagInfoAvoidsExtraTagRequests() = runTest {
        server.enqueue(json("""[{"id":1,"file_url":"https://wimg.rule34.xxx/one.png","tags":"artist general","tag_info":{"artist":1,"general":0}}]"""))
        val post = client.getPost(1)
        assertEquals(listOf("artist"), client.resolveArtistTags(post).map { it.name })
        assertEquals(1, server.requestCount)
        assertEquals("tag_info", server.takeRequest().requestUrl!!.queryParameter("fields"))
    }
    @Test fun objectTagInfoAlsoAcceptsTextTypes() = runTest {
        server.enqueue(json("""[{"id":1,"file_url":"https://wimg.rule34.xxx/one.png","tags":"artist general","tag_info":{"artist":{"type":"artist"},"general":"tag"}}]"""))
        assertEquals(listOf("artist"), client.resolveArtistTags(client.getPost(1)).map { it.name })
        assertEquals(1, server.requestCount)
    }
    @Test fun xmlEmptySearchRequiresCorrectRoot() = runTest {
        assertTrue(client.parseObjects("<posts count=\"0\"/>", "post").isEmpty())
        assertEquals(FailureKind.BAD_RESPONSE, failure { client.parseObjects("<response/>", "post") }.kind)
    }
    @Test fun failedSearchEnvelopeCannotCommitAnEmptyPage() = runTest {
        server.enqueue(json("""{"success":false,"message":"search down"}"""))
        assertEquals(FailureKind.SERVER, failure { client.searchPosts("artist", 0, null) }.kind)
    }
    @Test fun cancellationStopsAResponseBodyRead() = runTest {
        server.enqueue(json("[]").setBodyDelay(2, TimeUnit.SECONDS))
        val job = launch(Dispatchers.IO) { client.searchPosts("artist", 0, null) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        withContext(Dispatchers.IO) { withTimeout(5_000) { job.cancelAndJoin() } }
        assertEquals(AccountStatus.READY, account.state.value.status)
    }
    private suspend fun failure(block: suspend () -> Any?): SourceFailure {
        try { block(); fail("Expected a typed source failure") } catch (e: SourceFailure) { return e }
        error("unreachable")
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}

private class FakeAccount : AccountRepository {
    private var saved: ApiCredentials? = ApiCredentials("42", "fixture-key")
    override val state = MutableStateFlow(AccountState(AccountStatus.READY, "42"))
    override suspend fun credentials() = saved
    override suspend fun save(credentials: ApiCredentials) { saved = credentials; state.value = AccountState(AccountStatus.READY, credentials.userId) }
    override suspend fun clear() { saved = null; state.value = AccountState() }
    override suspend fun invalidate(expected: ApiCredentials, reason: String) { if (saved == expected) state.value = AccountState(AccountStatus.INVALID, expected.userId, reason) }
}
