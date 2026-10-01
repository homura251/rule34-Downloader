package com.homura251.rule34downloader.network

import android.webkit.WebView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Observe actual committed documents, never execute JS on a provisional empty WebView. */
internal class BrowserDocumentObserver(val diagnostics: BrowserReadDiagnostics) {
    private var generation = 0
    private var capture: Any? = null

    fun started(url: String) {
        generation++
        capture = null
        diagnostics.nativeUrl.set(url)
        diagnostics.committedUrl.set("")
        diagnostics.snapshot.set(null)
        diagnostics.snapshotError.set(null)
        diagnostics.loadError.set(null)
        diagnostics.httpError.set(0)
        diagnostics.challengeHeader.set(false)
        diagnostics.progress.set(0)
    }

    fun committed(url: String) {
        diagnostics.nativeUrl.set(url)
        if (url.toHttpUrlOrNull() != null && diagnostics.loadError.get() == null) diagnostics.committedUrl.set(url)
    }

    fun inspect(view: WebView, callback: (Result<BrowserSnapshot>) -> Unit) {
        if (diagnostics.committedUrl.get().isBlank() || capture != null) return
        val token = Any()
        val startedGeneration = generation
        capture = token
        try {
            BrowserPagePolicy.snapshot(view) { result ->
                if (capture === token) capture = null
                if (generation != startedGeneration) return@snapshot
                result.onSuccess { diagnostics.snapshot.set(it); diagnostics.snapshotError.set(null) }
                result.onFailure { diagnostics.snapshotError.set("${it.javaClass.simpleName}: ${it.message?.take(160)}") }
                callback(result)
            }
        } catch (e: Exception) {
            if (capture === token) capture = null
            diagnostics.snapshotError.set("${e.javaClass.simpleName}: ${e.message?.take(160)}")
            callback(Result.failure(e))
        }
    }
}
