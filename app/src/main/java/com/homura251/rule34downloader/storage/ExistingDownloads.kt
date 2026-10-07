package com.homura251.rule34downloader.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.homura251.rule34downloader.data.AppPreferences
import com.homura251.rule34downloader.data.DownloadRecord
import java.io.IOException

class ExistingDownloads(
    private val context: Context,
    private val artistTag: String,
    private val checkActive: () -> Unit,
) {
    data class Match(val uri: Uri, val bytes: Long, val verifiedMd5: String)
    data class Lookup(val match: Match?, val rejection: String? = null)
    private data class File(val uri: Uri, val identity: SavedFileIdentity)

    private val resolver = context.contentResolver
    private var files: Map<Long, List<File>> = emptyMap()
    private var indexedTree: String? = null

    init { refresh() }

    private fun refresh() {
        val selectedTree = AppPreferences(context).existingDownloadsTreeUri
        val found = mutableListOf<File>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "relative_path = ? AND is_pending = 0 AND _size > 0",
                arrayOf(MediaStoreDownloader.buildRelativePath(artistTag)),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    val identity = SavedFileIdentity.parse(cursor.getString(1)) ?: continue
                    found += File(ContentUris.withAppendedId(collection, cursor.getLong(0)), identity)
                }
            }
        } catch (_: SecurityException) {
            // Reinstalled apps use the authorized document tree below.
        }

        selectedTree?.let { value ->
            try {
                val tree = Uri.parse(value)
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
                val rootName = resolver.query(
                    root,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
                val folderName = MediaStoreDownloader.sanitizeFolderName(artistTag)
                val folderId = if (rootName == folderName) {
                    rootId
                } else {
                    var match: String? = null
                    children(tree, rootId).use { cursor ->
                        while (cursor.moveToNext()) {
                            checkActive()
                            if (
                                cursor.getString(1) == folderName &&
                                cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                            ) {
                                match = cursor.getString(0)
                                break
                            }
                        }
                    }
                    match
                }
                if (folderId != null) {
                    children(tree, folderId).use { cursor ->
                        while (cursor.moveToNext()) {
                            checkActive()
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                            val identity = SavedFileIdentity.parse(cursor.getString(1)) ?: continue
                            found += File(
                                DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                                identity,
                            )
                        }
                    }
                }
            } catch (e: SecurityException) {
                throw IOException("旧下载目录授权已失效，请在设置中重新选择目录。", e)
            } catch (e: IOException) {
                throw IOException("无法读取旧下载目录，请在设置中重新选择目录。", e)
            }
        }

        files = found.groupBy { it.identity.postId }
        indexedTree = selectedTree
    }

    private fun children(tree: Uri, id: String) = resolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null,
        null,
        null,
    ) ?: throw IOException("无法读取文件夹")

    fun find(record: DownloadRecord, checkActive: () -> Unit): Match? =
        lookup(record, checkActive).match

    fun lookup(record: DownloadRecord, checkActive: () -> Unit): Lookup {
        if (indexedTree != AppPreferences(context).existingDownloadsTreeUri) refresh()
        val expected = FileChecksum.expectedForReuse(record.md5, record.verifiedMd5, record.fileUrl)
            ?: return Lookup(null, "帖子 #${record.postId} 缺少可靠 MD5，无法校验旧文件")

        var candidateFound = false
        fun verify(uri: Uri): Match? {
            candidateFound = true
            val bytes = try {
                checkActive()
                resolver.openInputStream(uri)?.use {
                    SavedFileIdentity.verify(it, expected, checkActive)
                }
            } catch (_: SecurityException) {
                null
            } catch (_: IOException) {
                null
            }
            checkActive()
            return bytes?.let { Match(uri, it, expected) }
        }

        record.localUri?.let { value ->
            verify(Uri.parse(value))?.let { return Lookup(it) }
        }

        for (file in files[record.postId].orEmpty()) {
            checkActive()
            if (!file.identity.matches(record.postId, expected)) continue
            verify(file.uri)?.let { return Lookup(it) }
        }

        return Lookup(
            null,
            if (candidateFound) {
                "找到帖子 #${record.postId} 的旧文件，但内容 MD5 校验未通过或文件已无法读取"
            } else {
                "未在已授权目录中找到帖子 #${record.postId} 的可复用旧文件"
            },
        )
    }
}
