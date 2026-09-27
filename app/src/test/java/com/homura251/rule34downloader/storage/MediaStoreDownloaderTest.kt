package com.homura251.rule34downloader.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreDownloaderTest {
    @Test
    fun sanitizesFolderSeparators() {
        assertEquals(
            "artist_name_test",
            MediaStoreDownloader.sanitizeFolderName("artist/name:test"),
        )
    }
}
