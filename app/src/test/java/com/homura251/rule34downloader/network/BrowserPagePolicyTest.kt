package com.homura251.rule34downloader.network

import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BrowserPagePolicyTest {
    @Test fun canonicalFirstPageRedirectCanDropZeroOffsetButCannotChangeResults() {
        val base = "https://rule34.xxx/index.php?page=post&s=list&tags=test"
        assertTrue(BrowserPagePolicy.matchesRequest("$base&pid=0", base))
        assertFalse(BrowserPagePolicy.matchesRequest("$base&pid=42", base))
        assertFalse(BrowserPagePolicy.matchesRequest(base, "$base&pid=42"))
        assertFalse(BrowserPagePolicy.matchesRequest(base, base.replace("rule34.xxx", "example.test")))
        assertTrue(BrowserPagePolicy.matchesRequest(base, base.replace("rule34.xxx", "www.rule34.xxx")))
    }
    @Test fun passiveCloudflareScriptDoesNotHideReadablePostContent() {
        val url = "https://rule34.xxx/index.php?page=post&s=view&id=42"
        val html = "<script>window._cf_chl_opt={};</script><a href='/images/42/original.jpg'>Original image</a>"
        assertTrue(BrowserPagePolicy.isReadable(Jsoup.parse(html, url)))
        assertFalse(BrowserPagePolicy.isReadable(Jsoup.parse("<form id='challenge-form'></form>$html", url)))
    }
    @Test fun attachmentVerificationDoesNotAcceptHtml() {
        assertTrue(BrowserPagePolicy.isDownloadable("video/webm"))
        assertTrue(BrowserPagePolicy.isDownloadable("application/octet-stream"))
        assertFalse(BrowserPagePolicy.isDownloadable("text/html"))
    }
    @Test fun redirectsToAnAccountPageCannotCompletePostVerification() {
        val expected = "https://rule34.xxx/index.php?page=post&s=list&tags=test"
        assertFalse(BrowserPagePolicy.matchesRequest(expected, "https://rule34.xxx/index.php?page=account&s=login"))
        assertFalse(BrowserPagePolicy.matchesRequest(expected, "https://rule34.xxx/index.php?page=post&s=list&tags=other"))
        assertTrue(BrowserPagePolicy.matchesRequest(expected, "$expected&__cf_chl_tk=extra"))
    }
    @Test fun aNewCookieCannotMakeAChallengePageReadable() {
        assertFalse(BrowserPagePolicy.isReadable(Jsoup.parse("<title>Just a moment...</title><form id='challenge-form'></form>")))
        assertFalse(BrowserPagePolicy.isReadable(Jsoup.parse("<title>CAPTCHA</title>")))
        assertFalse(BrowserPagePolicy.isReadable(Jsoup.parse("<title>Error</title><p>Access denied</p>")))
    }
    @Test fun acceptsPostListEmptyListAndPoolDocuments() {
        for (html in listOf("<div class='image-list'></div>", "<div id='tag-sidebar'></div>", "<h4>Pool: Series</h4>", "<p>Nobody here but us chickens</p>")) {
            assertTrue(BrowserPagePolicy.isReadable(Jsoup.parse(html)))
        }
    }
    @Test fun waitsForParsedHtmlAndTheRequestedPostOriginal() {
        val url = "https://rule34.xxx/index.php?page=post&s=view&id=42"
        val sidebar = "<div id='header'></div><div id='tag-sidebar'></div>"
        assertFalse(BrowserPagePolicy.isReadable(Jsoup.parse(sidebar, url)))
        val post = Jsoup.parse(sidebar + "<a href='/images/42/original.jpg'>Original image</a>", url)
        assertFalse(BrowserPagePolicy.isReadable(post, "text/html", "loading"))
        assertTrue(BrowserPagePolicy.isReadable(post, "text/html", "interactive"))
        assertTrue(BrowserPagePolicy.isReadable(post, "text/html", "complete"))
    }
    @Test fun navigationCannotCompleteAListOrPoolRead() {
        val navigation = "<div id='header'></div><ul id='navbar'></ul>"
        assertFalse(BrowserPagePolicy.isReadable(Jsoup.parse(navigation, "https://rule34.xxx/index.php?page=post&s=list")))
        assertFalse(BrowserPagePolicy.isReadable(Jsoup.parse(navigation, "https://rule34.xxx/index.php?page=pool&s=show&id=42")))
    }
    @Test fun permitsChallengeSubframesAndRejectsExternalTopLevelNavigation() {
        assertFalse(BrowserPagePolicy.blockNavigation("https://challenges.cloudflare.com/turnstile", false, ::isRule34UrlString))
        assertTrue(BrowserPagePolicy.blockNavigation("https://example.test", true, ::isRule34UrlString))
        assertFalse(BrowserPagePolicy.blockNavigation("https://rule34.xxx/index.php", true, ::isRule34UrlString))
    }
    @Test fun anonymousMetadataUsesTheBrowserTransport() {
        var requested = ""
        var checked = false
        val client = Rule34HtmlClient(OkHttpClient(), { checked = true }) { url, active ->
            requested = url
            active()
            Jsoup.parse("<div class='link-list'><a href='https://wimg.rule34.xxx/5d41402abc4b2a76b9719d911017c592.jpg'>Original image</a></div>", url)
        }
        assertEquals(42L, client.getPostWithArtists(42).post.id)
        assertTrue(checked)
        assertTrue(requested.contains("s=view&id=42"))
    }
}
