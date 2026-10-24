package com.homura251.rule34downloader.domain.files

import java.net.URI

/** One naming rule for the file written to Downloads and its entry in the task list. */
object DownloadFileName {
    fun extension(url: String): String = runCatching {
        URI(url).path.orEmpty().substringAfterLast('.', "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
    }.getOrNull() ?: "bin"

    fun build(postId: Long, md5: String, url: String): String {
        val hash = FileChecksum.expected(md5, url)
        return "$postId${hash?.let { "_$it" }.orEmpty()}.${extension(url)}"
    }
}
