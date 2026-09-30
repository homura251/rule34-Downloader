package com.homura251.rule34downloader.network

import com.homura251.rule34downloader.data.Rule34Post
import com.homura251.rule34downloader.data.Rule34Tag
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Rule34HtmlClient(private val httpClient: OkHttpClient) {
    data class ResolvedPost(
        val post: Rule34Post,
        val artists: List<Rule34Tag>,
    )

    fun getPostWithArtists(postId: Long): ResolvedPost {
        val document = fetchDocument(postUrl(postId))
        return ResolvedPost(
            post = parsePostDocument(document, postId),
            artists = parseArtistTags(document),
        )
    }

    fun searchPosts(
        artistTag: String,
        afterPostId: Long,
        page: Int,
    ): List<Rule34Post> {
        val query = if (afterPostId > 0L) {
            "$artistTag id:>$afterPostId"
        } else {
            artistTag
        }
        val offset = page.coerceAtLeast(0) * POSTS_PER_PAGE
        val document = fetchDocument(
            "$SITE/index.php?page=post&s=list&tags=${encode(query)}&pid=$offset",
        )
        val postIds = parsePostIds(document)
        val previews = parseThumbnailUrls(document)
        return postIds.mapIndexed { index, postId ->
            if (index > 0) Thread.sleep(DETAIL_REQUEST_DELAY_MS)
            val post = parsePostDocument(fetchDocument(postUrl(postId)), postId)
            post.copy(previewUrl = previews[postId] ?: post.previewUrl)
        }
    }

    private fun fetchDocument(url: String): Document {
        var lastStatus = 0
        repeat(MAX_REQUEST_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder().url(url)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    lastStatus = response.code
                    if (lastStatus == 429 || lastStatus >= 500) {
                        if (attempt == MAX_REQUEST_ATTEMPTS - 1) {
                            throw RetryableApiException("Rule34 匿名网页暂时不可用（HTTP $lastStatus）。")
                        }
                    } else {
                        if (!response.isSuccessful) {
                            throw ApiException("Rule34 匿名网页请求失败（HTTP $lastStatus）。")
                        }
                        val body = response.body?.string()
                            ?: throw RetryableApiException("Rule34 匿名网页返回了空响应。")
                        val document = Jsoup.parse(body, response.request.url.toString())
                        if (looksLikeChallenge(document)) {
                            throw HtmlChallengeException(CloudflareChallengeException().message.orEmpty())
                        }
                        return document
                    }
                }
            } catch (e: CloudflareChallengeException) {
                throw HtmlChallengeException(e.message.orEmpty(), e)
            } catch (e: IOException) {
                if (Thread.currentThread().isInterrupted) throw e
                if (attempt == MAX_REQUEST_ATTEMPTS - 1) {
                    throw RetryableApiException(
                        "匿名网页请求失败：${e.message ?: "网络错误"}",
                        e,
                    )
                }
            }
            Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
        }
        throw RetryableApiException("Rule34 匿名网页暂时不可用（HTTP $lastStatus）。")
    }

    private fun parsePostDocument(document: Document, postId: Long): Rule34Post {
        val fileUrl = originalMediaUrl(document)
            ?: throw ApiException("帖子 #$postId 未找到可下载的 Original image 链接。")
        val filename = runCatching { URI(fileUrl).path.substringAfterLast('/') }.getOrDefault("")
        val stem = filename.substringBeforeLast('.', filename).removePrefix("sample_")
        val md5 = stem.takeIf { it.matches(Regex("^[0-9a-fA-F]{32}$")) }.orEmpty()

        return Rule34Post(
            id = postId,
            fileUrl = fileUrl,
            md5 = md5,
            tags = parseAllTags(document),
            previewUrl = document.selectFirst("img#image")?.absUrl("src")
                ?.let { normalizeMediaUrl(it, document.baseUri()) },
        )
    }

    private fun parseAllTags(document: Document): List<String> =
        document.select("#tag-sidebar li[class*=tag-type-]")
            .mapNotNull(::tagNameFromRow)
            .distinct()

    private fun parseArtistTags(document: Document): List<Rule34Tag> =
        document.select("#tag-sidebar li.tag-type-artist")
            .mapNotNull { row ->
                val name = tagNameFromRow(row) ?: return@mapNotNull null
                Rule34Tag(
                    name = name,
                    type = Rule34Client.ARTIST_TAG_TYPE,
                    count = tagCountFromRow(row),
                )
            }
            .distinctBy { it.name }

    private fun tagNameFromRow(row: Element): String? {
        val anchors = row.select("a[href*=tags=]")
        val namedAnchor = anchors.firstOrNull { anchor ->
            val text = anchor.text().trim()
            text.isNotEmpty() && text != "?" && text != "+" && text != "-"
        }
        val hrefTag = namedAnchor
            ?.attr("href")
            ?.let(::tagFromHref)
        if (!hrefTag.isNullOrBlank()) return hrefTag

        return namedAnchor
            ?.text()
            ?.trim()
            ?.replace(Regex("\\s+"), "_")
            ?.takeIf(String::isNotBlank)
    }

    private fun tagFromHref(href: String): String? {
        val normalized = href.replace("&amp;", "&")
        val raw = Regex("[?&]tags=([^&#]*)", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        val decoded = runCatching {
            URLDecoder.decode(raw, Charsets.UTF_8.name())
        }.getOrDefault(raw)
        return decoded
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.takeIf(String::isNotBlank)
    }

    private fun tagCountFromRow(row: Element): Long {
        val match = Regex("(\\d[\\d,]*)\\s*$").find(row.text()) ?: return 0L
        return match.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
    }

    private fun originalMediaUrl(document: Document): String? {
        val href = document
            .select(".link-list a[href]")
            .firstOrNull { it.text().contains("Original image", ignoreCase = true) }
            ?.let { anchor -> anchor.absUrl("href").ifBlank { anchor.attr("href") } }
            ?.trim()
            ?: return null
        return normalizeMediaUrl(href, document.baseUri())
    }

    private fun normalizeMediaUrl(value: String, baseUri: String): String? {
        val resolved = runCatching {
            val base = URI(baseUri.ifBlank { SITE })
            base.resolve(value)
        }.getOrNull() ?: return null

        return when (resolved.scheme?.lowercase()) {
            "https" -> resolved.toString()
            "http" -> URI(
                "https",
                resolved.userInfo,
                resolved.host,
                resolved.port,
                resolved.path,
                resolved.query,
                resolved.fragment,
            ).toString()
            else -> null
        }
    }

    private fun looksLikeChallenge(document: Document): Boolean {
        val title = document.title().trim()
        val html = document.html()
        return title.startsWith("Just a moment", ignoreCase = true) ||
            title.equals("CAPTCHA", ignoreCase = true) ||
            html.contains("_cf_chl_opt", ignoreCase = true) ||
            document.selectFirst("#challenge-form, #cf-challenge-running") != null
    }

    private fun postUrl(postId: Long): String =
        "$SITE/index.php?page=post&s=view&id=$postId"

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        const val POSTS_PER_PAGE = 42

        private const val SITE = "https://rule34.xxx"
        private const val MAX_REQUEST_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 5_000L
        private const val DETAIL_REQUEST_DELAY_MS = 750L

        internal fun parsePostIdsFromHtml(html: String): List<Long> {
            val document = Jsoup.parse(html, SITE)
            return parsePostIds(document)
        }

        internal fun originalMediaUrlFromHtml(html: String, baseUrl: String = SITE): String? {
            val document = Jsoup.parse(html, baseUrl)
            val anchor = document
                .select(".link-list a[href]")
                .firstOrNull { it.text().contains("Original image", ignoreCase = true) }
                ?: return null
            return anchor.absUrl("href").ifBlank { anchor.attr("href") }
        }

        internal fun thumbnailUrlsFromHtml(html: String): Map<Long, String> =
            parseThumbnailUrls(Jsoup.parse(html, SITE))

        private fun parseThumbnailUrls(document: Document): Map<Long, String> = buildMap {
            document.select(".image-list span.thumb").forEach { item ->
                val id = postIdFromThumbnail(item) ?: return@forEach
                val image = item.selectFirst("img") ?: return@forEach
                val raw = image.attr("data-src").ifBlank { image.attr("src") }
                val uri = runCatching { URI(document.baseUri()).resolve(raw) }.getOrNull() ?: return@forEach
                if (raw.isNotBlank() && uri.scheme in listOf("http", "https")) {
                    val url = if (uri.scheme == "http") {
                        URI("https", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
                    } else uri.toString()
                    put(id, url)
                }
            }
        }

        private fun parsePostIds(document: Document): List<Long> =
            document.select(".image-list span.thumb")
                .mapNotNull(::postIdFromThumbnail)
                .distinct()

        private fun postIdFromThumbnail(item: Element): Long? =
            item.id().takeIf { it.matches(Regex("^s\\d+$")) }?.drop(1)?.toLongOrNull()
                ?: item.selectFirst("a[href*=id=]")?.attr("href")?.let { href ->
                    Regex("[?&]id=(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toLongOrNull()
                }
    }
}

class HtmlChallengeException(message: String, cause: Throwable? = null) : ApiException(message, cause)
