package com.homura251.rule34downloader.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class CloudflareInterceptorTest {
    private val server = MockWebServer()
    private val cookies = TestCookieJar()
    private var resolutions = 0

    @Before fun setUp() { server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun client(
        allowLocalServer: Boolean = true,
        resolve: (Request) -> Unit = { request ->
            resolutions++
            cookies.saveFromResponse(request.url, listOf(
                Cookie.Builder().hostOnlyDomain(request.url.host).path("/")
                    .name("cf_clearance").value("verified").build(),
            ))
        },
    ): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .cookieJar(cookies)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "test-browser").build())
        }
        .addInterceptor(CloudflareInterceptor(
            clearanceCookie = { url -> cookies.loadForRequest(url).firstOrNull { it.name == "cf_clearance" }?.value },
            resolveChallenge = resolve,
            canResolve = { url -> allowLocalServer || isRule34Url(url) },
        ))
        .build()

    private fun challenge(code: Int = 403): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("cf-mitigated", "challenge")
        .setHeader("Content-Type", "text/html")
        .setBody("<html>Challenge</html>")

    @Test fun sharesVerifiedCookieAndUserAgentWithSubsequentMediaDownload() {
        server.enqueue(challenge()) // cf-mitigated works without a Server header.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("post page"))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("original file"))
        val client = client(resolve = { request ->
            assertEquals("test-browser", request.header("User-Agent"))
            resolutions++
            cookies.saveFromResponse(request.url, listOf(
                Cookie.Builder().hostOnlyDomain(request.url.host).path("/")
                    .name("cf_clearance").value("verified").build(),
            ))
        })
        client.newCall(Request.Builder().url(server.url("/post")).build()).execute().use {
            assertEquals("post page", it.body!!.string())
        }
        client.newCall(Request.Builder().url(server.url("/images/original.png")).build()).execute().use {
            assertEquals("original file", it.body!!.string())
        }
        val requests = (1..3).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
        assertEquals(1, resolutions)
        assertTrue(requests.all { it.getHeader("User-Agent") == "test-browser" })
        assertEquals(null, requests[0].getHeader("Cookie"))
        assertEquals("cf_clearance=verified", requests[1].getHeader("Cookie"))
        assertEquals("cf_clearance=verified", requests[2].getHeader("Cookie"))
    }

    @Test fun ordinaryRateLimitsAndServerErrorsDoNotLaunchVerification() {
        val client = client()
        for (code in listOf(429, 403, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Server", "cloudflare")
                .setHeader("Content-Type", "text/html").setBody("429 Rate limiting or maintenance"))
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                assertEquals(code, it.code)
            }
        }
        assertEquals(0, resolutions)
        assertEquals(3, server.requestCount)
    }

    @Test fun recognizesLegacyChallengeEvenWithHttp200() {
        server.enqueue(MockResponse().setHeader("Server", "cloudflare-nginx")
            .setHeader("Content-Type", "text/html").setBody("<script>window._cf_chl_opt = {};</script>"))
        server.enqueue(MockResponse().setBody("verified page"))
        client().newCall(Request.Builder().url(server.url("/")).build()).execute().use {
            assertEquals("verified page", it.body!!.string())
        }
        assertEquals(1, resolutions)
        assertEquals(2, server.requestCount)
    }

    @Test fun failedVerificationDoesNotRetryTheRequest() {
        server.enqueue(challenge())
        val client = client(resolve = { throw CloudflareChallengeException() })
        val error = assertThrows(CloudflareChallengeException::class.java) {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        }
        assertTrue(error.message!!.contains("网页验证"))
        assertEquals(1, server.requestCount)
    }

    @Test fun rejectedClearanceStopsAfterOneRetry() {
        server.enqueue(challenge())
        server.enqueue(challenge(503))
        val client = client()
        assertThrows(CloudflareChallengeException::class.java) {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        }
        assertEquals(1, resolutions)
        assertEquals(2, server.requestCount)
    }

    @Test fun concurrentChallengesReuseOneClearanceRefresh() {
        val originalRequests = CountDownLatch(2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Cookie") == "cf_clearance=verified") {
                    return MockResponse().setBody("verified")
                }
                originalRequests.countDown()
                if (!originalRequests.await(3, TimeUnit.SECONDS)) {
                    return MockResponse().setResponseCode(500).setBody("concurrent requests timed out")
                }
                return challenge()
            }
        }
        val client = client()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val downloads = (1..2).map { index -> executor.submit<String> {
                client.newCall(Request.Builder().url(server.url("/post/$index")).build()).execute().use {
                    it.body!!.string()
                }
            } }
            downloads.forEach { assertEquals("verified", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, resolutions)
            assertEquals(4, server.requestCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun ordinaryHtmlResponseRemainsReadableAfterChallengeDetection() {
        val html = """
            <html><title>Rule34</title><body>original post page</body>
            <script src="/cdn-cgi/challenge-platform/h/g/scripts/jsd/example.js"></script></html>
        """.trimIndent()
        server.enqueue(MockResponse().setHeader("Server", "cloudflare")
            .setHeader("Content-Type", "text/html").setBody(html))
        client().newCall(Request.Builder().url(server.url("/")).build()).execute().use {
            assertEquals(html, it.body!!.string())
        }
        assertEquals(0, resolutions)
    }

    @Test fun doesNotResolveChallengesOnUntrustedHosts() {
        server.enqueue(challenge())
        client(allowLocalServer = false).newCall(Request.Builder().url(server.url("/")).build()).execute().use {
            assertEquals(403, it.code)
        }
        assertEquals(0, resolutions)
    }

    @Test fun onlyHttpsRule34HostsCanBeOpenedForVerification() {
        assertTrue(isRule34Url("https://rule34.xxx/".toHttpUrl()))
        assertTrue(isRule34Url("https://wimg.rule34.xxx/".toHttpUrl()))
        assertFalse(isRule34Url("http://rule34.xxx/".toHttpUrl()))
        assertFalse(isRule34Url("https://rule34.xxx.example.com/".toHttpUrl()))
        assertFalse(isRule34Url("https://example.com/".toHttpUrl()))
    }

    private class TestCookieJar : CookieJar {
        @Volatile private var cookies: List<Cookie> = emptyList()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies = this.cookies.filterNot { old -> cookies.any { it.name == old.name } } + cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }
}
