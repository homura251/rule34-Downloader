package com.homura251.rule34downloader.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException

class SavedFileIdentityTest {
    private val hash = "5d41402abc4b2a76b9719d911017c592"
    @Test fun parsesLegacyNamesAndDuplicateSuffixes() {
        assertEquals(SavedFileIdentity(42, null, "jpg"), SavedFileIdentity.parse("42.jpg"))
        assertEquals(SavedFileIdentity(42, hash, "webm"), SavedFileIdentity.parse("42_${hash} (1).WEBM"))
    }
    @Test fun rejectsUnrelatedFilesAndMismatchedPosts() {
        for (name in listOf("thumbnail.jpg", "42_thumb.jpg", "../42.jpg", "0.jpg", "999999999999999999999.jpg")) assertNull(SavedFileIdentity.parse(name))
        val file = SavedFileIdentity.parse("42_${hash}.jpg")!!
        assertFalse(file.matches(43, hash, "jpg"))
        assertFalse(file.matches(42, "00000000000000000000000000000000", "jpg"))
        assertFalse(file.matches(42, hash, "png"))
    }
    @Test fun olderFilesWithoutHashInNameCanBeCheckedByContent() {
        assertTrue(SavedFileIdentity.parse("42.jpg")!!.matches(42, hash, "jpg"))
        assertEquals(5L, SavedFileIdentity.verify(ByteArrayInputStream("hello".toByteArray()), hash))
    }
    @Test fun rejectsEmptyTruncatedAndCorruptedFiles() {
        for (text in listOf("", "hell", "wrong")) assertNull(SavedFileIdentity.verify(ByteArrayInputStream(text.toByteArray()), hash))
        assertNull(SavedFileIdentity.verify(ByteArrayInputStream(byteArrayOf()), ""))
    }
    @Test fun queryParametersDoNotChangeTheExtension() {
        assertEquals("jpg", SavedFileIdentity.extension("https://example.test/42.JPG?format=webm"))
    }
    @Test fun localVerificationStopsWhenPaused() {
        assertThrows(CancellationException::class.java) {
            SavedFileIdentity.verify(ByteArrayInputStream(ByteArray(100_000)), hash) { throw CancellationException("paused") }
        }
    }
}
