package com.homura251.rule34downloader.domain.files

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class VerifiedTransferTest {
    private val hash = "5d41402abc4b2a76b9719d911017c592"
    @Test fun copiesKnownLengthWithoutReadingBackOrHashing() {
        val output = ByteArrayOutputStream()
        val result = VerifiedTransfer.copy(ByteArrayInputStream("hello".toByteArray()), output, hash, 5)
        assertEquals(5L, result.bytes)
        assertNull(result.md5)
        assertEquals("hello", output.toString("UTF-8"))
    }
    @Test fun unknownLengthAcceptsACompleteNonemptyStream() {
        assertEquals(5L, copy("hello", -1).bytes)
        assertEquals(4L, copy("hell", -1).bytes)
    }
    @Test fun rejectsEmptyAndWrongLength() {
        for ((body, length) in listOf("" to 0L, "hell" to 5L, "hello" to 4L)) {
            assertThrows(IOException::class.java) { copy(body, length) }
        }
    }
    @Test fun missingOrMismatchedHashesDoNotBlockDownloads() {
        for (md5 in listOf("", "invalid", "f".repeat(32))) {
            val result = VerifiedTransfer.copy(ByteArrayInputStream("hello".toByteArray()), ByteArrayOutputStream(), md5, 5)
            assertEquals(5L, result.bytes)
            assertNull(result.md5)
        }
    }
    @Test fun filenamesUsePostIdAndOptionalMetadataHash() {
        assertEquals("42.png", DownloadFileName.build(42, "", "https://example.test/original.png?token=1"))
        assertEquals("42_$hash.gif", DownloadFileName.build(42, hash, "https://example.test/original.GIF"))
        assertEquals("42.bin", DownloadFileName.build(42, "", ""))
    }
    @Test fun pauseAfterLastChunkCannotCompleteAFile() {
        var paused = false
        assertThrows(CancellationException::class.java) {
            VerifiedTransfer.copy(ByteArrayInputStream("hello".toByteArray()), ByteArrayOutputStream(), hash, 5,
                checkActive = { if (paused) throw CancellationException("paused") },
                onProgress = { paused = true })
        }
    }
    @Test fun derivesOnlyExactOriginalFilenameHashes() {
        assertEquals(hash, FileChecksum.expected("", "https://wimg.rule34.xxx/images/$hash.jpg?x=1"))
        assertEquals(hash, FileChecksum.expected(hash.uppercase(), "https://example.test/file.jpg"))
        assertNull(FileChecksum.expected("", "https://wimg.rule34.xxx/samples/sample_$hash.jpg"))
        assertNull(FileChecksum.expected("invalid", "https://wimg.rule34.xxx/images/42.jpg"))
    }
    private fun copy(body: String, length: Long) = VerifiedTransfer.copy(
        ByteArrayInputStream(body.toByteArray()), ByteArrayOutputStream(), hash, length)
}
