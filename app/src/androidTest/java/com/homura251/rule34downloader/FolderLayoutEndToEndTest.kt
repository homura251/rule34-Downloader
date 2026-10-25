package com.homura251.rule34downloader

import android.content.ContentValues
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** Real external-storage DocumentsProvider, native picker, HTTP API and read-back checksums. */
@RunWith(AndroidJUnit4::class)
class FolderLayoutEndToEndTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Rule34Application>()
    private val graph get() = app.graph
    private val origin get() = ApiTestEnvironment.origin
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun <T> read(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private data class Layout(val key: String, val selected: String, val authorSuffix: String,
        val mediaSuffix: String = "", val hashOnly: Boolean = false, val renamed: Boolean = false)

    @Test fun nativeFolderPickerRestoresAuthorsAcrossLayoutsAndOffersManualRecovery() {
        grantNotificationsForTest()
        wait { graph.startup.value.status == StartupStatus.READY }
        read {
            for (collection in graph.library.getCollections()) graph.background.remove(collection.tag)
            graph.account.save(ApiCredentials("42", "fixture-key"))
            graph.background.authenticationChanged()
        }
        val layouts = listOf(
            Layout("app", "Rule34 Downloader", ""),
            Layout("parent", "", "Rule34 Downloader/"),
            Layout("ancestor", "", "backup/Download/Rule34 Downloader/"),
            Layout("custom", "My archive", "", "images/2025/", hashOnly = true),
            Layout("variant", "rule34_downloader", "", "videos/", hashOnly = true),
            Layout("author", "artist", "", "images/")
        )
        val retained = mutableListOf<Uri>()
        val evidence = mutableListOf<String>()
        try {
            for (layout in layouts) {
                val tag = "folder_matrix_" + layout.key
                val base = "Download/Rule34 Folder Matrix/" + layout.key + "/"
                val selected = if (layout.key == "author") base + tag else base + layout.selected
                val media = if (layout.key == "author") selected + "/" + layout.mediaSuffix
                    else selected.trimEnd('/') + "/" + layout.authorSuffix + tag + "/" + layout.mediaSuffix
                val files = seed(media, hashOnly = layout.hashOnly).also { retained += it }
                val before = origin.totalOriginals()
                pick(selected.trimEnd('/'))
                val result = graph.folders.state.value.result!!
                assertEquals(layout.key, 1, result.restored)
                assertEquals(layout.key, 3, result.filesFound)
                assertEquals(layout.key, 3, result.identifiedFiles)
                assertFalse(layout.key, result.manualRecovery)
                completed(tag)
                verify(tag, before)
                evidence += layout.key + ": 3 originals reused through native SAF; no original HTTP transfers"
                read { graph.background.remove(tag) }
                files.forEach { assertEquals(1, app.contentResolver.delete(it, null, null)) }
                retained.removeAll(files.toSet())
            }

            // Readable folders with renamed originals must be linkable without guessing an artist.
            val manualPath = "Download/Rule34 Folder Matrix/manual/renamed archive"
            val manualFiles = seed("$manualPath/images/", renamed = true).also { retained += it }
            val before = origin.totalOriginals()
            pick(manualPath)
            val manual = graph.folders.state.value.result!!
            assertTrue(manual.manualRecovery)
            assertEquals(3, manual.filesFound)
            assertEquals(0, manual.identifiedFiles)
            assertNull(graph.folders.state.value.error)
            settleFeedback()
            ui.onNodeWithTag("folder-add-author").performScrollTo().assertIsEnabled().performClick()
            ui.onNodeWithTag("author-input").performTextInput("folder_matrix_manual")
            ui.onNodeWithText("添加", substring = false).performClick()
            completed("folder_matrix_manual")
            verify("folder_matrix_manual", before + 3, expectLinked = false)
            retained += read { graph.library.records("folder_matrix_manual") }.map { Uri.parse(it.localUri!!) }
            evidence += "manual: readable renamed archive accepted; UI added author; unrecognized renamed files retained; three new originals downloaded"
            read { graph.background.remove("folder_matrix_manual") }

            val empty = "Download/Rule34 Folder Matrix/empty"
            val placeholder = seed("$empty/").also { retained += it }
            placeholder.forEach { app.contentResolver.delete(it, null, null) }
            retained.removeAll(placeholder.toSet())
            pick(empty)
            assertEquals(0, graph.folders.state.value.result!!.filesFound)
            assertEquals(0, graph.folders.state.value.result!!.restored)
            assertNull(graph.folders.state.value.error)
            assertEquals(tree(empty).toString(), read { graph.settings.snapshot() }.existingDownloadsTreeUri)
            evidence += "empty: existing readable directory accepted with explicit zero-file result"
            assertEquals(0, origin.authenticationLeaks.get())
            writeTestText(app, "controlled", "folder-layout-matrix.txt", evidence.joinToString("\n"))
        } finally {
            read {
                for (collection in graph.library.getCollections().filter { it.tag.startsWith("folder_matrix_") })
                    graph.background.remove(collection.tag)
                graph.settings.save(graph.settings.snapshot().copy(existingDownloadsTreeUri = null))
            }
            retained.forEach { app.contentResolver.delete(it, null, null) }
        }
    }

    private fun seed(path: String, hashOnly: Boolean = false, renamed: Boolean = false) = (1L..3L).map { id ->
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, when {
                renamed -> "renamed-original-$id.png"
                hashOnly -> origin.hashes.getValue(id).uppercase() + " (1).PNG"
                else -> "${id}_${origin.hashes.getValue(id)}.png"
            })
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, path)
            put(MediaStore.Downloads.IS_PENDING, 1)
        })!!
        app.contentResolver.openOutputStream(uri)!!.use { it.write(origin.bytes.getValue(id)) }
        assertEquals(1, app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null))
        uri
    }
    private fun tree(path: String) = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$path")
    private fun pick(path: String) {
        val root = tree(path)
        read { graph.settings.save(graph.settings.snapshot().copy(existingDownloadsTreeUri = root.toString())) }
        wait { graph.settings.state.value.existingDownloadsTreeUri == root.toString() }
        if (ui.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithTag("nav-settings").performClick()
        settleFeedback()
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("link-downloads"))
        ui.onNodeWithTag("link-downloads").performClick()
        val use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 15_000)
            ?: error("Native directory picker not shown for $path")
        assertTrue(use.isEnabled)
        use.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)?.click()
        wait {
            app.contentResolver.persistedUriPermissions.any { it.uri == root && it.isReadPermission } &&
                !graph.folders.state.value.linking
        }
        assertNull(path, graph.folders.state.value.error)
        assertNotNull(path, graph.folders.state.value.result)
    }
    private fun completed(tag: String) = wait(90_000) {
        read { graph.library.getCollection(tag) }?.syncState == SyncState.COMPLETE &&
            WorkManager.getInstance(app).getWorkInfosForUniqueWork("rule34-sync-$tag").get(10, TimeUnit.SECONDS).all { it.state.isFinished }
    }
    private fun verify(tag: String, before: Int, expectLinked: Boolean = true) {
        val records = read { graph.library.records(tag) }
        assertEquals(3, records.size)
        assertTrue(records.all { it.status == DownloadStatus.DOWNLOADED })
        if (expectLinked) assertTrue(records.all { it.localUri!!.contains("externalstorage.documents") })
        for (record in records) {
            val bytes = app.contentResolver.openInputStream(Uri.parse(record.localUri!!))!!.use { it.readBytes() }
            assertEquals(origin.hashes.getValue(record.postId), TestApiOrigin.md5(bytes))
        }
        assertEquals("Existing originals must not be downloaded again", before, origin.totalOriginals())
    }
    private fun settleFeedback() {
        ui.waitForIdle()
        if (ui.onAllNodesWithTag("app-message").fetchSemanticsNodes().isNotEmpty()) {
            ui.mainClock.advanceTimeBy(6_000)
            ui.waitUntil(10_000) { ui.onAllNodesWithTag("app-message").fetchSemanticsNodes().isEmpty() }
        }
    }
    private fun wait(timeout: Long = 40_000, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (!predicate()) { check(System.nanoTime() < until) { "Folder matrix timed out: ${graph.folders.state.value}" }; Thread.sleep(50) }
    }
}
