package com.homura251.rule34downloader.network

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.homura251.rule34downloader.data.AppPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.util.concurrent.TimeUnit

class Rule34Network private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    // Do not impersonate Chrome: both clients use the actual WebView UA.
    val userAgent: String = WebSettings.getDefaultUserAgent(appContext)
    val cookies = WebViewCookieJar()
    var verificationUrl: String
        get() = preferences.verificationUrl?.takeIf(::isRule34UrlString) ?: VERIFICATION_URL
        private set(value) { preferences.verificationUrl = value }
    private val pages by lazy { BrowserPageReader(appContext, ::createWebView) }
    private val media by lazy { BrowserMediaReader(appContext, ::createWebView) }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .cookieJar(cookies)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .addInterceptor(CloudflareInterceptor(cookies::clearance, { resolveChallenge(it) },
            resolveWithCancellation = { request, active -> resolveChallenge(request, active) }))
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = userAgent
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.loadsImagesAutomatically = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    fun htmlClient(http: OkHttpClient = client, checkActive: () -> Unit = {}): Rule34HtmlClient =
        Rule34HtmlClient(http, checkActive) { url, active ->
            try { pages.read(url, active).also { cookies.flush() } }
            catch (e: CloudflareChallengeException) { rememberVerificationUrl(url); throw e }
        }

    fun openBrowserMedia(url: String, checkActive: () -> Unit, registerCancel: (() -> Unit) -> Closeable): MediaSource {
        try { return media.open(url, checkActive, registerCancel) }
        catch (e: CloudflareChallengeException) { rememberVerificationUrl(url); throw e }
    }

    private fun resolveChallenge(request: Request, checkActive: () -> Unit = {}) {
        rememberVerificationUrl(request.url.toString())
        pages.read(request.url.toString(), checkActive)
        cookies.flush()
    }

    fun rememberVerificationUrl(url: String) {
        if (isRule34UrlString(url)) verificationUrl = url
    }

    companion object {
        const val SITE = "https://rule34.xxx"
        const val VERIFICATION_URL = "$SITE/index.php?page=post&s=list"
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
        manager.getCookie(url.toString()).orEmpty().split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    fun clearance(url: HttpUrl): String? = loadForRequest(url).firstOrNull { it.name == "cf_clearance" }?.value
    fun flush() = manager.flush()
}

internal fun isRule34UrlString(url: String): Boolean = url.toHttpUrlOrNull()?.let(::isRule34Url) == true
