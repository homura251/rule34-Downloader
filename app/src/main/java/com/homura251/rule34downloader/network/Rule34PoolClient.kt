package com.homura251.rule34downloader.network

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

data class PoolPost(val id: Long, val previewUrl: String? = null)
data class PoolPage(val title: String, val posts: List<PoolPost>, val nextOffset: Int?)
data class Rule34Pool(val id: Long, val title: String, val posts: List<PoolPost>)

class Rule34PoolClient(
    private val html: Rule34HtmlClient,
    private val checkActive: () -> Unit = {},
) {
    fun getPage(poolId: Long, offset: Int = 0): PoolPage {
        require(poolId > 0 && offset >= 0)
        checkActive()
        return parsePage(html.fetchDocument(poolUrl(poolId) + "&pid=$offset"), poolId, offset)
    }

    fun getPool(poolId: Long): Rule34Pool = collect(poolId) { offset ->
        checkActive()
        if (offset > 0) Thread.sleep(750L)
        getPage(poolId, offset)
    }

    companion object {
        const val POSTS_PER_PAGE = 45
        fun key(poolId: Long): String = "pool:$poolId"
        fun poolUrl(poolId: Long): String = "https://rule34.xxx/index.php?page=pool&s=show&id=$poolId"

        internal fun parsePage(document: Document, poolId: Long, offset: Int): PoolPage {
            val heading = document.select("h1, h2, h3, h4").firstOrNull {
                it.text().trim().startsWith("Pool:", ignoreCase = true)
            } ?: throw ApiException("图集 #$poolId 不存在或当前无权访问。")
            val title = heading.text().trim().substringAfter(':').trim().ifBlank { "Pool #$poolId" }
            val items = document.select(".thumb").ifEmpty {
                document.select("a[href]").filter { it.selectFirst("img") != null }
            }
            val posts = items.mapNotNull { item ->
                val id = thumbnailId(item) ?: return@mapNotNull null
                val image = item.selectFirst("img")
                val raw = image?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                PoolPost(id, raw?.takeIf(String::isNotBlank)?.let { mediaUrl(it, document.baseUri()) })
            }.distinctBy { it.id }
            val next = document.select("a[href]").mapNotNull { anchor ->
                val href = anchor.absUrl("href")
                if (PostUrlParser.parsePoolId(href) != poolId) return@mapNotNull null
                Regex("[?&]pid=(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?.takeIf { it > offset }
            }.minOrNull() ?: if (posts.size == POSTS_PER_PAGE && offset <= Int.MAX_VALUE - POSTS_PER_PAGE) {
                offset + POSTS_PER_PAGE
            } else null
            return PoolPage(title, posts, next)
        }

        internal fun collect(poolId: Long, loadPage: (Int) -> PoolPage): Rule34Pool {
            val ordered = linkedMapOf<Long, PoolPost>()
            var offset = 0
            var title = "Pool #$poolId"
            do {
                val page = loadPage(offset)
                if (offset == 0) title = page.title
                var added = 0
                page.posts.forEach { post ->
                    if (ordered.putIfAbsent(post.id, post) == null) added++
                }
                val next = page.nextOffset ?: break
                if (next <= offset || added == 0) {
                    throw RetryableApiException("图集分页未前进，请稍后重试；已有下载会保留。")
                }
                offset = next
            } while (true)
            return Rule34Pool(poolId, title, ordered.values.toList())
        }

        private fun thumbnailId(item: Element): Long? {
            fun id(value: String) = value.takeIf { it.matches(Regex("^[sp]\\d+$")) }
                ?.drop(1)?.toLongOrNull()?.takeIf { it > 0 }
            return id(item.id()) ?: item.select("a[id]").firstNotNullOfOrNull { id(it.id()) }
                ?: (listOf(item) + item.select("a[href]")).firstNotNullOfOrNull {
                    PostUrlParser.parsePostId(it.absUrl("href"))
                }
        }

        private fun mediaUrl(value: String, base: String): String? = runCatching {
            val uri = URI(base).resolve(value)
            when (uri.scheme?.lowercase()) {
                "https" -> uri.toString()
                "http" -> URI("https", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
                else -> null
            }
        }.getOrNull()
    }
}
