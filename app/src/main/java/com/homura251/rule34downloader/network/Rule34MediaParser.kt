package com.homura251.rule34downloader.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Resolve originals without depending on the site's Options container class. */
internal object Rule34MediaParser {
    private val originalLabel = Regex("^original(?:\\s+(?:image|file|video))?(?:\\s*\\([^)]*\\))?$", RegexOption.IGNORE_CASE)
    private val mediaExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "mp4", "webm", "swf", "zip")

    fun originalUrl(document: Document): String? {
        // The legacy layout has an unclassified Options <ul>. Ignore links in
        // comments, sources and related-post thumbnails.
        for (anchor in document.select("a[href]")) {
            val label = anchor.text().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
            if (!originalLabel.matches(label) || isComment(anchor)) continue
            mediaUrl(anchor.attr("href"), document)?.let { return it }
        }
        // Samples and video posters are never valid fallback originals.
        for (element in document.select("video#gelcomVideoPlayer[src], video#gelcomVideoPlayer source[src], video#gelcomVideo[src], video#gelcomVideo source[src], img#image[src]")) {
            if (isComment(element)) continue
            val url = mediaUrl(element.attr("src"), document) ?: continue
            if (url.toHttpUrlOrNull()?.pathSegments?.any { it.equals("images", true) } == true) return url
        }
        return null
    }

    private fun mediaUrl(raw: String, document: Document): String? {
        if (raw.isBlank()) return null
        val base = document.baseUri().ifBlank { "https://rule34.xxx/" }.toHttpUrlOrNull() ?: return null
        val resolved = base.resolve(raw.trim()) ?: return null
        val url = resolved.newBuilder().scheme("https").build()
        if (!isRule34Url(url) || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val parts = url.pathSegments
        if (parts.any { it.lowercase() in setOf("sample", "samples", "thumbs", "thumbnails") }) return null
        val filename = parts.lastOrNull().orEmpty().lowercase()
        if (filename.startsWith("sample_") || filename.startsWith("sample-") ||
            filename.startsWith("thumbnail_") || filename.startsWith("thumbnail-")) return null
        return url.toString().takeIf { filename.substringAfterLast('.', "") in mediaExtensions }
    }

    private fun isComment(element: Element): Boolean = element.parents().any { parent ->
        parent.id().startsWith("comment", true) || parent.classNames().any { it.startsWith("comment", true) }
    }
}
