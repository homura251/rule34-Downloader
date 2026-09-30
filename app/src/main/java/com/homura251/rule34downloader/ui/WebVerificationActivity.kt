package com.homura251.rule34downloader.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.network.isRule34UrlString
import okhttp3.HttpUrl.Companion.toHttpUrl

class WebVerificationActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val network = Rule34Network.get(this)
            val url = network.verificationUrl.toHttpUrl()
            val previousClearance = network.cookies.clearance(url)
            network.cookies.clearClearance(url)

            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val padding = (16 * resources.displayMetrics.density).toInt()
            layout.addView(TextView(this).apply {
                text = "请完成站点验证。验证成功后会自动返回，再点同步继续下载。"
                textSize = 16f
                setPadding(padding, padding, padding, padding)
            })
            layout.addView(Button(this).apply {
                text = "返回"
                setOnClickListener { finish() }
            })

            fun checkClearance(): Boolean {
                if (isFinishing) return true
                val cookie = network.cookies.clearance(url)
                if (!cookie.isNullOrBlank() && cookie != previousClearance) {
                    network.cookies.flush()
                    Toast.makeText(this, "网页验证成功，请重新同步。", Toast.LENGTH_LONG).show()
                    finish()
                    return true
                }
                return false
            }

            val view = network.createWebView(this)
            webView = view
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    checkClearance()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isRule34UrlString(request.url.toString())
            }
            layout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(layout)
            view.loadUrl(url.toString())

            main.postDelayed(object : Runnable {
                override fun run() {
                    if (!isFinishing && !checkClearance()) main.postDelayed(this, 250L)
                }
            }, 250L)
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
