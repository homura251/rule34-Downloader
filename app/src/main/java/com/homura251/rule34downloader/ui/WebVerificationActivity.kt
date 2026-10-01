package com.homura251.rule34downloader.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.homura251.rule34downloader.network.BrowserPagePolicy
import com.homura251.rule34downloader.network.BrowserDocumentObserver
import com.homura251.rule34downloader.network.BrowserReadDiagnostics
import com.homura251.rule34downloader.network.BrowserSnapshot
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.network.blockedPostMedia
import com.homura251.rule34downloader.network.browserSslError
import com.homura251.rule34downloader.network.isRule34UrlString

class WebVerificationActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var verified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val status = TextView(this).apply {
            text = "请完成站点验证。请求页面可读取后会自动返回，再点同步继续下载。"
            textSize = 16f
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(status)
        val buttons = LinearLayout(this)
        layout.addView(buttons)
        setContentView(layout)
        try {
            val network = Rule34Network.get(this)
            val target = network.verificationUrl
            val previousDiagnostic = network.browserDiagnostics
            val diagnostics = BrowserReadDiagnostics(target)
            val observer = BrowserDocumentObserver(diagnostics)

            fun complete(view: WebView, page: BrowserSnapshot?) {
                if (isFinishing || verified || webView !== view) return
                if (page != null) network.completeVerification(target, page, view)
                else network.adoptVerifiedView(target, view)
                verified = true
                // Ownership moves to the reader before Activity destruction.
                webView = null
                Toast.makeText(this, "请求页面已可读取，请重新同步。", Toast.LENGTH_LONG).show()
                finish()
            }

            fun inspect() {
                val view = webView ?: return
                if (isFinishing || verified) return
                observer.inspect(view) captured@ { captured ->
                    if (isFinishing || verified || webView !== view) return@captured
                    // A blank/error document must never replace the connection failure.
                    if (diagnostics.loadError.get() != null) return@captured
                    captured.onFailure { status.text = "网页内容读取失败：${it.message}。可重新加载，或复制诊断反馈。" }
                    captured.onSuccess { page ->
                        val document = page.document
                        if (isRule34UrlString(document.baseUri()) && BrowserPagePolicy.matchesRequest(target, document.baseUri()) &&
                            BrowserPagePolicy.isReadable(document, page.contentType, page.readyState)) {
                            complete(view, page)
                        } else if (BrowserPagePolicy.isChallenge(document)) {
                            status.text = "仍在验证页面，请完成下方操作。若反复验证，可复制诊断反馈。"
                        } else if (diagnostics.httpError.get() >= 400) {
                            status.text = "请求页面返回 HTTP ${diagnostics.httpError.get()}。可重新加载或复制诊断。"
                        } else if (document.baseUri().startsWith("about:") || !page.hasDocument) {
                            status.text = "请求页面尚未生成主文档，正在等待加载。可重新加载或复制诊断。"
                        } else if (!BrowserPagePolicy.matchesRequest(target, document.baseUri())) {
                            status.text = "网站跳转到了其他页面，尚未读到请求的作品。可复制诊断反馈。"
                        } else if (page.readyState in listOf("interactive", "complete")) {
                            status.text = "页面已打开，正在等待请求的作品信息。若一直无法加载，请复制诊断反馈。"
                        }
                    }
                }
            }

            fun loadTarget(view: WebView) {
                observer.started(target)
                status.text = "正在打开请求页面…"
                view.onResume()
                view.loadUrl(target)
            }

            fun openPage() {
                try {
                    status.text = "正在打开请求页面…"
                    val view = network.createWebView(this)
                    webView = view
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { observer.started(url) }
                        override fun onPageCommitVisible(view: WebView, url: String) { observer.committed(url); inspect() }
                        override fun onPageFinished(view: WebView, url: String) { observer.committed(url); inspect() }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val blocked = BrowserPagePolicy.blockNavigation(request.url.toString(), request.isForMainFrame, ::isRule34UrlString)
                            if (blocked) diagnostics.blockedNavigation.set(request.url.toString())
                            return blocked
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = blockedPostMedia(request)
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) {
                                val message = "网页连接失败（${error.errorCode}）：${error.description}。可重新加载或复制诊断。"
                                diagnostics.loadError.set(message)
                                status.text = message
                            }
                        }
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel()
                            if (BrowserPagePolicy.matchesRequest(target, error.url) || error.url == diagnostics.nativeUrl.get()) {
                                val message = browserSslError(error)
                                diagnostics.loadError.set(message)
                                status.text = message
                            }
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) {
                                diagnostics.httpError.set(response.statusCode)
                                diagnostics.challengeHeader.set(response.responseHeaders.orEmpty().any { (key, value) ->
                                    key.equals("cf-mitigated", true) && value.equals("challenge", true)
                                })
                                status.text = "页面返回 HTTP ${response.statusCode}，尚未确认可读取。请完成验证或重新加载。"
                            }
                        }
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            (view.parent as? android.view.ViewGroup)?.removeView(view)
                            view.destroy()
                            if (webView === view) webView = null
                            val message = "网页渲染进程已退出，可点重新加载。"
                            diagnostics.loadError.set(message)
                            status.text = message
                            return true
                        }
                    }
                    view.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, progress: Int) { diagnostics.progress.set(progress) }
                    }
                    view.setDownloadListener { url, _, _, type, _ ->
                        if (isRule34UrlString(url) && BrowserPagePolicy.matchesRequest(target, url) && BrowserPagePolicy.isDownloadable(type)) {
                            complete(view, null)
                        }
                    }
                    layout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                    loadTarget(view)
                } catch (e: Exception) {
                    val message = "无法打开网页验证：${e.message}。请检查 Android System WebView 是否可用。"
                    diagnostics.loadError.set(message)
                    status.text = message
                }
            }

            buttons.addView(Button(this).apply {
                text = "重新加载"
                setOnClickListener { webView?.let(::loadTarget) ?: openPage() }
            })
            buttons.addView(Button(this).apply {
                text = "复制诊断"
                setOnClickListener {
                    val current = network.verificationDiagnostic(diagnostics, status.text.toString())
                    val diagnostic = "上次后台读取：\n${previousDiagnostic.ifBlank { "暂无" }}\n\n当前验证：\n$current"
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("网页验证诊断", diagnostic))
                    Toast.makeText(this@WebVerificationActivity, "诊断已复制", Toast.LENGTH_SHORT).show()
                }
            })
            buttons.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
            openPage()
            main.postDelayed(object : Runnable {
                override fun run() {
                    if (!isFinishing && !verified) { inspect(); main.postDelayed(this, 300L) }
                }
            }, 300L)
        } catch (e: Exception) {
            status.text = "无法初始化网页验证：${e.message}。请检查 Android System WebView 是否可用。"
            buttons.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        }
    }

    override fun onPause() { webView?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); webView?.onResume() }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        webView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.stopLoading(); view.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
