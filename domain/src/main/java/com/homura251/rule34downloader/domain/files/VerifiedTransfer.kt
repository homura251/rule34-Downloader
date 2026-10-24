package com.homura251.rule34downloader.domain.files

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

class FileIntegrityException(message: String) : IOException(message)

/** Legacy hash metadata is retained for filenames and matching old downloads. */
object FileChecksum {
    private val hash = Regex("[a-fA-F0-9]{32}")
    fun normalize(value: String): String? = value.takeIf { it.matches(hash) }?.lowercase()
    fun expected(md5: String, fileUrl: String): String? = normalize(md5) ?: runCatching {
        val name = URI(fileUrl).path.substringAfterLast('/')
        normalize(name.substringBeforeLast('.', name))
    }.getOrNull()

    fun expectedForReuse(md5: String, verifiedMd5: String?, fileUrl: String): String? =
        normalize(md5) ?: normalize(verifiedMd5.orEmpty()) ?: expected("", fileUrl)
}

/** Copies once with length and cancellation checks; the old checksum argument is ignored. */
object VerifiedTransfer {
    data class Result(val bytes: Long, val md5: String? = null)

    fun copy(
        input: InputStream,
        output: OutputStream,
        @Suppress("UNUSED_PARAMETER") expectedMd5: String = "",
        expectedLength: Long = -1,
        checkActive: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): Result {
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            bytes += count
            if (expectedLength >= 0 && bytes > expectedLength) throw FileIntegrityException("原文件长度超过响应声明值。")
            output.write(buffer, 0, count)
            onProgress(bytes)
        }
        checkActive()
        if (bytes == 0L) throw FileIntegrityException("原文件为空，未保存。")
        if (expectedLength >= 0 && bytes != expectedLength) throw FileIntegrityException("原文件下载不完整（$bytes/$expectedLength 字节）。")
        output.flush()
        return Result(bytes)
    }
}
