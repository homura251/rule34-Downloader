package com.homura251.rule34downloader.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Success means the requested document is readable, not that a cookie has changed. */
object BrowserPagePolicy {
    fun matchesRequest(requested: String, actual: String): Boolean {
        val expected = requested.toHttpUrlOrNull() ?: return false
        val result = actual.toHttpUrlOrNull() ?: return false
        return expected.encodedPath == result.encodedPath &&
            listOf("page", "s", "id", "tags", "pid").all { key ->
                expected.queryParameter(key)?.let { it == result.queryParameter(key) } ?: true
            }
    }
    fun isChallenge(document: Document): Boolean =
        document.title().startsWith("Just a moment", true) ||
            document.title().equals("CAPTCHA", true) ||
            document.selectFirst("#challenge-form, #cf-challenge-running") != null ||
            document.html().contains("_cf_chl_opt", true)

    fun isReadable(document: Document, contentType: String = "text/html"): Boolean {
        if (contentType.startsWith("image/") || contentType.startsWith("video/")) return true
        if (isChallenge(document)) return false
        return document.selectFirst(".image-list, #tag-sidebar, #image, #navbar, #header") != null ||
            document.select("h1, h2, h3, h4").any { it.text().trim().startsWith("Pool:", true) } ||
            document.body().text().contains("Nobody here but us chickens", true)
    }

    // Challenge subframes must be allowed. Only top-level navigations are restricted.
    fun blockNavigation(url: String, mainFrame: Boolean, allowed: (String) -> Boolean): Boolean =
        mainFrame && !allowed(url)

    fun snapshot(view: WebView, callback: (Document, String) -> Unit) {
        view.evaluateJavascript(
            "JSON.stringify({url:location.href,html:document.documentElement.outerHTML,type:document.contentType})",
        ) { value ->
            runCatching {
                val json = JSONObject(JSONTokener(value).nextValue() as String)
                callback(Jsoup.parse(json.getString("html"), json.getString("url")), json.optString("type", "text/html"))
            }
        }
    }
}

/** Uses the same Chromium transport, storage and UA as the interactive verification page. */
class BrowserPageReader(
    private val context: Context,
    private val createWebView: (Context) -> WebView,
    private val allowed: (String) -> Boolean = ::isRule34UrlString,
    private val timeoutMs: Long = 30_000L,
) {
    private val main = Handler(Looper.getMainLooper())

    fun read(url: String, checkActive: () -> Unit = {}): Document {
        check(Looper.myLooper() != Looper.getMainLooper()) { "网页读取不能阻塞主线程。" }
        require(allowed(url)) { "不支持的网页地址。" }
        val done = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val result = AtomicReference<Document?>()
        val failure = AtomicReference<Exception?>()
        val pollToken = Any()
        var webView: WebView? = null // Main thread only.
        fun fail(error: Exception) {
            if (done.compareAndSet(false, true)) { failure.set(error); latch.countDown() }
        }
        main.post {
            if (done.get()) return@post
            try {
                val view = createWebView(context)
                webView = view
                fun inspect() {
                    if (done.get()) return
                    BrowserPagePolicy.snapshot(view) { document, type ->
                        if (allowed(document.baseUri()) && BrowserPagePolicy.matchesRequest(url, document.baseUri()) &&
                            BrowserPagePolicy.isReadable(document, type) && done.compareAndSet(false, true)) {
                            result.set(document)
                            latch.countDown()
                        }
                    }
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) = inspect()
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        BrowserPagePolicy.blockNavigation(request.url.toString(), request.isForMainFrame, allowed)
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        blockedPostMedia(request)
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) fail(IOException("网页连接失败（${error.errorCode}）：${error.description}"))
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame && errorResponse.statusCode !in listOf(403, 429, 503)) {
                            fail(ApiException("匿名网页请求失败（HTTP ${errorResponse.statusCode}）。"))
                        }
                    }
                }
                val poll = object : Runnable {
                    override fun run() {
                        if (!done.get()) { inspect(); main.postDelayed(this, pollToken, 300L) }
                    }
                }
                main.postDelayed(poll, pollToken, 300L)
                view.loadUrl(url)
            } catch (e: Exception) {
                fail(IOException("无法启动网页读取，请更新或启用 Android System WebView。", e))
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                checkActive()
                if (System.nanoTime() >= deadline) throw CloudflareChallengeException(
                    "WebView 未能读取请求页面。请在设置中完成「网页验证」后重试；若仍停在验证页，请更新 Android System WebView。",
                )
            }
            checkActive()
            failure.get()?.let { throw it }
            return result.get() ?: throw CloudflareChallengeException()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("网页读取已取消").apply { initCause(e) }
        } finally {
            done.set(true)
            main.post {
                main.removeCallbacksAndMessages(pollToken)
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }
    }
}

/** Avoid fetching post thumbnails/originals while reading HTML; retain challenge resources. */
internal fun blockedPostMedia(request: WebResourceRequest): WebResourceResponse? {
    val url = request.url.toString()
    if (request.isForMainFrame || !isRule34UrlString(url) || request.url.path.orEmpty().contains("/cdn-cgi/")) return null
    val extension = request.url.lastPathSegment.orEmpty().substringAfterLast('.').lowercase()
    if (extension !in setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "mp4", "webm")) return null
    return WebResourceResponse("application/octet-stream", null, java.io.ByteArrayInputStream(byteArrayOf()))
}
