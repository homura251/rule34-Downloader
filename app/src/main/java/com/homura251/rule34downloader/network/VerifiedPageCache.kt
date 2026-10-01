package com.homura251.rule34downloader.network

import org.jsoup.nodes.Document

/** One short-lived, one-use document from an explicitly completed verification. */
internal class VerifiedPageCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var entry: Pair<Long, Document>? = null

    @Synchronized fun put(document: Document) {
        entry = now() to document.clone()
    }

    @Synchronized fun take(url: String): Document? {
        val (created, document) = entry ?: return null
        if (now() - created > 120_000) { entry = null; return null }
        if (!BrowserPagePolicy.matchesRequest(url, document.baseUri())) return null
        entry = null
        return document.clone()
    }
}
