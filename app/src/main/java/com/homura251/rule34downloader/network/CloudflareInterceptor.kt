package com.homura251.rule34downloader.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

// Follows Tachiyomi's flow: close the challenge response, resolve in a WebView,
// then retry with the same User-Agent and the WebView's cookies.
class CloudflareInterceptor(
    private val clearanceCookie: (HttpUrl) -> String?,
    private val resolveChallenge: (Request) -> Unit,
    private val canResolve: (HttpUrl) -> Boolean = ::isRule34Url,
) : Interceptor {
    private val challengeLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val previousClearance = clearanceCookie(request.url)
        val response = chain.proceed(request)
        if (request.method != "GET" || !canResolve(request.url) || !isChallenge(response)) {
            return response
        }
        response.close()

        synchronized(challengeLock) {
            // Another request may already have refreshed the shared clearance.
            val currentClearance = clearanceCookie(request.url)
            if (currentClearance == null || currentClearance == previousClearance) {
                try {
                    resolveChallenge(request)
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw CloudflareChallengeException(cause = e)
                }
            }
        }

        // Retry only once, including when the server rejects the new cookie.
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
