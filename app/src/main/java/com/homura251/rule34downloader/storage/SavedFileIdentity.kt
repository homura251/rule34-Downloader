package com.homura251.rule34downloader.storage

import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

data class SavedFileIdentity(val postId: Long, val md5: String?, val extension: String) {
    /**
     * The filename is only an index hint. Content MD5 is the final authority, so
     * renamed files and historical extension changes must still be eligible.
     */
    fun matches(postId: Long, expectedMd5: String): Boolean =
        this.postId == postId && FileChecksum.normalize(expectedMd5) != null

    companion object {
        private val POST_ID_PREFIX = Regex("^(\\d+)(?:[_ .(-].*)?$")
        private val HASH = Regex("[a-fA-F0-9]{32}")

        fun parse(name: String): SavedFileIdentity? {
            val value = name.trim()
            if (value.isEmpty()) return null
            val dot = value.lastIndexOf('.')
            val stem = if (dot > 0) value.substring(0, dot) else value
            val match = POST_ID_PREFIX.matchEntire(stem) ?: return null
            val id = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val extension = if (dot in 1 until value.lastIndex) value.substring(dot + 1).lowercase() else ""
            return SavedFileIdentity(id, HASH.find(stem)?.value?.lowercase(), extension)
        }

        fun extension(url: String): String = runCatching {
            URI(url).path.substringAfterLast('.', "").lowercase()
        }.getOrDefault("").takeIf { it.matches(Regex("[a-z0-9]{2,8}")) } ?: "bin"

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
