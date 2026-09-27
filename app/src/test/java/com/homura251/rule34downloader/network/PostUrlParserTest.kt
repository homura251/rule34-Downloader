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
}
