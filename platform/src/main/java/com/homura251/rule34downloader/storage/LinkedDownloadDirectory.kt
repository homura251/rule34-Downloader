package com.homura251.rule34downloader.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.homura251.rule34downloader.domain.SavedDownloadDirectory
import com.homura251.rule34downloader.domain.files.SavedFileIdentity
import java.io.IOException

/** One reader for recovery discovery and later file reuse: author, app root, or its parent. */
internal class LinkedDownloadDirectory(private val resolver: ContentResolver, private val tree: Uri,
    private val checkActive: () -> Unit) {
    data class Entry(val id: String, val name: String, val directory: Boolean)
    data class Scan(val directories: List<SavedDownloadDirectory>, val wholeLibrary: Boolean)
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private fun name(id: String) = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id),
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: throw IOException("无法读取文件夹")

    fun children(id: String): List<Entry> {
        val result = mutableListOf<Entry>()
        (resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?: throw IOException("无法读取文件夹")).use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                result += Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return result
    }
    private fun base(): Pair<String, Boolean> {
        if (name(rootId) == APP_DIRECTORY) return rootId to true
        val nested = children(rootId).firstOrNull { it.directory && it.name == APP_DIRECTORY }
        return if (nested == null) rootId to false else nested.id to true
    }
    fun authorFolder(folderName: String): String? {
        if (name(rootId) == folderName) return rootId
        val (base, _) = base()
        return children(base).firstOrNull { it.directory && it.name == folderName }?.id
    }
    fun scan(): Scan {
        val (base, library) = base()
        val entries = children(base)
        fun describe(title: String, items: List<Entry>): SavedDownloadDirectory {
            val ids = items.filterNot { it.directory }.mapNotNull { entry ->
                SavedFileIdentity.parse(entry.name)?.takeIf { it.md5 != null || it.extension in MEDIA_EXTENSIONS }?.postId
            }
            return SavedDownloadDirectory(title, ids.minOrNull(), ids.size)
        }
        val direct = describe(name(base), entries)
        val directories = if (!library && (direct.fileCount > 0 || entries.none { it.directory })) listOf(direct) else
            entries.filter { it.directory }.map { describe(it.name, children(it.id)) }
        return Scan(directories, library)
    }
    fun uri(entry: Entry): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
    companion object {
        private const val APP_DIRECTORY = "Rule34 Downloader"
        private val MEDIA_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "mp4", "webm", "m4v", "mov", "swf")
    }
}
