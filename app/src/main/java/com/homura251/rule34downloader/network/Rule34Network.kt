package com.homura251.rule34downloader.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class Rule34Network private constructor(context: Context) {
    private val appContext = context.applicationContext
    val userAgent: String = WebSettings.getDefaultUserAgent(appContext)
        .replace("; wv", "")
        .replace(" Version/4.0", "")
    val cookies = WebViewCookieJar()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var verificationUrl: String = SITE
        private set

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .cookieJar(cookies)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .addInterceptor(CloudflareInterceptor(cookies::clearance, ::resolveChallenge))
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = userAgent
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = true
        // Verification does not require showing post images or video previews.
        settings.loadsImagesAutomatically = false
        CookieManager.getInstance().setAcceptCookie(true)
    }

    private fun resolveChallenge(request: Request) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw CloudflareChallengeException("网页验证不能阻塞主线程。")
        }
        verificationUrl = request.url.toString()
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val cleared = AtomicBoolean(false)
        val failure = AtomicReference<Exception?>()
        var webView: WebView? = null // Only accessed on the main thread.
        val oldClearance = cookies.clearance(request.url)
        val pollToken = Any()

        main.post {
            if (finished.get()) return@post
            try {
                cookies.clearClearance(request.url)
                val view = createWebView(appContext)
                webView = view
                fun checkClearance(): Boolean {
                    val cookie = cookies.clearance(request.url)
                    if (!cookie.isNullOrBlank() && cookie != oldClearance) {
                        cookies.flush()
                        cleared.set(true)
                        latch.countDown()
                        return true
                    }
                    return false
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        checkClearance()
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !isRule34UrlString(request.url.toString())

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            failure.set(CloudflareChallengeException())
                            latch.countDown()
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame && errorResponse.statusCode !in listOf(403, 429, 503)) {
                            failure.set(CloudflareChallengeException())
                            latch.countDown()
                        }
                    }
                }
                val poll = object : Runnable {
                    override fun run() {
                        if (!finished.get() && !checkClearance()) {
                            main.postDelayed(this, pollToken, 250L)
                        }
                    }
                }
                main.postDelayed(poll, pollToken, 250L)
                // Let WebView compute its own Cookie/Host headers. No API credentials
                // or unsafe transport headers are forwarded to the verification page.
                val headers = request.headers.toMap().filterKeys {
                    it.equals("Accept", true) || it.equals("Accept-Language", true) || it.equals("Referer", true)
                }
                view.loadUrl(request.url.toString(), headers)
            } catch (e: Exception) {
                failure.set(e)
                latch.countDown()
            }
        }

        try {
            if (!latch.await(30, TimeUnit.SECONDS) || !cleared.get()) {
                throw CloudflareChallengeException(cause = failure.get())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("网页验证已取消").apply { initCause(e) }
        } finally {
            finished.set(true)
            main.post {
                main.removeCallbacksAndMessages(pollToken)
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }
    }

    companion object {
        const val SITE = "https://rule34.xxx"
        @Volatile private var instance: Rule34Network? = null

        fun get(context: Context): Rule34Network = instance ?: synchronized(this) {
            instance ?: Rule34Network(context).also { instance = it }
        }
    }
}

class WebViewCookieJar : CookieJar {
    private val manager = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        manager.getCookie(url.toString()).orEmpty().split(';')
            .mapNotNull { Cookie.parse(url, it.trim()) }

    fun clearance(url: HttpUrl): String? =
        loadForRequest(url).firstOrNull { it.name == "cf_clearance" }?.value

    fun clearClearance(url: HttpUrl) {
        val domains = listOf(url.host, url.topPrivateDomain() ?: url.host).distinct()
        domains.forEach { domain ->
            manager.setCookie(url.toString(), "cf_clearance=; Max-Age=0; Path=/; Domain=$domain; Secure")
        }
    }

    fun flush() = manager.flush()
}

internal fun isRule34UrlString(url: String): Boolean =
    url.toHttpUrlOrNull()?.let(::isRule34Url) == true
