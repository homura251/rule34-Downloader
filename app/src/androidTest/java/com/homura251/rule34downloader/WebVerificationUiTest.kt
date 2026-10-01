package com.homura251.rule34downloader

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homura251.rule34downloader.data.AppPreferences
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.ui.WebVerificationActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WebVerificationUiTest {
    @Test fun reloadLeavesAboutBlankAndConnectionErrorSurvivesDocumentPolling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = AppPreferences(context)
        val previous = preferences.verificationUrl
        val target = "https://rule34.xxx/index.php?page=post&s=view&id=18905312"
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Rule34Network.get(context).rememberVerificationUrl(target) }
        try {
            ActivityScenario.launch(WebVerificationActivity::class.java).use { scenario ->
                val blank = CountDownLatch(1)
                val requested = CountDownLatch(1)
                val finished = CountDownLatch(1)
                val failed = CountDownLatch(1)
                val started = AtomicReference("")
                val connectionError = AtomicReference("")
                scenario.onActivity { activity ->
                    val view = descendants(activity.window.decorView).filterIsInstance<WebView>().single()
                    view.stopLoading()
                    val delegate = view.webViewClient
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            delegate.onPageStarted(view, url, favicon)
                            if (url != "about:blank") { started.set(url); requested.countDown() }
                        }
                        override fun onPageCommitVisible(view: WebView, url: String) { delegate.onPageCommitVisible(view, url) }
                        override fun onPageFinished(view: WebView, url: String) {
                            delegate.onPageFinished(view, url)
                            if (url == "about:blank") blank.countDown() else if (url == target) finished.countDown()
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (request.url.host == "127.0.0.1") null else
                                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("<title>Controlled verification document</title><p>Waiting for original</p>".toByteArray()))
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            delegate.onReceivedError(view, request, error)
                            if (request.isForMainFrame && request.url.host == "127.0.0.1") {
                                connectionError.set(error.description.toString()); failed.countDown()
                            }
                        }
                    }
                    view.loadUrl("about:blank")
                }
                assertTrue(blank.await(10, TimeUnit.SECONDS))
                onView(withText("重新加载")).perform(click())
                assertTrue(requested.await(10, TimeUnit.SECONDS))
                assertEquals(target, started.get())
                assertTrue(finished.await(10, TimeUnit.SECONDS))

                val refusedUrl = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}/connection-refused" }
                val polled = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val view = descendants(activity.window.decorView).filterIsInstance<WebView>().single()
                    view.loadUrl(refusedUrl)
                }
                assertTrue(failed.await(10, TimeUnit.SECONDS))
                assertTrue(connectionError.get().isNotBlank())
                scenario.onActivity {
                    Handler(Looper.getMainLooper()).postDelayed({ polled.countDown() }, 900)
                }
                assertTrue(polled.await(5, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString().contains(connectionError.get())
                    })
                }
                onView(withText("复制诊断")).perform(click())
                scenario.onActivity { activity ->
                    val text = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                    assertTrue(text.contains("连接错误: 网页连接失败"))
                    assertTrue(text.contains(connectionError.get()))
                    assertFalse(text.substringAfter("当前验证：").contains("正在等待请求的作品信息"))
                    assertTrue(text.contains("已提交文档: 未提交"))
                }
            }
        } finally { preferences.verificationUrl = previous }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
