package com.homura251.rule34downloader.storage

import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

data class SavedFileIdentity(val postId: Long, val md5: String?, val extension: String) {
    fun matches(postId: Long, md5: String, extension: String): Boolean =
        this.postId == postId && this.extension == extension.lowercase() &&
            FileChecksum.normalize(md5) != null && (this.md5 == null || this.md5.equals(md5, true))

    companion object {
        private val NAME = Regex("^(\\d+)(?:_([a-fA-F0-9]{32}))?(?: \\(\\d+\\))?\\.([a-zA-Z0-9]{2,5})$")
        fun parse(name: String): SavedFileIdentity? {
            val match = NAME.matchEntire(name) ?: return null
            val id = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return SavedFileIdentity(id, match.groupValues[2].ifBlank { null }, match.groupValues[3].lowercase())
        }
        fun extension(url: String): String = runCatching {
            URI(url).path.substringAfterLast('.', "").lowercase()
        }.getOrDefault("").takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "bin"

        /** Return byte count only for non-empty files whose known checksum matches. */
        fun verify(input: InputStream, expectedMd5: String, checkActive: () -> Unit = {}): Long? {
            val expected = FileChecksum.normalize(expectedMd5) ?: return null
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                bytes += count
                digest.update(buffer, 0, count)
            }
            if (bytes == 0L) return null
            checkActive()
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            return bytes.takeIf { hash == expected }
        }
    }
}
