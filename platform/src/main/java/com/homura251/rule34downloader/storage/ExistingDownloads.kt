package com.homura251.rule34downloader.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.homura251.rule34downloader.domain.files.SavedFileIdentity
import com.homura251.rule34downloader.domain.files.FileChecksum
import com.homura251.rule34downloader.domain.DownloadRecord
import java.io.IOException

class ExistingDownloads(
    private val context: Context,
    private val artistTag: String,
    private val checkActive: () -> Unit,
    private val currentFolder: () -> String? = { null },
) {
    data class Match(val uri: Uri, val bytes: Long, val verifiedMd5: String)
    data class Lookup(val match: Match?, val rejection: String? = null)
    private data class File(val uri: Uri, val identity: SavedFileIdentity)

    private val resolver = context.contentResolver
    private var files: Map<Long, List<File>> = emptyMap()
    private var indexedTree: String? = null

    init { refresh() }

    private fun refresh() {
        val selectedTree = currentFolder()
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
                val directory = LinkedDownloadDirectory(resolver, tree, checkActive)
                val folderId = directory.authorFolder(MediaStoreDownloader.sanitizeFolderName(artistTag))
                if (folderId != null) {
                    for (entry in directory.children(folderId)) {
                        checkActive()
                        if (entry.directory) continue
                        val identity = SavedFileIdentity.parse(entry.name) ?: continue
                        found += File(directory.uri(entry), identity)
                    }
                }
            } catch (e: SecurityException) {
                throw com.homura251.rule34downloader.domain.SourceFailure(com.homura251.rule34downloader.domain.FailureKind.FILE, "旧下载目录授权已失效，请在设置中重新选择目录。")
            } catch (e: IOException) {
                throw com.homura251.rule34downloader.domain.SourceFailure(com.homura251.rule34downloader.domain.FailureKind.FILE, "无法读取旧下载目录，请在设置中重新选择目录。")
            }
        }

        files = found.groupBy { it.identity.postId }
        indexedTree = selectedTree
    }

    fun find(record: DownloadRecord, checkActive: () -> Unit): Match? =
        lookup(record, checkActive).match

    fun lookup(record: DownloadRecord, checkActive: () -> Unit): Lookup {
        if (indexedTree != currentFolder()) refresh()
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
