package com.homura251.rule34downloader.storage

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.homura251.rule34downloader.domain.FolderRestorePlan
import com.homura251.rule34downloader.domain.DownloadRecord
import com.homura251.rule34downloader.domain.DownloadStatus
import com.homura251.rule34downloader.domain.SourceFailure
import com.homura251.rule34downloader.domain.FailureKind
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LinkedDownloadDirectoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var provider: FolderDocumentsProvider
    private val hash = "5d41402abc4b2a76b9719d911017c592"
    @Before fun setup() {
        provider = Robolectric.buildContentProvider(FolderDocumentsProvider::class.java).create(ProviderInfo().apply {
            authority = "folder.tests"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }).get()
        ShadowContentResolver.registerProviderInternal("folder.tests", provider)
    }
    private fun reader() = LinkedDownloadDirectory(context.contentResolver,
        DocumentsContract.buildTreeDocumentUri("folder.tests", "root"), {})
    private fun author(parent: String, name: String = "artist_one", filename: String = "42_$hash.png"): String {
        val id = provider.directory(parent, name)
        provider.file(id, filename)
        return id
    }

    @Test fun selectingAnAncestorOfDownloadsFindsAuthorSubdirectories() {
        provider.root("Shared storage")
        val downloads = provider.directory("root", "Download")
        val app = provider.directory(downloads, "Rule34 Downloader")
        author(app)
        assertEquals(setOf("artist_one"), reader().scan().directories.filter { it.fileCount > 0 }.map { it.name }.toSet())
    }
    @Test fun nestedImageAndVideoFoldersBelongToTheirAuthor() {
        provider.root("Rule34 Downloader")
        val artist = provider.directory("root", "artist_one")
        author(artist, "images")
        val scan = reader().scan()
        assertEquals(1, scan.directories.single().fileCount)
        assertEquals("artist_one", scan.directories.single().name)
    }
    @Test fun selectingAnAuthorWithMediaSubdirectoriesKeepsTheAuthorName() {
        provider.root("artist_one")
        author("root", "images")
        assertEquals(listOf("artist_one"), reader().scan().directories.filter { it.fileCount > 0 }.map { it.name })
    }
    @Test fun hashNamedOriginalsCanRestoreAuthorSubscriptionsWithoutPostIds() {
        provider.root("Rule34 Downloader")
        author("root", filename = "$hash.png")
        val scan = reader().scan()
        val plan = FolderRestorePlan.create(scan.directories, emptyList(), scan.wholeLibrary)
        assertEquals(setOf("artist_one"), plan.targets)
    }
    @Test fun aProviderStillLoadingIsNotTreatedAsAnEmptyFolder() {
        provider.root("Rule34 Downloader")
        provider.loading = true
        assertThrows(IOException::class.java) { reader().scan() }
    }
    @Test fun customLibraryRootsAndOpaqueDocumentIdsAreSupported() {
        provider.root("My archive")
        author("root")
        assertEquals(listOf("artist_one"), reader().scan().directories.map { it.name })
    }
    @Test fun hashAndPostIdFilenamesAreReusedWithoutContentHashing() {
        for (filename in listOf("$hash.png", "42.png", "42_$hash.png")) {
            provider.root("My archive")
            val artist = provider.directory("root", "artist_one")
            val images = provider.directory(artist, "images")
            provider.file(images, filename)
            val tree = DocumentsContract.buildTreeDocumentUri("folder.tests", "root")
            val record = DownloadRecord("artist_one", 42, "https://example.test/$hash.png", hash, DownloadStatus.PENDING, 0, 0)
            val result = ExistingDownloads(context, "artist_one", {}, { tree.toString() }).lookup(record) {}
            assertNotNull(filename, result.match)
            assertNull(result.match!!.verifiedMd5)
            assertEquals(5L, result.match!!.bytes)
            assertEquals("folder.tests", result.match!!.uri.authority)
        }
    }
    @Test fun sameLengthDamageRequiresContentVerification() {
        provider.root("artist_one")
        val id = provider.file("root", "42_$hash.png")
        val tree = DocumentsContract.buildTreeDocumentUri("folder.tests", "root")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val record = DownloadRecord("artist_one", 42, "https://example.test/$hash.png", hash,
            DownloadStatus.PENDING, 5, 5, uri.toString(), hash)
        assertEquals(hash, ExistingDownloads(context, "artist_one", {}, { tree.toString() }).lookup(record) {}.match?.verifiedMd5)
        provider.replaceContent(id, "HELLO".toByteArray())
        val checked = ExistingDownloads(context, "artist_one", {}, { tree.toString() }).lookup(record) {}
        assertNull(checked.match)
        assertTrue(checked.rejection.orEmpty().contains("MD5"))
        val withoutProof = record.copy(verifiedMd5 = null)
        assertNull(ExistingDownloads(context, "artist_one", {}, { tree.toString() })
            .lookup(withoutProof) {}.match?.verifiedMd5)
        assertNull(ExistingDownloads(context, "artist_one", {}, { tree.toString() })
            .lookup(withoutProof, verifyContent = true) {}.match)
    }
    @Test fun unknownRenamedFilesAreLeftUnclaimed() {
        provider.root("artist_one")
        provider.file("root", "renamed.png")
        val tree = DocumentsContract.buildTreeDocumentUri("folder.tests", "root")
        val record = DownloadRecord("artist_one", 42, "https://example.test/$hash.png", hash, DownloadStatus.PENDING, 0, 0)
        assertNull(ExistingDownloads(context, "artist_one", {}, { tree.toString() }).lookup(record) {}.match)
    }
    @Test fun deepDirectoriesHaveNoArbitraryDepthLimit() {
        provider.root("Rule34 Downloader")
        var folder = provider.directory("root", "artist_one")
        repeat(80) { folder = provider.directory(folder, "images") }
        provider.file(folder, "42.png")
        assertEquals("artist_one", reader().scan().directories.single { it.fileCount > 0 }.name)
        assertEquals(1, reader().filesForAuthor("artist_one").size)
    }
    @Test fun aRecordedValidFileRemainsReusableWhileTheLinkedFolderIsLoading() {
        provider.root("Rule34 Downloader")
        val id = provider.file("root", "42_$hash.png")
        val tree = DocumentsContract.buildTreeDocumentUri("folder.tests", "root")
        val local = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        provider.loading = true
        val record = DownloadRecord("artist_one", 42, "https://example.test/$hash.png", hash,
            DownloadStatus.DOWNLOADED, 5, 5, local.toString(), hash)
        assertEquals(local, ExistingDownloads(context, "artist_one", {}, { tree.toString() }).lookup(record) {}.match?.uri)
    }
    @Test fun anUnreadableSiblingDoesNotHideHealthyAuthors() {
        provider.root("Rule34 Downloader")
        author("root", "healthy")
        provider.denied += provider.directory("root", "unreadable")
        val scan = reader().scan()
        assertEquals(listOf("unreadable"), scan.unreadableDirectories)
        assertEquals(setOf("healthy"), FolderRestorePlan.create(scan.directories, emptyList(), scan.wholeLibrary).targets)
    }
    @Test fun anUnreadableSelectedAuthorReportsAFileErrorInsteadOfAnEmptyFolder() {
        provider.root("archive")
        provider.denied += provider.directory("root", "artist_one")
        val tree = DocumentsContract.buildTreeDocumentUri("folder.tests", "root")
        val record = DownloadRecord("artist_one", 42, "https://example.test/$hash.png", hash, DownloadStatus.PENDING, 0, 0)
        val failure = assertThrows(SourceFailure::class.java) {
            ExistingDownloads(context, "artist_one", {}, { tree.toString() }).lookup(record) {}
        }
        assertEquals(FailureKind.FILE, failure.kind)
        assertTrue(failure.message.contains("授权"))
    }
    @Test fun rootNameVariantsAndDeeperBackupsKeepNestedFilesWithTheirAuthor() {
        for (name in listOf("Rule34 Downloader", "rule34_downloader", "Rule34-Downloader", "R34 Downloader")) {
            provider.root("backup")
            val nested = provider.directory("root", name)
            val artist = provider.directory(nested, "artist_one")
            val year = provider.directory(artist, "2025")
            val images = provider.directory(year, "images")
            provider.file(images, "42_$hash.png")
            val scan = reader().scan()
            assertEquals(listOf("artist_one"), scan.directories.filter { it.fileCount > 0 }.map { it.name })
            assertEquals(1, reader().filesForAuthor("artist_one").size)
        }
    }
}

/** DocumentsContract wire format with opaque IDs, exercised through ContentResolver. */
class FolderDocumentsProvider : ContentProvider() {
    data class Node(val id: String, val parent: String?, val name: String, val directory: Boolean,
        val content: ByteArray = "hello".toByteArray())
    private val nodes = linkedMapOf<String, Node>()
    fun replaceContent(id: String, value: ByteArray) { nodes[id] = nodes.getValue(id).copy(content = value) }
    val denied = hashSetOf<String>()
    var loading = false
    fun root(name: String) { nodes.clear(); denied.clear(); loading = false; nodes["root"] = Node("root", null, name, true) }
    fun directory(parent: String, name: String) = add(parent, name, true)
    fun file(parent: String, name: String) = add(parent, name, false)
    private fun add(parent: String, name: String, directory: Boolean): String {
        val id = "opaque:${nodes.size + 1}"
        nodes[id] = Node(id, parent, name, directory)
        return id
    }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        if (uri.lastPathSegment == "children") queryChildDocuments(DocumentsContract.getDocumentId(uri), projection)
        else rows(listOf(nodes.getValue(DocumentsContract.getDocumentId(uri))), projection)
    private fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?): Cursor {
        if (parentDocumentId in denied) throw SecurityException("Fixture permission denied")
        return rows(if (loading) emptyList() else nodes.values.filter { it.parent == parentDocumentId }, projection).apply {
            extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, loading) }
        }
    }
    private fun rows(items: List<Node>, projection: Array<out String>?): MatrixCursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return MatrixCursor(columns).apply {
            items.forEach { node -> addRow(columns.map<String, Any?> { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (node.directory) DocumentsContract.Document.MIME_TYPE_DIR else "image/png"
                DocumentsContract.Document.COLUMN_SIZE -> node.content.size
                else -> null
            } }.toTypedArray()) }
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val documentId = DocumentsContract.getDocumentId(uri)
        val file = File(context!!.cacheDir, "folder-tests-${documentId.replace(':', '-')}")
        file.writeBytes(nodes.getValue(documentId).content)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
}
