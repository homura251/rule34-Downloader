package com.homura251.rule34downloader.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun readsLegacyOptionsWithoutALinkListClass() {
        // Structure from pikadick-rs/lib/rule34-rs/test_data/gif_post.html:
        // Options is an ordinary <ul>, followed by the post's <img id=image>.
        val url = "https://us.rule34.xxx//images/3410/7701dd9004397b7cf921292f828c77b1.gif"
        assertEquals(url, Rule34HtmlClient.originalMediaUrlFromHtml("""
            <div id="post-view"><div class="sidebar"><h5>Options</h5><ul>
              <li><a href="#">Edit</a></li>
              <li><a href="$url" style="font-weight:bold">Original image</a></li>
            </ul></div></div>
        """))
    }

    @Test fun resolvesNestedLabelsRelativeLinksAndHttpWithoutLosingQueries() {
        for ((raw, expected) in listOf(
            "//wimg.rule34.xxx/images/42/original.jpg?x=1&amp;y=2" to "https://wimg.rule34.xxx/images/42/original.jpg?x=1&y=2",
            "/images/42/original.jpg" to "https://rule34.xxx/images/42/original.jpg",
            "http://wimg.rule34.xxx/images/42/original.jpg" to "https://wimg.rule34.xxx/images/42/original.jpg",
        )) {
            assertEquals(expected, Rule34HtmlClient.originalMediaUrlFromHtml(
                "<ul><li><a href='$raw'><strong>Original</strong>&nbsp; image</a></li></ul>",
            ))
        }
    }

    @Test fun prefersExplicitOriginalOverTheDisplayedSampleOrVideoPoster() {
        val url = "https://webm.rule34.xxx//images/1547/e0fc72e060628a36d7267f5c80ed37f2.mp4?11299642"
        assertEquals(url, Rule34HtmlClient.originalMediaUrlFromHtml("""
            <img id="image" src="https://wimg.rule34.xxx/samples/42/sample_abcdef.jpg">
            <video id="gelcomVideoPlayer" poster="https://wimg.rule34.xxx/images/42/poster.jpg"></video>
            <ul><li><a href="$url">Original image</a></li></ul>
        """))
    }

    @Test fun fallsBackToTheOriginalPostImageOrVideoSource() {
        val image = "https://wimg.rule34.xxx//images/42/0123456789abcdef0123456789abcdef.png?42"
        assertEquals(image, Rule34HtmlClient.originalMediaUrlFromHtml("<img id='image' src='$image'>"))
        val video = "https://webm.rule34.xxx/images/42/0123456789abcdef0123456789abcdef.webm"
        assertEquals(video, Rule34HtmlClient.originalMediaUrlFromHtml(
            "<video id='gelcomVideoPlayer' poster='/images/42/poster.jpg'><source src='$video' type='video/webm'></video>",
        ))
    }

    @Test fun neverGuessesAnOriginalFromSamplesThumbnailsOrPosters() {
        for (html in listOf(
            "<img id='image' src='/samples/42/sample_0123456789abcdef0123456789abcdef.jpg'>",
            "<img id='image' src='/images/42/sample-0123456789abcdef0123456789abcdef.jpg'>",
            "<img id='image' src='/thumbnails/42/original.jpg'>",
            "<meta property='og:image' content='/images/42/poster.jpg'>",
            "<video id='gelcomVideoPlayer' poster='/images/42/poster.jpg'></video>",
            "<video id='advertisement'><source src='/images/42/ad.mp4'></video>",
        )) assertNull(Rule34HtmlClient.originalMediaUrlFromHtml(html))
    }

    @Test fun skipsInvalidOriginalLinksAndCommentLinks() {
        val url = "https://wimg.rule34.xxx/images/42/original.jpg"
        assertEquals(url, Rule34HtmlClient.originalMediaUrlFromHtml("""
            <div id="comments"><a href="https://wimg.rule34.xxx/images/1/wrong.jpg">Original image</a></div>
            <a href="javascript:alert(1)">Original image</a>
            <a href="https://example.test/original.jpg">Original image</a>
            <a href="https://rule34.xxx.evil.test/original.jpg">Original image</a>
            <a href="$url">Original file</a>
        """))
        assertNull(Rule34HtmlClient.originalMediaUrlFromHtml("<a href='https://example.test/original.jpg'>Original image</a>"))
    }
}
