package com.homura251.rule34downloader.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.network.blockedPostMedia
import com.homura251.rule34downloader.network.isRule34UrlString

class WebVerificationActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var verified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val network = Rule34Network.get(this)
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val padding = (16 * resources.displayMetrics.density).toInt()
            val status = TextView(this).apply {
                text = "请完成站点验证。确认请求页面可读取后会自动返回，再点同步继续下载。"
                textSize = 16f
                setPadding(padding, padding, padding, padding)
            }
            layout.addView(status)
            val buttons = LinearLayout(this)
            buttons.addView(Button(this).apply {
                text = "重新加载"
                setOnClickListener { webView?.reload() }
            })
            buttons.addView(Button(this).apply {
                text = "返回"
                setOnClickListener { finish() }
            })
            layout.addView(buttons)
            val view = network.createWebView(this)
            webView = view
            fun inspect() {
                if (isFinishing || verified) return
                BrowserPagePolicy.snapshot(view) { document, type ->
                    if (!isFinishing && !verified && isRule34UrlString(document.baseUri()) &&
                        BrowserPagePolicy.matchesRequest(network.verificationUrl, document.baseUri()) && BrowserPagePolicy.isReadable(document, type)) {
                        verified = true
                        network.cookies.flush()
                        Toast.makeText(this, "请求页面已可读取，请重新同步。", Toast.LENGTH_LONG).show()
                        finish()
                    } else if (BrowserPagePolicy.isChallenge(document)) {
                        status.text = "仍在验证页面，请完成下方操作。若反复验证，请更新 Android System WebView 后重试。"
                    }
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = inspect()
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    BrowserPagePolicy.blockNavigation(request.url.toString(), request.isForMainFrame, ::isRule34UrlString)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    blockedPostMedia(request)
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) status.text = "网页连接失败（${error.errorCode}）：${error.description}。可点重新加载。"
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) status.text = "页面返回 HTTP ${errorResponse.statusCode}，尚未确认可读取。请完成验证或重新加载。"
                }
            }
            layout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(layout)
            // Keep a valid clearance; resetting it on every visit caused verification loops.
            view.loadUrl(network.verificationUrl)
            main.postDelayed(object : Runnable {
                override fun run() {
                    if (!isFinishing && !verified) { inspect(); main.postDelayed(this, 300L) }
                }
            }, 300L)
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开网页验证，请更新或启用 Android System WebView。", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        webView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
