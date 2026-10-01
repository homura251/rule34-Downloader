package com.homura251.rule34downloader.network

import android.webkit.WebView
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class BrowserSnapshot(
    val document: Document,
    val contentType: String,
    val readyState: String,
    val visibility: String,
    val viewport: String,
    val hasDocument: Boolean = true,
)

/** Use WebView's native string encoding, independent of a site's JSON overrides. */
internal fun captureBrowserSnapshot(view: WebView, callback: (Result<BrowserSnapshot>) -> Unit) {
    view.evaluateJavascript("""
        (function() {
          return location.href + '\n' + document.contentType + '\n' + document.readyState + '\n' +
            document.visibilityState + '\n' + innerWidth + 'x' + innerHeight + '\n' +
            (document.documentElement ? document.documentElement.outerHTML : '');
        })()
    """.trimIndent()) { value ->
        callback(runCatching {
            val text = JSONTokener(value).nextValue() as? String
                ?: throw IllegalStateException("WebView 未返回页面字符串。")
            val fields = text.split('\n', limit = 6)
            check(fields.size == 6) { "WebView 页面数据格式异常（字段 ${fields.size}，长度 ${text.length}）。" }
            BrowserSnapshot(Jsoup.parse(fields[5], fields[0]), fields[1], fields[2], fields[3], fields[4], fields[5].isNotEmpty())
        })
    }
}
