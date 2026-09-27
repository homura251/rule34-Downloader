package com.homura251.rule34downloader.network

import com.homura251.rule34downloader.data.Rule34Post
import com.homura251.rule34downloader.data.Rule34Tag
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Rule34HtmlClient {
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
        return postIds.mapIndexed { index, postId ->
            if (index > 0) Thread.sleep(DETAIL_REQUEST_DELAY_MS)
            parsePostDocument(fetchDocument(postUrl(postId)), postId)
        }
    }

    private fun fetchDocument(url: String): Document {
        var lastStatus = 0
        repeat(MAX_REQUEST_ATTEMPTS) { attempt ->
            val response = try {
                Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .timeout(READ_TIMEOUT_MS)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .execute()
            } catch (e: Exception) {
                if (attempt == MAX_REQUEST_ATTEMPTS - 1) {
                    throw RetryableApiException(
                        "匿名网页请求失败：${e.message ?: "网络错误"}",
                        e,
                    )
                }
                Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                return@repeat
            }

            lastStatus = response.statusCode()
            val document = response.parse()

            if (looksLikeChallenge(document)) {
                throw HtmlChallengeException(
                    "匿名网页访问被 Cloudflare/验证码拦截。可稍后重试，或在设置中配置 API 凭据。",
                )
            }

            if (lastStatus == 429 || lastStatus >= 500) {
                if (attempt == MAX_REQUEST_ATTEMPTS - 1) {
                    throw RetryableApiException("Rule34 匿名网页暂时不可用（HTTP $lastStatus）。")
                }
                Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                return@repeat
            }

            if (lastStatus !in 200..299) {
                throw ApiException("Rule34 匿名网页请求失败（HTTP $lastStatus）。")
            }

            return document
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
        val title = document.title()
        val body = document.body()?.text().orEmpty()
        val html = document.html()
        return title.contains("Just a moment", ignoreCase = true) ||
            title.contains("CAPTCHA", ignoreCase = true) ||
            body.contains("429 Rate limiting", ignoreCase = true) ||
            body.contains("Checking your browser", ignoreCase = true) ||
            body.contains("Enable JavaScript and cookies", ignoreCase = true) ||
            html.contains("challenge-platform", ignoreCase = true) ||
            html.contains("_cf_chl_opt", ignoreCase = true)
    }

    private fun postUrl(postId: Long): String =
        "$SITE/index.php?page=post&s=view&id=$postId"

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        const val POSTS_PER_PAGE = 42

        private const val SITE = "https://rule34.xxx"
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REQUEST_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 5_000L
        private const val DETAIL_REQUEST_DELAY_MS = 750L
        private const val USER_AGENT =
            "rule34-Downloader/1.1 (+https://github.com/homura251/rule34-Downloader)"

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

        private fun parsePostIds(document: Document): List<Long> =
            document.select(".image-list span.thumb")
                .mapNotNull { item ->
                    item.id()
                        .takeIf { it.matches(Regex("^s\\d+$")) }
                        ?.drop(1)
                        ?.toLongOrNull()
                        ?: item.selectFirst("a[href*=id=]")
                            ?.attr("href")
                            ?.let { href ->
                                Regex("[?&]id=(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toLongOrNull()
                            }
                }
                .distinct()
    }
}

class HtmlChallengeException(message: String) : ApiException(message)
