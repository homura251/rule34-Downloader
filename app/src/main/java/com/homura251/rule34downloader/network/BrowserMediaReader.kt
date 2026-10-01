package com.homura251.rule34downloader.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MediaSource(
    val input: InputStream,
    val contentType: String?,
    val contentLength: Long,
    private val cleanup: () -> Unit,
) : Closeable {
    override fun close() { try { input.close() } finally { cleanup() } }
}

/** Streaming fallback for clearance bound to Chromium's TLS/client fingerprint. */
class BrowserMediaReader(
    private val context: Context,
    private val createWebView: (Context) -> WebView,
    private val allowed: (String) -> Boolean = ::isRule34UrlString,
) {
    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun open(
        url: String,
        checkActive: () -> Unit = {},
        registerCancel: ((() -> Unit) -> Closeable) = { Closeable {} },
    ): MediaSource {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(allowed(url)) { "不支持的原文件地址。" }
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val ended = AtomicBoolean(false)
        val error = AtomicReference<IOException?>()
        val headersReady = CountDownLatch(1)
        val chunks = ArrayBlockingQueue<ByteArray>(4)
        var mime: String? = null
        var length = -1L
        var view: WebView? = null // Main thread only.
        fun closeSession() {
            if (!closed.compareAndSet(false, true)) return
            headersReady.countDown()
            chunks.clear()
            main.post {
                view?.evaluateJavascript("window.transferAbort && window.transferAbort.abort()", null)
                view?.removeJavascriptInterface("Transfer")
                view?.stopLoading()
                view?.destroy()
                view = null
            }
        }
        val cancellation = registerCancel(::closeSession)
        val bridge = object {
            @JavascriptInterface fun headers(status: Int, type: String, size: String) {
                if (closed.get()) return
                if (status !in 200..299 || type.substringBefore(';').lowercase() in setOf("text/html", "application/xhtml+xml")) {
                    error.set(CloudflareChallengeException("原文件仍被网页验证拦截（HTTP $status）。请在设置中完成对应页面的「网页验证」。"))
                    ended.set(true)
                } else {
                    mime = type.takeIf(String::isNotBlank)
                    length = size.toLongOrNull()?.takeIf { it >= 0 } ?: -1
                }
                headersReady.countDown()
            }
            @JavascriptInterface fun chunk(data: String) {
                if (closed.get() || ended.get()) return
                try {
                    if (data.length > 90_000) throw IOException("网页传输块过大。")
                    val bytes = Base64.decode(data, Base64.NO_WRAP)
                    if (bytes.size > 64 * 1024) throw IOException("网页传输块过大。")
                    while (!closed.get() && !chunks.offer(bytes, 100, TimeUnit.MILLISECONDS)) { /* bounded backpressure */ }
                } catch (e: Exception) {
                    error.set(IOException("网页原文件传输失败。", e)); ended.set(true)
                }
            }
            @JavascriptInterface fun complete() { ended.set(true) }
            @JavascriptInterface fun failed(message: String) {
                error.set(IOException("网页原文件传输失败：${message.take(200)}"))
                ended.set(true)
                headersReady.countDown()
            }
        }
        main.post {
            if (closed.get()) return@post
            try {
                val web = createWebView(context)
                view = web
                // The bridge exists only on our constructed page. Remote documents
                // and subframes can never navigate into this WebView.
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun onRenderProcessGone(web: WebView, detail: RenderProcessGoneDetail): Boolean {
                        error.set(IOException("原文件传输的网页渲染进程已退出，请重新同步。"))
                        ended.set(true); headersReady.countDown()
                        web.removeJavascriptInterface("Transfer")
                        web.destroy()
                        view = null
                        return true
                    }
                }
                web.addJavascriptInterface(bridge, "Transfer")
                val origin = URI(url).let { "${it.scheme}://${it.rawAuthority}/" }
                val target = JSONObject.quote(url).replace("<", "\\u003c")
                web.loadDataWithBaseURL(origin, """
                    <!doctype html><meta charset="utf-8"><script>
                    window.transferAbort = new AbortController();
                    (async () => {
                      try {
                        const response = await fetch($target, {credentials:'include', signal:transferAbort.signal});
                        const type = response.headers.get('content-type') || '';
                        const encoding = response.headers.get('content-encoding');
                        Transfer.headers(response.status, type, (!encoding || encoding === 'identity') ? (response.headers.get('content-length') || '-1') : '-1');
                        if (!response.ok || /^(text\/html|application\/xhtml\+xml)/i.test(type)) return;
                        if (!response.body) throw new Error('Empty response body');
                        const reader = response.body.getReader();
                        while (true) {
                          const part = await reader.read();
                          if (part.done) break;
                          for (let offset=0; offset<part.value.length; offset+=65536) {
                            const bytes = part.value.subarray(offset, offset+65536);
                            let binary='';
                            for (let index=0; index<bytes.length; index++) binary += String.fromCharCode(bytes[index]);
                            Transfer.chunk(btoa(binary));
                          }
                        }
                        Transfer.complete();
                      } catch (failure) { Transfer.failed(String(failure)); }
                    })();
                    </script>
                """.trimIndent(), "text/html", "UTF-8", null)
            } catch (e: Exception) {
                error.set(IOException("无法启动 WebView 原文件传输。", e))
                ended.set(true); headersReady.countDown()
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (!headersReady.await(100, TimeUnit.MILLISECONDS)) {
                checkActive()
                if (System.nanoTime() >= deadline) throw IOException("网页原文件响应超时。")
            }
            checkActive()
            if (closed.get()) throw InterruptedIOException("原文件传输已取消")
            error.get()?.let { throw it }
            val stream = object : InputStream() {
                private var current = byteArrayOf()
                private var offset = 0
                override fun read(): Int {
                    val byte = ByteArray(1)
                    return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
                }
                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                    while (offset >= current.size) {
                        checkActive()
                        if (closed.get()) throw InterruptedIOException("原文件传输已取消")
                        error.get()?.let { throw it }
                        val next = chunks.poll(100, TimeUnit.MILLISECONDS)
                        if (next != null) { current = next; offset = 0; continue }
                        if (ended.get()) return -1
                        if (System.nanoTime() >= deadline) throw IOException("网页原文件读取超时。")
                    }
                    val count = minOf(len, current.size - offset)
                    current.copyInto(buffer, off, offset, offset + count)
                    offset += count
                    return count
                }
                override fun close() = closeSession()
            }
            return MediaSource(stream, mime, length) { closeSession(); cancellation.close() }
        } catch (e: Exception) {
            closeSession(); cancellation.close()
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("网页原文件传输已取消").apply { initCause(e) }
            }
            throw e
        }
    }
}
