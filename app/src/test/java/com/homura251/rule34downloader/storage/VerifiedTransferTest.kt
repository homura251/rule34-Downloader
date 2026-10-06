package com.homura251.rule34downloader.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class VerifiedTransferTest {
    private val hash = "5d41402abc4b2a76b9719d911017c592"
    @Test fun verifiesKnownLengthAndChecksum() {
        val output = ByteArrayOutputStream()
        val result = VerifiedTransfer.copy(ByteArrayInputStream("hello".toByteArray()), output, hash, 5)
        assertEquals(5L, result.bytes)
        assertEquals(hash, result.md5)
        assertEquals("hello", output.toString("UTF-8"))
    }
    @Test fun unknownLengthStillRequiresFullChecksum() {
        assertEquals(5L, copy("hello", -1).bytes)
        assertThrows(IOException::class.java) { copy("hell", -1) }
    }
    @Test fun rejectsEmptyWrongLengthAndCorruptedData() {
        for ((body, length) in listOf("" to 0L, "hell" to 5L, "hello" to 4L, "wrong" to 5L)) {
            assertThrows(IOException::class.java) { copy(body, length) }
        }
    }
    @Test fun refusesUnknownChecksumsForBothNewAndOldFiles() {
        assertThrows(IOException::class.java) {
            VerifiedTransfer.copy(ByteArrayInputStream("partial".toByteArray()), ByteArrayOutputStream(), "", 7)
        }
        assertNull(SavedFileIdentity.verify(ByteArrayInputStream("partial".toByteArray()), ""))
        assertFalse(SavedFileIdentity.parse("42.jpg")!!.matches(42, ""))
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
