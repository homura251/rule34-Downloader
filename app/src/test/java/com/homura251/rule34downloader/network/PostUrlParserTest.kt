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
    fun rejectsOtherHosts() {
        assertNull(PostUrlParser.parsePostId("https://example.com/?id=18875738"))
    }

    @Test
    fun rejectsMissingId() {
        assertNull(PostUrlParser.parsePostId("https://rule34.xxx/index.php?page=post&s=list"))
    }
}
