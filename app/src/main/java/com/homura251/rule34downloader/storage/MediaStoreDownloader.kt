package com.homura251.rule34downloader.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.homura251.rule34downloader.data.DownloadRecord
import com.homura251.rule34downloader.network.Rule34Network
import okhttp3.Request
import okhttp3.Response
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.TimeUnit

class MediaStoreDownloader(
    private val context: Context,
    client: OkHttpClient = Rule34Network.get(context).client,
    private val checkActive: () -> Unit = {},
    private val registerCancel: (() -> Unit) -> java.io.Closeable = { java.io.Closeable {} },
) {
    private val httpClient = client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    data class Result(val uri: Uri, val bytesWritten: Long, val existed: Boolean, val verifiedMd5: String)

    fun download(
        record: DownloadRecord,
        onHeaders: (totalBytes: Long) -> Unit,
        onProgress: (downloaded: Long, totalBytes: Long) -> Unit,
    ): Result {
        checkActive()
        val expectedMd5 = FileChecksum.expected(record.md5, record.fileUrl)
            ?: throw IOException("帖子 #${record.postId} 的原文件缺少可靠 MD5，无法确认完整性。")
        val extension = extensionFromUrl(record.fileUrl)
        val request = Request.Builder().url(record.fileUrl)
            .header("Referer", "${Rule34Network.SITE}/index.php?page=post&s=view&id=${record.postId}")
            .build()
        var insertedUri: Uri? = null
        try {
            return openSource(request).use { source ->
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
                if (savedBytes != transfer.bytes) throw IOException("写入后的文件校验失败，未保存。")
                checkDownloadActive()
                val published = context.contentResolver.update(targetUri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                if (published != 1) throw IOException("无法完成原文件保存。")
                Result(targetUri, transfer.bytes, false, transfer.md5)
            }
        } catch (e: Exception) {
            // This URI was created by this attempt; reused old files are never deleted.
            insertedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw e
        }
    }

    private fun checkDownloadActive() {
        checkActive()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已取消")
    }

    private fun openSource(request: Request): com.homura251.rule34downloader.network.MediaSource {
        try {
            val call = httpClient.newCall(request)
            // Keep cancellation registered while an interceptor is reading a challenge.
            val response = registerCancel(call::cancel).use { call.execute() }
            try {
                requireMediaResponse(response)
                val body = response.body ?: throw IOException("原文件下载返回了空响应。")
                return com.homura251.rule34downloader.network.MediaSource(
                    body.byteStream(), body.contentType()?.toString(), body.contentLength(), response::close)
            } catch (e: Exception) { response.close(); throw e }
        } catch (e: com.homura251.rule34downloader.network.CloudflareChallengeException) {
            checkDownloadActive()
            return Rule34Network.get(context).openBrowserMedia(request.url.toString(), ::checkDownloadActive, registerCancel)
        }
    }

    /** Called only while holding the artist's exclusive worker gate. */
    fun cleanInterruptedFiles(artistTag: String) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(collection, arrayOf(MediaStore.Downloads._ID),
            "relative_path = ? AND is_pending = 1 AND owner_package_name = ?",
            arrayOf(buildRelativePath(artistTag), context.packageName), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                checkDownloadActive()
                context.contentResolver.delete(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null)
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
        internal fun requireMediaResponse(response: Response) {
            if (!response.isSuccessful) throw IOException("原文件下载失败（HTTP ${response.code}）")
            val type = response.body?.contentType()
            if (type?.subtype.equals("html", true) || type?.subtype.equals("xhtml+xml", true)) {
                throw com.homura251.rule34downloader.network.CloudflareChallengeException("原文件下载返回了网页，正在尝试 WebView 传输。")
            }
        }

        fun buildRelativePath(artistTag: String): String =
            Environment.DIRECTORY_DOWNLOADS + "/Rule34 Downloader/" + sanitizeFolderName(artistTag) + "/"

        fun sanitizeFolderName(value: String): String {
            val sanitized = buildString(value.length) {
                value.forEach { char ->
                    append(
                        when {
                            char.code < 32 -> '_'
                            char in listOf('\\', '/', ':', '*', '?', '"', '<', '>', '|') -> '_'
                            else -> char
                        },
                    )
                }
            }.trim().trim('.', ' ')
            return sanitized.ifBlank { "unknown_artist" }.take(80)
        }
    }
}
