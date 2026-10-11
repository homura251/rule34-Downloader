package com.homura251.rule34downloader.domain

import java.io.Closeable
import java.io.InputStream

data class MediaRequest(val url: String, val referer: String)
class MediaStream(val input: InputStream, val contentType: String?, val contentLength: Long, private val onClose: () -> Unit) : Closeable {
    override fun close() = onClose()
}
interface MediaTransport { suspend fun open(request: MediaRequest): MediaStream }
