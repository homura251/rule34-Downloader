package com.homura251.rule34downloader.network

import android.webkit.WebView
import com.homura251.rule34downloader.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class BrowserReadDiagnostics(private val requested: String) {
    val snapshot = AtomicReference<BrowserSnapshot?>()
    val nativeUrl = AtomicReference("")
    val committedUrl = AtomicReference("")
    val progress = AtomicInteger(0)
    val loadError = AtomicReference<String?>()
    val blockedNavigation = AtomicReference("")
    val snapshotError = AtomicReference<String?>()
    val httpError = AtomicInteger(0)
    val challengeHeader = AtomicBoolean(false)

    fun timeout(): Exception {
        val page = snapshot.get()
        val address = safeUrl(requested)
        if (page?.let { BrowserPagePolicy.isChallenge(it.document) } == true || challengeHeader.get()) {
            return CloudflareChallengeException("请求仍停在验证页面，请在设置中完成对应页面的「网页验证」后重新同步。页面：$address")
        }
        val reason = when {
            loadError.get() != null -> loadError.get().orEmpty()
            committedUrl.get().isBlank() -> "网页加载超时，服务器尚未提交主文档（进度 ${progress.get()}%）"
            page == null && snapshotError.get() != null -> "网页内容读取失败（${snapshotError.get()}）"
            page == null -> "WebView 未返回网页内容"
            page.document.baseUri().startsWith("about:") -> "WebView 仍停在空白文档，未取得请求页面"
            !page.hasDocument -> "网页主文档为空，尚未生成页面节点"
            !BrowserPagePolicy.matchesRequest(requested, page.document.baseUri()) -> "网站跳转到了其他页面：${safeUrl(page.document.baseUri())}"
            page.readyState == "loading" -> "网页加载超时，文档仍未解析完成"
            else -> "网页已打开，但缺少可识别的作品信息"
        }
        return BrowserReadException("$reason。请重新同步；若仍失败，请在「网页验证」中复制诊断。请求：$address")
    }

    fun report(reason: String): String {
        val page = snapshot.get()
        val provider = runCatching { WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } }.getOrNull()
        return listOf(
            "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "WebView: ${provider ?: "未知"}",
            "结果: ${reason.take(400)}",
            "请求: ${safeUrl(requested)}",
            "浏览器地址: ${safeUrl(nativeUrl.get())}",
            "已提交文档: ${safeUrl(committedUrl.get()).ifBlank { "未提交" }}",
            "当前: ${safeUrl(page?.document?.baseUri().orEmpty()).ifBlank { "未取得" }}",
            "加载进度: ${progress.get()}%",
            "标题: ${page?.document?.title()?.take(160).orEmpty()}",
            "文档: ${page?.readyState ?: "未取得"}",
            "文档节点: ${page?.hasDocument?.let { if (it) "存在" else "未创建" } ?: "未知"}",
            "可见性: ${page?.visibility ?: "未知"}",
            "视口: ${page?.viewport ?: "未知"}",
            "HTTP 错误状态: ${httpError.get().takeIf { it > 0 } ?: "无"}",
            "验证响应头: ${challengeHeader.get()}",
            "读取错误: ${snapshotError.get().orEmpty()}",
            "连接错误: ${loadError.get().orEmpty()}",
            "拦截导航: ${safeUrl(blockedNavigation.get())}",
        ).joinToString("\n")
    }

    companion object {
        /** Never expose cookies, credentials or Cloudflare query tokens. */
        fun safeUrl(value: String): String {
            val url = value.toHttpUrlOrNull() ?: return value.substringBefore('?').take(300)
            val cleaned = url.newBuilder().username("").password("").query(null).fragment(null)
            for (key in listOf("page", "s", "id", "tags", "pid")) {
                url.queryParameter(key)?.let { cleaned.addQueryParameter(key, it) }
            }
            return cleaned.build().toString().take(600)
        }
    }
}

class BrowserReadException(message: String, cause: Throwable? = null) : IOException(message, cause)
