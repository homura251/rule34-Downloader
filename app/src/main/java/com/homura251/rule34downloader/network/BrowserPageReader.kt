package com.homura251.rule34downloader.network

import android.content.Context
import android.content.MutableContextWrapper
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

object BrowserPagePolicy {
    fun isDownloadable(type: String): Boolean = type.startsWith("image/") || type.startsWith("video/") ||
        type.substringBefore(';').equals("application/octet-stream", true)

    fun matchesRequest(requested: String, actual: String): Boolean {
        val expected = requested.toHttpUrlOrNull() ?: return false
        val result = actual.toHttpUrlOrNull() ?: return false
        if (expected.host.removePrefix("www.") != result.host.removePrefix("www.") ||
            expected.scheme != result.scheme || expected.port != result.port) return false
        val paginated = expected.queryParameter("page") == "pool" ||
            (expected.queryParameter("page") == "post" && expected.queryParameter("s") == "list")
        return expected.encodedPath == result.encodedPath && listOf("page", "s", "id", "tags", "pid").all { key ->
            if (key == "pid" && paginated) {
                (expected.queryParameter(key) ?: "0") == (result.queryParameter(key) ?: "0")
            } else expected.queryParameter(key)?.let { it == result.queryParameter(key) } ?: true
        }
    }

    fun isChallenge(document: Document): Boolean {
        if (document.title().startsWith("Just a moment", true) || document.title().equals("CAPTCHA", true) ||
            document.selectFirst("#challenge-form, #cf-challenge-running") != null) return true
        // Passive detections can be embedded in an otherwise readable page.
        return document.html().contains("_cf_chl_opt", true) && !hasContent(document)
    }

    fun isReadable(document: Document, contentType: String = "text/html", readyState: String = "complete"): Boolean {
        if (contentType.startsWith("image/") || contentType.startsWith("video/")) return true
        if (readyState !in listOf("interactive", "complete") || isChallenge(document)) return false
        val url = document.baseUri().toHttpUrlOrNull()
        val page = url?.queryParameter("page")
        val section = url?.queryParameter("s")
        if (page == "post" && section == "view") return Rule34MediaParser.originalUrl(document) != null
        if (page == "post" && section == "list") return document.selectFirst(".image-list") != null || isEmptyList(document)
        if (page == "pool" && section == "show") return hasPoolHeading(document)
        return hasContent(document) || document.selectFirst("#tag-sidebar, #image, #navbar, #header") != null
    }

    private fun hasContent(document: Document): Boolean = document.selectFirst(".image-list") != null ||
        Rule34MediaParser.originalUrl(document) != null || hasPoolHeading(document) || isEmptyList(document)
    private fun hasPoolHeading(document: Document): Boolean =
        document.select("h1, h2, h3, h4").any { it.text().trim().startsWith("Pool:", true) }
    private fun isEmptyList(document: Document): Boolean =
        document.body().text().let { it.contains("Nobody here but us chickens", true) || it.contains("No posts found", true) }

    fun blockNavigation(url: String, mainFrame: Boolean, allowed: (String) -> Boolean): Boolean = mainFrame && !allowed(url)
    fun snapshot(view: WebView, callback: (Result<BrowserSnapshot>) -> Unit) = captureBrowserSnapshot(view, callback)
}

/** One serialized Chromium session; explicit verification can hand its actual WebView back. */
class BrowserPageReader(
    private val context: Context,
    private val createWebView: (Context) -> WebView,
    private val allowed: (String) -> Boolean = ::isRule34UrlString,
    private val timeoutMs: Long = 30_000L,
    private val reuseSession: Boolean = false,
    private val onDiagnostic: (String) -> Unit = {},
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val sessionLock = ReentrantLock()
    private val idleToken = Any()
    private val closed = AtomicBoolean(false)
    // These references and all WebView operations belong to the main thread.
    private var idleView: WebView? = null
    private var offeredView: WebView? = null
    private var activeView: WebView? = null
    private var abortActive: (() -> Unit)? = null

    fun adoptVerifiedView(view: WebView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed.get()) { "网页会话已关闭。" }
        (view.parent as? ViewGroup)?.removeView(view)
        (view.context as? MutableContextWrapper)?.baseContext = context.applicationContext
        offeredView?.let(::destroy)
        offeredView = view
        configureIdle(view)
        scheduleIdleCleanup()
    }

    fun read(url: String, checkActive: () -> Unit = {}): Document {
        check(Looper.myLooper() != Looper.getMainLooper()) { "网页读取不能阻塞主线程。" }
        require(allowed(url)) { "不支持的网页地址。" }
        try {
            checkActive()
            check(!closed.get()) { "网页会话已关闭。" }
            while (!sessionLock.tryLock(100, TimeUnit.MILLISECONDS)) checkActive()
            try { checkActive(); return readPage(url, checkActive) } finally { sessionLock.unlock() }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("网页读取已取消").apply { initCause(e) }
        }
    }

    private fun readPage(url: String, checkActive: () -> Unit): Document {
        val done = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val result = AtomicReference<Document?>()
        val diagnostics = BrowserReadDiagnostics(url)
        val observer = BrowserDocumentObserver(diagnostics)
        val failure = AtomicReference<Exception?>()
        val pollToken = Any()
        var webView: WebView? = null
        var succeeded = false
        fun fail(error: Exception) {
            if (done.compareAndSet(false, true)) { failure.set(error); latch.countDown() }
        }
        main.post {
            if (done.get()) return@post
            if (closed.get()) { fail(BrowserReadException("网页会话已关闭。")); return@post }
            try {
                main.removeCallbacksAndMessages(idleToken)
                val view = offeredView?.also { offeredView = null; idleView?.let(::destroy); idleView = null }
                    ?: idleView?.also { idleView = null } ?: createWebView(context)
                webView = view
                activeView = view
                abortActive = { fail(BrowserReadException("网页会话已关闭。")) }
                fun inspect() {
                    if (done.get()) return
                    observer.inspect(view) captured@ { captured ->
                        if (done.get()) return@captured
                        captured.onSuccess { page ->
                            val document = page.document
                            if (allowed(document.baseUri()) && BrowserPagePolicy.matchesRequest(url, document.baseUri()) &&
                                BrowserPagePolicy.isReadable(document, page.contentType, page.readyState) && done.compareAndSet(false, true)) {
                                result.set(document); latch.countDown()
                            } else if (!BrowserPagePolicy.isChallenge(document) && !diagnostics.challengeHeader.get() && document.body().text().isNotBlank()) {
                                val status = diagnostics.httpError.get()
                                if (status == 429 || status >= 500) fail(RetryableApiException("匿名网页暂时不可用（HTTP $status），请稍后重试。"))
                                else if (status >= 400) fail(ApiException("匿名网页请求失败（HTTP $status）。"))
                            }
                        }
                    }
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, actual: String, favicon: android.graphics.Bitmap?) {
                        observer.started(actual)
                    }
                    override fun onPageCommitVisible(view: WebView, actual: String) { observer.committed(actual); inspect() }
                    override fun onPageFinished(view: WebView, actual: String) { observer.committed(actual); inspect() }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val blocked = BrowserPagePolicy.blockNavigation(request.url.toString(), request.isForMainFrame, allowed)
                        if (blocked) diagnostics.blockedNavigation.set(request.url.toString())
                        return blocked
                    }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = blockedPostMedia(request)
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && (BrowserPagePolicy.matchesRequest(url, request.url.toString()) || request.url.toString() == diagnostics.nativeUrl.get())) {
                            val message = "网页连接失败（${error.errorCode}）：${error.description}。请求：${BrowserReadDiagnostics.safeUrl(url)}"
                            diagnostics.loadError.set(message)
                            fail(BrowserReadException(message))
                        }
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel()
                        if (BrowserPagePolicy.matchesRequest(url, error.url) || error.url == diagnostics.nativeUrl.get()) {
                            val message = browserSslError(error)
                            diagnostics.loadError.set(message)
                            fail(BrowserReadException(message))
                        }
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (!request.isForMainFrame || !BrowserPagePolicy.matchesRequest(url, request.url.toString())) return
                        val status = response.statusCode
                        diagnostics.httpError.set(status)
                        val challenge = response.responseHeaders.orEmpty().any { (key, value) -> key.equals("cf-mitigated", true) && value.equals("challenge", true) }
                        diagnostics.challengeHeader.set(challenge)
                        if (status == 429 && !challenge) fail(RetryableApiException("匿名网页暂时不可用（HTTP 429），请稍后重试。"))
                        else if (status >= 500 && status != 503 && !challenge) fail(RetryableApiException("匿名网页暂时不可用（HTTP $status），请稍后重试。"))
                        else if (status !in listOf(403, 429, 503) && !challenge) fail(ApiException("匿名网页请求失败（HTTP $status）。"))
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        fail(BrowserReadException("网页渲染进程${if (detail.didCrash()) "崩溃" else "被系统回收"}，请重新同步。"))
                        return true
                    }
                }
                view.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, progress: Int) { diagnostics.progress.set(progress) }
                }
                view.setDownloadListener { actual, _, _, type, _ ->
                    if (allowed(actual) && BrowserPagePolicy.matchesRequest(url, actual) && BrowserPagePolicy.isDownloadable(type) && done.compareAndSet(false, true)) {
                        result.set(Jsoup.parse("", actual)); latch.countDown()
                    }
                }
                val poll = object : Runnable {
                    override fun run() { if (!done.get()) { inspect(); main.postDelayed(this, pollToken, 300L) } }
                }
                main.postDelayed(poll, pollToken, 300L)
                observer.started(url)
                view.onResume()
                view.loadUrl(url)
            } catch (e: Exception) { fail(BrowserReadException("无法启动网页读取：${e.message?.take(200)}", e)) }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                checkActive()
                if (System.nanoTime() >= deadline) throw diagnostics.timeout()
            }
            checkActive()
            failure.get()?.let { throw it }
            val document = result.get() ?: throw BrowserReadException("WebView 未返回请求页面。")
            succeeded = true
            onDiagnostic(diagnostics.report("页面读取成功"))
            return document
        } catch (e: Exception) {
            if (e is IOException || e is ApiException) onDiagnostic(diagnostics.report(e.message.orEmpty()))
            throw e
        } finally {
            done.set(true)
            val keep = succeeded && reuseSession && runCatching { checkActive() }.isSuccess
            main.post {
                main.removeCallbacksAndMessages(pollToken)
                val view = webView
                if (activeView === view) { activeView = null; abortActive = null }
                if (view != null) {
                    if (keep && !closed.get() && offeredView == null) {
                        idleView = view; configureIdle(view); scheduleIdleCleanup()
                    } else destroy(view)
                }
            }
        }
    }

    private fun configureIdle(view: WebView) {
        view.webChromeClient = WebChromeClient()
        view.setDownloadListener(null)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                BrowserPagePolicy.blockNavigation(request.url.toString(), request.isForMainFrame, allowed)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = blockedPostMedia(request)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (idleView === view) idleView = null
                if (offeredView === view) offeredView = null
                destroy(view); return true
            }
        }
    }
    private fun scheduleIdleCleanup() {
        main.removeCallbacksAndMessages(idleToken)
        main.postDelayed({ idleView?.let(::destroy); offeredView?.let(::destroy); idleView = null; offeredView = null }, idleToken, 30_000L)
    }
    private fun destroy(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.stopLoading(); view.destroy() }
    }
    override fun close() {
        closed.set(true)
        main.post {
            main.removeCallbacksAndMessages(idleToken)
            abortActive?.invoke()
            idleView?.let(::destroy); offeredView?.let(::destroy)
            idleView = null; offeredView = null
        }
    }
}

internal fun blockedPostMedia(request: WebResourceRequest): WebResourceResponse? {
    val url = request.url.toString()
    if (request.isForMainFrame || !isRule34UrlString(url) || request.url.path.orEmpty().contains("/cdn-cgi/")) return null
    val extension = request.url.lastPathSegment.orEmpty().substringAfterLast('.').lowercase()
    if (extension !in setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "mp4", "webm")) return null
    return WebResourceResponse("application/octet-stream", null, java.io.ByteArrayInputStream(byteArrayOf()))
}
