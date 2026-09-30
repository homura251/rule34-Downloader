package com.homura251.rule34downloader.storage

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

/** Original files must have a trustworthy checksum before they can be completed/reused. */
object FileChecksum {
    private val hash = Regex("[a-fA-F0-9]{32}")
    fun normalize(value: String): String? = value.takeIf { it.matches(hash) }?.lowercase()
    fun expected(md5: String, fileUrl: String): String? = normalize(md5) ?: runCatching {
        val name = URI(fileUrl).path.substringAfterLast('/')
        normalize(name.substringBeforeLast('.', name))
    }.getOrNull()
}

object VerifiedTransfer {
    data class Result(val bytes: Long, val md5: String)

    fun copy(
        input: InputStream,
        output: OutputStream,
        expectedMd5: String,
        expectedLength: Long = -1,
        checkActive: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): Result {
        val expected = FileChecksum.normalize(expectedMd5)
            ?: throw IOException("原文件缺少可靠的 MD5，无法确认完整性。")
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            bytes += count
            if (expectedLength >= 0 && bytes > expectedLength) throw IOException("原文件长度超过响应声明值。")
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            onProgress(bytes)
        }
        checkActive()
        if (bytes == 0L) throw IOException("原文件为空，未保存。")
        if (expectedLength >= 0 && bytes != expectedLength) throw IOException("原文件下载不完整（$bytes/$expectedLength 字节）。")
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual != expected) throw IOException("原文件 MD5 校验失败，未保存。")
        output.flush()
        return Result(bytes, actual)
    }
}
