package com.homura251.rule34downloader.domain.files

/** Keep legacy folder naming identical for existing-file recovery and UI previews. */
object DirectoryLayout {
    fun relativePath(collectionId: String) = "Download/Rule34 Downloader/${folderName(collectionId)}/"
    fun folderName(value: String): String = value.map { char ->
        if (char.code < 32 || char in "\\/:*?\"<>|") '_' else char
    }.joinToString("").trim().trim('.', ' ').ifBlank { "unknown_artist" }.take(80)
}
