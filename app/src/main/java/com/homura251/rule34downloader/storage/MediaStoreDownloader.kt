package com.homura251.rule34downloader.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.homura251.rule34downloader.data.DownloadRecord
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection

class MediaStoreDownloader(
    private val context: Context,
) {
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

        val connection = (URI(record.fileUrl).toURL().openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

        var insertedUri: Uri? = null
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("原文件下载失败（HTTP $status）")
            }
            val totalBytes = connection.contentLengthLong.coerceAtLeast(0L)
            onHeaders(totalBytes)

            val mimeType = connection.contentType
                ?.substringBefore(';')
                ?.takeIf { it.contains('/') }
                ?: mimeTypeForExtension(extension)

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            insertedUri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: throw IOException("无法在系统 Download 集合中创建文件。")

            var written = 0L
            context.contentResolver.openOutputStream(insertedUri, "w")?.use { output ->
                connection.inputStream.use { input ->
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
                insertedUri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            return Result(insertedUri, written, false)
        } catch (e: Exception) {
            insertedUri?.let { context.contentResolver.delete(it, null, null) }
            throw e
        } finally {
            connection.disconnect()
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
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val USER_AGENT =
            "rule34-Downloader/1.0 (+https://github.com/homura251/rule34-Downloader)"

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
