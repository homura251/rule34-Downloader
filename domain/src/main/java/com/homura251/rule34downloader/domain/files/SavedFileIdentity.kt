package com.homura251.rule34downloader.domain.files

import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

data class SavedFileIdentity(val postId: Long?, val md5: String?, val extension: String) {
    /**
     * The filename is only an index hint. Content MD5 is the final authority, so
     * renamed files and historical extension changes must still be eligible.
     */
    fun matches(postId: Long, expectedMd5: String): Boolean =
        FileChecksum.normalize(expectedMd5)?.let { expected ->
            if (this.postId != null) this.postId == postId else md5 == expected
        } == true

    companion object {
        private val POST_ID_PREFIX = Regex("^(\\d+)(?:[_ .(-].*)?$")
        private val HASH = Regex("[a-fA-F0-9]{32}")
        private val HASH_ONLY = Regex("^([a-fA-F0-9]{32})(?:[ _-]?\\([0-9]+\\))?$")

        fun parse(name: String): SavedFileIdentity? {
            val value = name.trim()
            if (value.isEmpty() || value.any { it == '/' || it == '\\' || it.code == 0 }) return null
            val dot = value.lastIndexOf('.')
            val stem = if (dot > 0) value.substring(0, dot) else value
            val extension = if (dot in 1 until value.lastIndex) value.substring(dot + 1).lowercase() else ""
            HASH_ONLY.matchEntire(stem)?.let { return SavedFileIdentity(null, it.groupValues[1].lowercase(), extension) }
            val match = POST_ID_PREFIX.matchEntire(stem) ?: return null
            val id = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return SavedFileIdentity(id, HASH.find(stem)?.value?.lowercase(), extension)
        }

        fun extension(url: String): String = runCatching {
            URI(url).path.substringAfterLast('.', "").lowercase()
        }.getOrDefault("").takeIf { it.matches(Regex("[a-z0-9]{2,8}")) } ?: "bin"

        /** Return byte count only for non-empty files whose known checksum matches. */
        fun verify(input: InputStream, expectedMd5: String, checkActive: () -> Unit = {}): Long? {
            val expected = FileChecksum.normalize(expectedMd5) ?: return null
            return fingerprint(input, checkActive)?.takeIf { it.md5 == expected }?.bytes
        }

        data class Fingerprint(val bytes: Long, val md5: String)

        /** Hash renamed originals once; compare with trusted metadata before reusing them. */
        fun fingerprint(input: InputStream, checkActive: () -> Unit = {}): Fingerprint? {
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
            return Fingerprint(bytes, hash)
        }
    }
}
