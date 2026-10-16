package com.homura251.rule34downloader.domain

import com.homura251.rule34downloader.domain.files.DirectoryLayout
import com.homura251.rule34downloader.domain.input.PostUrlParser

data class SavedDownloadDirectory(val name: String, val firstPostId: Long?, val fileCount: Int,
    val mediaFileCount: Int = fileCount)
data class RestoredSubscription(val tag: String, val sourcePostId: Long)
data class FolderRestorePlan(val additions: List<RestoredSubscription>, val targets: Set<String>,
    val paused: Int, val poolDirectories: Int, val unrecognizedDirectories: Int) {
    companion object {
        fun create(directories: List<SavedDownloadDirectory>, existing: List<CollectionRecord>, wholeLibrary: Boolean): FolderRestorePlan {
            val additions = linkedMapOf<String, RestoredSubscription>()
            val matched = linkedSetOf<String>()
            var pools = 0
            var unknown = 0
            for (directory in directories) {
                val known = existing.filter { DirectoryLayout.folderName(it.tag).equals(directory.name, ignoreCase = true) }
                if (known.any { it.poolId != null } || directory.name.matches(Regex("pool_[0-9]+"))) {
                    pools++; continue
                }
                if (known.isNotEmpty()) {
                    matched.addAll(known.map { it.tag }); continue
                }
                if (directory.fileCount == 0) {
                    if (directory.mediaFileCount > 0) unknown++
                    continue
                }
                val name = directory.name
                if (name == "unknown_artist" || name.lowercase().filter(Char::isLetterOrDigit) in
                    setOf("rule34downloader", "r34downloader", "download", "downloads", "images", "videos", "pictures") ||
                    PostUrlParser.normalizeArtistTag(name) == null ||
                    name.any(Char::isWhitespace) || DirectoryLayout.folderName(name) != name) {
                    unknown++; continue
                }
                additions[name] = RestoredSubscription(name, directory.firstPostId ?: 0)
                matched += name
            }
            if (wholeLibrary) matched.addAll(existing.filter { it.poolId == null }.map { it.tag })
            val paused = existing.count { it.tag in matched && it.paused }
            val targets = matched.filterTo(linkedSetOf()) { tag -> existing.none { it.tag == tag && it.paused } }
            return FolderRestorePlan(additions.values.toList(), targets, paused, pools, unknown)
        }
    }
}
