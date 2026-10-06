package com.homura251.rule34downloader.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import java.io.IOException

class MediaStoreDownloaderTest {
    @Test
    fun rejectsHtmlSuccessResponseBeforeSavingAMediaFile() {
        for (type in listOf("text/html", "application/xhtml+xml")) {
            response(type).use {
                assertThrows(IOException::class.java) { MediaStoreDownloader.requireMediaResponse(it) }
            }
        }
    }

    @Test
    fun acceptsOriginalMediaResponse() {
        response("image/png").use { MediaStoreDownloader.requireMediaResponse(it) }
        response("video/webm").use { MediaStoreDownloader.requireMediaResponse(it) }
    }

    @Test
    fun transientMediaErrorsRequestBatchRetry() {
        for (code in listOf(429, 500, 503)) {
            val response = Response.Builder()
                .request(Request.Builder().url("https://wimg.rule34.xxx/original.png").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("retry")
                .body("temporary".toResponseBody("text/plain".toMediaType()))
                .build()
            response.use {
                assertThrows(RetryableDownloadException::class.java) {
                    MediaStoreDownloader.requireMediaResponse(it)
                }
            }
        }
    }

    private fun response(type: String): Response = Response.Builder()
        .request(Request.Builder().url("https://wimg.rule34.xxx/original.png").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body("response".toResponseBody(type.toMediaType()))
        .build()
    @Test
    fun sanitizesFolderSeparators() {
        assertEquals(
            "artist_name_test",
            MediaStoreDownloader.sanitizeFolderName("artist/name:test"),
        )
    }
}
