package com.homura251.rule34downloader.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

// Follows Tachiyomi's flow: close the challenge response, resolve in a WebView,
// then retry with the same User-Agent and the WebView's cookies.
class CloudflareInterceptor(
    private val clearanceCookie: (HttpUrl) -> String?,
    private val resolveChallenge: (Request) -> Unit,
    private val canResolve: (HttpUrl) -> Boolean = ::isRule34Url,
    private val resolveWithCancellation: ((Request, () -> Unit) -> Unit)? = null,
) : Interceptor {
    private val challengeLock = ReentrantLock()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val originalClearance = clearanceCookie(request.url)
        val response = chain.proceed(request)
        val challengeRequest = response.request
        if (request.method != "GET" || !canResolve(challengeRequest.url) || !isChallenge(response)) {
            return response
        }
        // Redirects may challenge a different host/path. Verify the final URL,
        // while retaining the original URL for the one bounded retry.
        val networkRequest = response.networkResponse?.request
        val previousClearance = if (networkRequest != null) {
            // Use the cookie actually sent on the final hop, not one another
            // concurrent request may have refreshed since its response arrived.
            networkRequest.header("Cookie").orEmpty().split(';').map(String::trim)
                .firstOrNull { it.substringBefore('=') == "cf_clearance" }
                ?.substringAfter('=')
        } else {
            originalClearance
        }
        response.close()

        fun checkActive() {
            if (chain.call().isCanceled()) throw InterruptedIOException("网页验证已取消")
        }
        while (!challengeLock.tryLock(100, TimeUnit.MILLISECONDS)) checkActive()
        try {
            checkActive()
            // Another request may already have refreshed the shared clearance.
            val currentClearance = clearanceCookie(challengeRequest.url)
            if (currentClearance == null || currentClearance == previousClearance) {
                try {
                    if (resolveWithCancellation != null) resolveWithCancellation.invoke(challengeRequest, ::checkActive)
                    else resolveChallenge(challengeRequest)
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw CloudflareChallengeException(cause = e)
                }
            }
        } finally { challengeLock.unlock() }

        // Retry only once, including when the server rejects the new cookie.
        checkActive()
        val retry = chain.proceed(request)
        if (isChallenge(retry)) {
            retry.close()
            throw CloudflareChallengeException()
        }
        return retry
    }

    companion object {
        internal fun isChallenge(response: Response): Boolean {
            if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) return true

            // Older challenges lack cf-mitigated. A Cloudflare Server header or
            // HTTP 429 alone is also used for ordinary errors, so inspect HTML.
            val server = response.header("Server").orEmpty()
            if (!server.equals("cloudflare", true) && !server.equals("cloudflare-nginx", true)) {
                return false
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.isNotEmpty() && !contentType.contains("html", true)) return false
            val body = response.peekBody(64L * 1024).string()
            return body.contains("_cf_chl_opt", true) ||
                body.contains("/orchestrate/chl_page/", true) ||
                body.contains("<title>Just a moment", true) ||
                body.contains("id=\"challenge-form\"", true)
        }
    }
}

internal fun isRule34Url(url: HttpUrl): Boolean =
    url.isHttps && (url.host == "rule34.xxx" || url.host.endsWith(".rule34.xxx"))

class CloudflareChallengeException(
    message: String = "Cloudflare 验证未完成。请在设置中点「网页验证」，完成验证后重新同步。",
    cause: Throwable? = null,
) : IOException(message, cause)
