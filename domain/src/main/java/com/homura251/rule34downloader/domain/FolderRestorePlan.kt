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
                val name = directory.name
                val tag = PostUrlParser.normalizeArtistTag(name)
                val known = existing.filter { DirectoryLayout.folderName(it.tag).equals(name, ignoreCase = true) ||
                    tag != null && it.tag.equals(tag, ignoreCase = true) }
                if (known.any { it.poolId != null } || directory.name.matches(Regex("pool_[0-9]+"))) {
                    pools++; continue
                }
                if (known.isNotEmpty()) {
                    matched.addAll(known.map { it.tag }); continue
                }
                if (isContainer(name)) {
                    if (directory.mediaFileCount > 0) unknown++
                    continue
                }
                if (name.equals("unknown_artist", ignoreCase = true) || tag == null || DirectoryLayout.folderName(name) != name) {
                    unknown++; continue
                }
                // An author directory is itself a subscription hint. It does not need to
                // contain any files, nor must old filenames expose a post ID or checksum.
                additions[tag] = RestoredSubscription(tag, directory.firstPostId ?: additions[tag]?.sourcePostId ?: 0)
                matched += tag
            }
            if (wholeLibrary) matched.addAll(existing.filter { it.poolId == null }.map { it.tag })
            val paused = existing.count { it.tag in matched && it.paused }
            val targets = matched.filterTo(linkedSetOf()) { tag -> existing.none { it.tag == tag && it.paused } }
            return FolderRestorePlan(additions.values.toList(), targets, paused, pools, unknown)
        }

        private fun isContainer(name: String): Boolean = name.lowercase().filter(Char::isLetterOrDigit) in setOf(
            "rule34downloader", "r34downloader", "download", "downloads", "image", "images", "video", "videos",
            "picture", "pictures", "original", "originals", "media", "artists", "authors",
            "backup", "backups", "backupfiles", "archive", "archives", "下载", "作者", "画师", "原图", "图片", "视频", "备份"
        ) || name.matches(Regex("(?:19|20)[0-9]{2}"))
    }
}
