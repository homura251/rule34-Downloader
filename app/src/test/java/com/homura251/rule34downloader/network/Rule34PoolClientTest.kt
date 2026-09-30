package com.homura251.rule34downloader.network

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class Rule34PoolClientTest {
    private val base = Rule34PoolClient.poolUrl(123)
    private fun parse(html: String, offset: Int = 0) = Rule34PoolClient.parsePage(Jsoup.parse(html, base), 123, offset)

    @Test
    fun keepsPoolOrderAndRecognizesPIdsAndLazyThumbnails() {
        val page = parse("""
            <h4>Pool: Example &amp; series</h4>
            <div class="image-list">
              <span class="thumb" id="p90"><a><img src="//rule34.xxx/thumbs/90.jpg"></a></span>
              <span class="thumb" id="p3"><a><img data-src="http://rule34.xxx/thumbs/3.jpg" src="placeholder.jpg"></a></span>
              <span class="thumb"><a href="/index.php?page=post&amp;s=view&amp;id=41"><img src="/thumbs/41.jpg"></a></span>
              <span class="thumb" id="p90"><a><img src="/thumbs/90.jpg"></a></span>
            </div>
        """)
        assertEquals("Example & series", page.title)
        assertEquals(listOf(90L, 3L, 41L), page.posts.map { it.id })
        assertEquals("https://rule34.xxx/thumbs/3.jpg", page.posts[1].previewUrl)
        assertNull(page.nextOffset)
    }

    @Test
    fun followsNextPoolOffsetAndIgnoresOtherPoolsHostsAndBackLinks() {
        val page = parse("""
            <h4>Pool: Example</h4><span class="thumb" id="p7"></span>
            <div id="paginator">
              <a href="?page=pool&amp;s=show&amp;id=123&amp;pid=0">1</a>
              <a href="?id=123&amp;page=pool&amp;s=show&amp;pid=45">2</a>
              <a href="?page=pool&amp;s=show&amp;id=123&amp;pid=90">3</a>
              <a href="?page=pool&amp;s=show&amp;id=124&amp;pid=1">Other</a>
              <a href="https://example.com/?page=pool&amp;s=show&amp;id=123&amp;pid=2">External</a>
            </div>
        """)
        assertEquals(45, page.nextOffset)
    }

    @Test
    fun readsAllPagesIncludingOldIdsAndDeduplicatesWithoutReordering() {
        val offsets = mutableListOf<Int>()
        val pool = Rule34PoolClient.collect(123) { offset ->
            offsets += offset
            when (offset) {
                0 -> PoolPage("Example", listOf(PoolPost(90), PoolPost(3)), 45)
                45 -> PoolPage("Example", listOf(PoolPost(3), PoolPost(2), PoolPost(80)), null)
                else -> error("Unexpected offset")
            }
        }
        assertEquals(listOf(0, 45), offsets)
        assertEquals(listOf(90L, 3L, 2L, 80L), pool.posts.map { it.id })
    }

    @Test
    fun handlesFullFortyFiveItemPageWithoutNavigationAndEmptyLastPage() {
        val thumbs = (1..45).joinToString("") { "<span class=\"thumb\" id=\"p$it\"></span>" }
        val first = parse("<h4>Pool: Boundary</h4>$thumbs")
        assertEquals(45, first.posts.size)
        assertEquals(45, first.nextOffset)
        val seen = mutableListOf<Int>()
        val pool = Rule34PoolClient.collect(123) { offset ->
            seen += offset
            if (offset == 0) first else parse("<h4>Pool: Boundary</h4>", offset)
        }
        assertEquals(45, pool.posts.size)
        assertEquals(listOf(0, 45), seen)
    }

    @Test
    fun failsClearlyForUnavailablePoolAndInvalidThumbnailSources() {
        assertThrows(ApiException::class.java) { parse("<h1>Access denied</h1>") }
        val page = parse("<h4>Pool: Example</h4><span class=\"thumb\" id=\"p7\"><img src=\"javascript:alert(1)\"></span>")
        assertEquals(7L, page.posts.single().id)
        assertNull(page.posts.single().previewUrl)
    }

    @Test
    fun rejectsRepeatedPagesAndOffsetsInsteadOfLoopingOrReportingSuccess() {
        assertThrows(RetryableApiException::class.java) {
            Rule34PoolClient.collect(123) { PoolPage("Example", listOf(PoolPost(7)), 45) }
        }
        assertThrows(RetryableApiException::class.java) {
            Rule34PoolClient.collect(123) { PoolPage("Example", listOf(PoolPost(7)), 0) }
        }
    }

    @Test
    fun propagatesPauseOrCancellationDuringPagination() {
        val offsets = mutableListOf<Int>()
        assertThrows(CancellationException::class.java) {
            Rule34PoolClient.collect(123) { offset ->
                offsets += offset
                if (offset != 0) throw CancellationException("Paused")
                PoolPage("Example", listOf(PoolPost(7)), 45)
            }
        }
        assertEquals(listOf(0, 45), offsets)
    }
}
