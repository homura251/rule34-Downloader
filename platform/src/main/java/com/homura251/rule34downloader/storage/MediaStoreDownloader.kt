package com.homura251.rule34downloader.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.ContentResolver
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.homura251.rule34downloader.domain.DownloadRecord
import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.domain.files.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import com.homura251.rule34downloader.network.HttpMediaTransport
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI

class MediaStoreDownloader(
    private val context: Context,
    private val transport: MediaTransport,
    private val checkActive: () -> Unit = {},
) : MediaDownloader {
    data class Result(val uri: Uri, val bytesWritten: Long, val existed: Boolean, val verifiedMd5: String)

    override suspend fun download(record: DownloadRecord, onHeaders: (Long) -> Unit, onProgress: (Long, Long) -> Unit,
        onPublished: suspend (SavedFile) -> Unit): SavedFile = withContext(Dispatchers.IO) {
        transport.open(MediaRequest(record.fileUrl, "https://rule34.xxx/index.php?page=post&s=view&id=${record.postId}")).use { source -> downloadBlocking(record, source, onHeaders, onProgress, onPublished) }
            .let { SavedFile(it.uri.toString(), it.bytesWritten, it.verifiedMd5) }
    }

    private suspend fun downloadBlocking(record: DownloadRecord, source: MediaStream, onHeaders: (Long) -> Unit, onProgress: (Long, Long) -> Unit,
        onPublished: suspend (SavedFile) -> Unit): Result {
        checkActive()
        val expectedMd5 = FileChecksum.expectedForReuse(record.md5, record.verifiedMd5, record.fileUrl)
            ?: throw FileIntegrityException("帖子 #${record.postId} 的原文件缺少可靠 MD5，无法确认完整性。")
        val extension = extensionFromUrl(record.fileUrl)
        var insertedUri: Uri? = null
        var published = false
        try {
            return source.use { source ->
                checkActive()
                val totalBytes = source.contentLength
                onHeaders(totalBytes.coerceAtLeast(0))
                val mimeType = source.contentType?.substringBefore(';')?.takeIf { it.contains('/') }
                    ?: mimeTypeForExtension(extension)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, buildFileName(record.postId, expectedMd5, extension))
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, buildRelativePath(record.artistTag))
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val targetUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("无法在系统 Download 集合中创建文件。")
                insertedUri = targetUri
                val transfer = context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                    VerifiedTransfer.copy(source.input, output, expectedMd5, totalBytes, ::checkDownloadActive) {
                        onProgress(it, totalBytes.coerceAtLeast(0))
                    }
                } ?: throw IOException("无法打开目标文件。")
                // Check the bytes actually stored, not just those read from HTTP.
                val savedBytes = context.contentResolver.openInputStream(targetUri)?.use {
                    SavedFileIdentity.verify(it, expectedMd5, ::checkDownloadActive)
                }
                if (savedBytes != transfer.bytes) throw FileIntegrityException("写入后的文件校验失败，未保存。")
                checkDownloadActive()
                withContext(NonCancellable) {
                    val changed = context.contentResolver.update(targetUri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    if (changed != 1) throw IOException("无法完成原文件保存。")
                    published = true
                    onPublished(SavedFile(targetUri.toString(), transfer.bytes, transfer.md5))
                }
                Result(targetUri, transfer.bytes, false, transfer.md5)
            }
        } catch (e: Exception) {
            // This URI was created by this attempt; reused old files are never deleted.
            if (!published) insertedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            if (e is SecurityException || (e is IOException && e !is FileIntegrityException && e !is InterruptedIOException))
                throw SourceFailure(FailureKind.FILE, "文件读写失败，请检查存储空间与系统授权。")
            throw e
        }
    }

    private fun checkDownloadActive() {
        checkActive()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已取消")
    }

    /** Called only while holding the artist's exclusive worker gate. */
    override suspend fun cleanInterruptedFiles(collectionId: String) = withContext(Dispatchers.IO) { cleanInterruptedBlocking(collectionId) }

    private fun cleanInterruptedBlocking(artistTag: String) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        queryIncludingPending(context.contentResolver,
            "relative_path = ? AND is_pending = 1 AND owner_package_name = ?",
            arrayOf(buildRelativePath(artistTag), context.packageName))?.use { cursor ->
            while (cursor.moveToNext()) {
                checkDownloadActive()
                try {
                    context.contentResolver.delete(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null)
                } catch (_: SecurityException) {
                    // A previous installation can share the package name but no
                    // longer own this row. Its files require the user's read grant.
                }
            }
        }
    }

    private fun extensionFromUrl(url: String): String {
        val candidate = runCatching {
            URI(url).path.substringAfterLast('.', "").lowercase()
        }.getOrDefault("")
        return candidate.takeIf { it.matches(Regex("^[a-z0-9]{2,5}$")) } ?: "bin"
    }

    private fun buildFileName(postId: Long, md5: String, extension: String): String {
        val safeMd5 = md5.lowercase().filter { it in 'a'..'f' || it in '0'..'9' }.take(32)
        val stem = if (safeMd5.isNotEmpty()) "${postId}_$safeMd5" else postId.toString()
        return "$stem.$extension"
    }

    private fun mimeTypeForExtension(extension: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "webm" -> "video/webm"
                "avif" -> "image/avif"
                else -> "application/octet-stream"
            }

    companion object {
        @Suppress("DEPRECATION")
        internal fun queryIncludingPending(resolver: ContentResolver, selection: String, args: Array<String>): android.database.Cursor? {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            return if (Build.VERSION.SDK_INT >= 30) resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }, null)
            else resolver.query(MediaStore.setIncludePending(collection), arrayOf(MediaStore.Downloads._ID), selection, args, null)
        }

        internal fun requireMediaResponse(response: Response) = HttpMediaTransport.requireResponse(response)

        fun buildRelativePath(artistTag: String): String = DirectoryLayout.relativePath(artistTag)
        fun sanitizeFolderName(value: String): String = DirectoryLayout.folderName(value)
    }
}

