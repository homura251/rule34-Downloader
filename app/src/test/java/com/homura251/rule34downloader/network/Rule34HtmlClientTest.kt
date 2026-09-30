package com.homura251.rule34downloader.network

import org.junit.Assert.assertEquals
import org.junit.Test

class Rule34HtmlClientTest {
    @Test
    fun parsesAndNormalizesThumbnailsIncludingLazyImages() {
        val html = """
            <div class="image-list">
              <span class="thumb" id="s42"><img src="//rule34.xxx/thumbs/42.jpg"></span>
              <span class="thumb"><a href="/index.php?page=post&s=view&id=43"><img data-src="http://rule34.xxx/thumbs/43.jpg" src="placeholder.jpg"></a></span>
              <span class="thumb" id="s44"><img src="/thumbs/44.jpg"></span>
              <span class="thumb" id="s45"><img src="javascript:alert(1)"></span>
              <span class="thumb" id="s46"><img></span>
            </div>
        """.trimIndent()
        assertEquals(
            mapOf(42L to "https://rule34.xxx/thumbs/42.jpg", 43L to "https://rule34.xxx/thumbs/43.jpg", 44L to "https://rule34.xxx/thumbs/44.jpg"),
            Rule34HtmlClient.thumbnailUrlsFromHtml(html),
        )
    }
    @Test
    fun parsesPostIdsFromThumbnailItems() {
        val html = """
            <div class="image-list">
              <span class="thumb" id="s18875738"><a href="/index.php?page=post&s=view&id=18875738"></a></span>
              <span class="thumb" id="s18875739"><a href="/index.php?page=post&s=view&id=18875739"></a></span>
            </div>
        """.trimIndent()

        assertEquals(
            listOf(18875738L, 18875739L),
            Rule34HtmlClient.parsePostIdsFromHtml(html),
        )
    }

    @Test
    fun parsesOriginalImageLink() {
        val html = """
            <div class="link-list">
              <a href="https://wimg.rule34.xxx/images/ab/cd/0123456789abcdef0123456789abcdef.jpg">
                Original image
              </a>
            </div>
        """.trimIndent()

        assertEquals(
            "https://wimg.rule34.xxx/images/ab/cd/0123456789abcdef0123456789abcdef.jpg",
            Rule34HtmlClient.originalMediaUrlFromHtml(html),
        )
    }
}
