package com.homura251.rule34downloader.storage

import android.content.ContentUris
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
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.TimeUnit

class MediaStoreDownloader(
    private val context: Context,
) {
    private val httpClient = Rule34Network.get(context).client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    data class Result(
        val uri: Uri,
        val bytesWritten: Long,
        val existed: Boolean,
    )

    fun download(
        record: DownloadRecord,
        onHeaders: (totalBytes: Long) -> Unit,
        onProgress: (downloaded: Long, totalBytes: Long) -> Unit,
    ): Result {
        val extension = extensionFromUrl(record.fileUrl)
        val displayName = buildFileName(record.postId, record.md5, extension)
        val relativePath = buildRelativePath(record.artistTag)
        findExisting(displayName, relativePath)?.let { existing ->
            return Result(existing, 0L, true)
        }

        val request = Request.Builder().url(record.fileUrl)
            .header("Referer", "${Rule34Network.SITE}/index.php?page=post&s=view&id=${record.postId}")
            .build()

        var insertedUri: Uri? = null
        try {
            return httpClient.newCall(request).execute().use { response ->
                requireMediaResponse(response)
                val body = response.body ?: throw IOException("原文件下载返回了空响应。")
                val totalBytes = body.contentLength().coerceAtLeast(0L)
                onHeaders(totalBytes)

                val mimeType = body.contentType()?.toString()
                    ?.substringBefore(';')
                    ?.takeIf { it.contains('/') }
                    ?: mimeTypeForExtension(extension)

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val targetUri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: throw IOException("无法在系统 Download 集合中创建文件。")
                insertedUri = targetUri

                var written = 0L
                context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedIOException("下载已取消")
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read.toLong()
                            onProgress(written, totalBytes)
                        }
                        output.flush()
                    }
                } ?: throw IOException("无法打开目标文件。")

                context.contentResolver.update(
                    targetUri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
                Result(targetUri, written, false)
            }
        } catch (e: Exception) {
            insertedUri?.let { context.contentResolver.delete(it, null, null) }
            throw e
        }
    }

    private fun findExisting(displayName: String, relativePath: String): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val args = arrayOf(displayName, relativePath)
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ContentUris.withAppendedId(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                cursor.getLong(0),
            )
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
        private const val BUFFER_SIZE = 64 * 1024

        internal fun requireMediaResponse(response: Response) {
            if (!response.isSuccessful) throw IOException("原文件下载失败（HTTP ${response.code}）")
            val type = response.body?.contentType()
            if (type?.subtype.equals("html", true) || type?.subtype.equals("xhtml+xml", true)) {
                throw IOException("原文件下载返回了网页。请完成设置中的「网页验证」后重新同步。")
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
