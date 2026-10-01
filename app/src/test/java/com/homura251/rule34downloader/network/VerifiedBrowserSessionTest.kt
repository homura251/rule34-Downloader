package com.homura251.rule34downloader.network

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class VerifiedBrowserSessionTest {
    private val post = "https://rule34.xxx/index.php?page=post&s=view&id=42"
    private val html = "<a href='/images/42/original.jpg'>Original image</a>"

    @Test fun browserIdentityUsesInstalledEngineVersionAndRemovesWebViewBrand() {
        val raw = "Mozilla/5.0 (Linux; Android 15; device Build/123; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/165.0.1234.56 Mobile Safari/537.36"
        val browser = BrowserIdentity.userAgent(raw)
        assertTrue(browser.contains("Chrome/165.0.1234.56"))
        assertTrue(browser.contains("Android 10; K)"))
        assertFalse(browser.contains("wv"))
        assertFalse(browser.contains("Version/4.0"))
    }

    @Test fun verificationCanSupplyTheNextReadOnceWithoutReopeningThePage() {
        val cache = VerifiedPageCache()
        cache.put(Jsoup.parse(html, post))
        assertNull(cache.take(post.replace("id=42", "id=43")))
        assertNotNull(Rule34MediaParser.originalUrl(cache.take(post)!!))
        assertNull(cache.take(post))
    }

    @Test fun verifiedDocumentsCannotLeakAcrossOriginsOrOffsets() {
        val cache = VerifiedPageCache()
        val first = "https://rule34.xxx/index.php?page=post&s=list&tags=test&pid=0"
        cache.put(Jsoup.parse("<div class='image-list'></div>", first))
        assertNull(cache.take(first.replace("rule34.xxx", "example.test")))
        assertNull(cache.take(first.replace("pid=0", "pid=42")))
        assertNotNull(cache.take(first.removeSuffix("&pid=0")))
    }

    @Test fun expiredVerificationCannotReturnStaleMetadata() {
        var now = 0L
        val cache = VerifiedPageCache { now }
        cache.put(Jsoup.parse(html, post))
        now = 120_001L
        assertNull(cache.take(post))
    }

    @Test fun diagnosticsRemoveCredentialsAndChallengeTokens() {
        val safe = BrowserReadDiagnostics.safeUrl("https://name:password@rule34.xxx/index.php?page=post&s=view&id=42&api_key=secret&user_id=123&__cf_chl_tk=token#credential")
        assertEquals(post, safe)
    }
}
