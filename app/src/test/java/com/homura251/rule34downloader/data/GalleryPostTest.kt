package com.homura251.rule34downloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPostTest {
    private fun image() = GalleryPost(
        "artist", 42L, "https://wimg.rule34.xxx/original.jpg",
        "https://rule34.xxx/thumbnail.jpg", "content://media/external/downloads/42",
        DownloadStatus.DOWNLOADED,
    )

    @Test fun downloadedPicturesPreferLocalFilesForBothViews() {
        assertEquals(image().localUri, image().imageSource(fullSize = false))
        assertEquals(image().localUri, image().imageSource(fullSize = true))
    }

    @Test fun missingLocalFileFallsBackToThumbnailOrOriginal() {
        assertEquals(image().previewUrl, image().imageSource(fullSize = false, skipLocal = true))
        assertEquals(image().fileUrl, image().imageSource(fullSize = true, skipLocal = true))
    }

    @Test fun olderRecordsWithoutThumbnailsCanStillBePreviewed() {
        val post = image().copy(localUri = null, previewUrl = null)
        assertEquals(post.fileUrl, post.imageSource(fullSize = false))
        assertEquals(post.fileUrl, post.imageSource(fullSize = true))
    }

    @Test fun videosUseTheirPosterAndNeverSendVideoBytesToAnImageDecoder() {
        val video = image().copy(fileUrl = "https://wimg.rule34.xxx/original.WEBM?token=abc.jpg")
        assertTrue(video.isVideo)
        assertEquals(video.previewUrl, video.imageSource(fullSize = false))
        assertNull(video.imageSource(fullSize = true))
        assertNull(video.copy(previewUrl = null).imageSource(fullSize = false))
    }

    @Test fun gifIsAnImageAndPostLinksUseTheCorrectPostId() {
        val gif = image().copy(fileUrl = "https://wimg.rule34.xxx/original.gif")
        assertFalse(gif.isVideo)
        assertEquals("https://rule34.xxx/index.php?page=post&s=view&id=42", gif.postUrl)
    }
}
