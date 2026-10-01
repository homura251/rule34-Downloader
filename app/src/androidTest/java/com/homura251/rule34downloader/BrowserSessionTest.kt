package com.homura251.rule34downloader

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.homura251.rule34downloader.network.BrowserPageReader
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.work.SyncControl
import com.homura251.rule34downloader.work.SyncPausedException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BrowserSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var network: Rule34Network
    private val local: (String) -> Boolean = { it.toHttpUrlOrNull()?.host == "localhost" }

    @Before fun prepare() {
        val cleared = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            network = Rule34Network.get(context)
            CookieManager.getInstance().removeAllCookies { cleared.countDown() }
        }
        assertTrue(cleared.await(10, TimeUnit.SECONDS))
    }

    @Test fun detachedBrowserHasViewportAndConsistentIdentityEvenIfSiteOverridesJson() {
        MockWebServer().use { server ->
            server.enqueue(html("""
                <div class='image-list' id='payload'></div><script>
                  JSON.stringify = function() { return null; };
                  const p = document.getElementById('payload');
                  p.setAttribute('data-width', String(innerWidth));
                  p.setAttribute('data-height', String(innerHeight));
                  p.setAttribute('data-ua', navigator.userAgent);
                  if (navigator.userAgentData) {
                    p.setAttribute('data-brands', navigator.userAgentData.brands.map(x => x.brand).join(','));
                    p.setAttribute('data-versions', navigator.userAgentData.brands.filter(x => x.brand==='Google Chrome').map(x => x.version).join(','));
                  }
                </script>
            """.trimIndent()))
            val reader = BrowserPageReader(context, network::createWebView, local, 10_000)
            try {
                val payload = reader.read(server.url("/profile").toString()).getElementById("payload")!!
                assertTrue(payload.attr("data-width").toInt() > 0)
                assertTrue(payload.attr("data-height").toInt() > 0)
                assertEquals(network.userAgent, payload.attr("data-ua"))
                assertFalse(network.userAgent.contains("; wv"))
                assertFalse(network.userAgent.contains("Version/4.0"))
                val browserRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals(network.userAgent, browserRequest.getHeader("User-Agent"))
                if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                    assertFalse(payload.attr("data-brands").contains("Android WebView"))
                    assertTrue(payload.attr("data-brands").contains("Google Chrome"))
                    val version = Regex("Chrome/(\\d+)").find(network.userAgent)!!.groupValues[1]
                    assertEquals(version, payload.attr("data-versions"))
                }
                server.enqueue(html("<div class='image-list'></div>"))
                network.client.newCall(Request.Builder().url(server.url("/native")).build()).execute().close()
                assertEquals(network.userAgent, server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("User-Agent"))
            } finally { reader.close() }
        }
    }

    @Test fun successfulReadKeepsDeferredClearanceScriptsAndReusesTheirBrowserSession() {
        val lateRequest = CountDownLatch(1)
        val created = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/first" -> html("<div class='image-list'></div><script>setTimeout(() => fetch('/late-clearance'), 900);</script>")
                    "/late-clearance" -> { lateRequest.countDown(); MockResponse().setHeader("Set-Cookie", "cf_clearance=deferred; Path=/").setBody("ok") }
                    "/second" -> if (request.getHeader("Cookie").orEmpty().contains("cf_clearance=deferred")) html("<div class='image-list'></div>") else MockResponse().setResponseCode(403)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val reader = BrowserPageReader(context, { created.incrementAndGet(); network.createWebView(it) }, local, 10_000, reuseSession = true)
            try {
                reader.read(server.url("/first").toString())
                assertTrue("Deferred JS was canceled after accepting the document", lateRequest.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!CookieManager.getInstance().getCookie(server.url("/").toString()).orEmpty().contains("cf_clearance=deferred") && System.nanoTime() < deadline) {
                    Thread.sleep(20)
                }
                assertNotNull(reader.read(server.url("/second").toString()).selectFirst(".image-list"))
                assertEquals(1, created.get())
            } finally { reader.close() }
        }
    }

    @Test fun manuallyVerifiedViewIsAdoptedWithoutCreatingAnotherBrowser() {
        val created = AtomicInteger()
        MockWebServer().use { server ->
            server.enqueue(html("<div class='image-list'></div>"))
            val reader = BrowserPageReader(context, { created.incrementAndGet(); network.createWebView(it) }, local, 10_000, reuseSession = true)
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val view = network.createWebView(context)
                    val parent = android.widget.FrameLayout(context)
                    parent.addView(view)
                    reader.adoptVerifiedView(view)
                    assertNull(view.parent)
                }
                assertNotNull(reader.read(server.url("/list").toString()).selectFirst(".image-list"))
                assertEquals(0, created.get())
            } finally { reader.close() }
        }
    }

    @Test fun pauseWhileWaitingForSharedBrowserDoesNotInterruptItsActiveReader() {
        val requested = CountDownLatch(1)
        val firstControl = SyncControl()
        val waitingControl = SyncControl()
        val executor = Executors.newFixedThreadPool(2)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.requestUrl?.encodedPath == "/hold") {
                    requested.countDown(); html("<title>Just a moment...</title><form id='challenge-form'></form>")
                } else html("<div class='image-list'></div>")
            }
            val reader = BrowserPageReader(context, network::createWebView, local, 10_000, reuseSession = true)
            try {
                val first = executor.submit<Throwable?> {
                    try { reader.read(server.url("/hold").toString(), firstControl::checkActive); null } catch (e: Exception) { e }
                }
                assertTrue(requested.await(5, TimeUnit.SECONDS))
                val waiting = CountDownLatch(1)
                val second = executor.submit<Throwable?> {
                    try { waiting.countDown(); reader.read(server.url("/list").toString(), waitingControl::checkActive); null } catch (e: Exception) { e }
                }
                assertTrue(waiting.await(5, TimeUnit.SECONDS))
                waitingControl.pause()
                assertTrue(second.get(5, TimeUnit.SECONDS) is SyncPausedException)
                assertFalse(first.isDone)
                firstControl.pause()
                assertTrue(first.get(5, TimeUnit.SECONDS) is SyncPausedException)
                assertNotNull(reader.read(server.url("/list").toString()).selectFirst(".image-list"))
            } finally { firstControl.pause(); waitingControl.pause(); reader.close(); executor.shutdownNow() }
        }
    }

    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html").setBody(body)
}
