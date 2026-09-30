package com.homura251.rule34downloader.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostUrlParserTest {
    @Test
    fun parsesRule34PostUrl() {
        assertEquals(
            18875738L,
            PostUrlParser.parsePostId(
                "https://rule34.xxx/index.php?page=post&s=view&id=18875738",
            ),
        )
    }

    @Test
    fun acceptsRawPostId() {
        assertEquals(18875738L, PostUrlParser.parsePostId("18875738"))
    }

    @Test
    fun parsesArtistTagFromListUrl() {
        assertEquals(
            "savvyraexo",
            PostUrlParser.parseArtistTag(
                "https://rule34.xxx/index.php?page=post&s=list&tags=savvyraexo",
            ),
        )
    }

    @Test
    fun normalizesDirectArtistTag() {
        assertEquals("some_artist", PostUrlParser.parseArtistTag("artist: some artist"))
    }

    @Test
    fun rejectsMultiTagListUrl() {
        assertNull(
            PostUrlParser.parseArtistTag(
                "https://rule34.xxx/index.php?page=post&s=list&tags=foo+bar",
            ),
        )
    }

    @Test
    fun rejectsOtherHosts() {
        assertNull(PostUrlParser.parsePostId("https://example.com/?id=18875738"))
        assertNull(PostUrlParser.parseArtistTag("https://example.com/?tags=test"))
    }

    @Test
    fun rejectsMissingId() {
        assertNull(PostUrlParser.parsePostId("https://rule34.xxx/index.php?page=post&s=list"))
    }

    @Test
    fun distinguishesPoolLinksFromPostIdsRegardlessOfParameterOrder() {
        val url = "https://www.rule34.xxx/index.php?id=58638&s=show&page=pool&pid=45"
        assertEquals(58638L, PostUrlParser.parsePoolId(url))
        assertNull(PostUrlParser.parsePostId(url))
        assertEquals(58638L, PostUrlParser.parsePoolId("pool:58638"))
        assertNull(PostUrlParser.parsePoolId("58638"))
        assertNull(PostUrlParser.parsePoolId("https://rule34.xxx/index.php?page=post&s=view&id=58638"))
    }

    @Test
    fun rejectsInvalidPoolIdsAndUnrelatedPages() {
        assertNull(PostUrlParser.parsePoolId("pool:0"))
        assertNull(PostUrlParser.parsePoolId("pool:-1"))
        assertNull(PostUrlParser.parsePoolId("pool:9223372036854775808"))
        assertNull(PostUrlParser.parsePoolId("https://example.com/index.php?page=pool&s=show&id=123"))
        assertNull(PostUrlParser.parsePoolId("https://rule34.xxx/index.php?page=pool&s=edit&id=123"))
        assertNull(PostUrlParser.parsePostId("https://rule34.xxx/index.php?page=forum&s=view&id=123"))
        assertNull(PostUrlParser.parseArtistTag("pool:invalid"))
        assertNull(PostUrlParser.parseArtistTag("https://rule34.xxx/index.php?page=pool&s=show&tags=test"))
    }
}
