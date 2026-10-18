package com.homura251.rule34downloader.domain

import com.homura251.rule34downloader.domain.files.DirectoryLayout
import com.homura251.rule34downloader.domain.input.PostUrlParser
import java.util.Locale

data class SavedDownloadDirectory(val name: String, val firstPostId: Long?, val fileCount: Int,
    val mediaFileCount: Int = fileCount)
data class RestoredSubscription(val tag: String, val sourcePostId: Long)
data class FolderRestorePlan(val additions: List<RestoredSubscription>, val targets: Set<String>,
    val paused: Int, val poolDirectories: Int, val unrecognizedDirectories: Int,
    val discoveredAuthors: Set<String>, val removedAuthors: Int) {
    companion object {
        fun create(directories: List<SavedDownloadDirectory>, existing: List<CollectionRecord>, wholeLibrary: Boolean,
            previouslyRestored: Set<String> = emptySet(), requestedRestores: Set<String> = emptySet()): FolderRestorePlan {
            val additions = linkedMapOf<String, RestoredSubscription>()
            val matched = linkedSetOf<String>()
            val discovered = linkedSetOf<String>()
            val removed = hashSetOf<String>()
            fun key(value: String) = value.lowercase(Locale.ROOT)
            val knownFolders = existing.groupBy { key(DirectoryLayout.folderName(it.tag)) }
            val knownTags = existing.groupBy { key(it.tag) }
            val oldFolders = previouslyRestored.groupBy { key(DirectoryLayout.folderName(it)) }
            val oldTags = previouslyRestored.groupBy(::key)
            val requested = requestedRestores.mapTo(hashSetOf(), ::key)
            fun restore(tag: String, postId: Long?) {
                val old = additions[key(tag)]
                val canonical = old?.tag ?: tag
                additions[key(tag)] = RestoredSubscription(canonical, postId ?: old?.sourcePostId ?: 0)
                matched += canonical
                discovered += canonical
            }
            var pools = 0
            var unknown = 0
            for (directory in directories) {
                val name = directory.name
                val tag = PostUrlParser.normalizeArtistTag(name)
                val known = (knownFolders[key(name)].orEmpty() + knownTags[tag?.let(::key)].orEmpty()).distinctBy { it.tag }
                if (known.any { it.poolId != null } || directory.name.matches(Regex("pool_[0-9]+"))) {
                    pools++; continue
                }
                if (known.isNotEmpty()) {
                    discovered.addAll(known.map { it.tag })
                    matched.addAll(known.map { it.tag }); continue
                }
                val historical = (oldFolders[key(name)].orEmpty() + oldTags[tag?.let(::key)].orEmpty()).distinctBy(::key)
                if (historical.isNotEmpty()) {
                    for (original in historical) {
                        discovered += original
                        if (key(original) in requested) restore(original, directory.firstPostId)
                        else removed += key(original)
                    }
                    continue
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
                restore(tag, directory.firstPostId)
            }
            if (wholeLibrary) matched.addAll(existing.filter { it.poolId == null }.map { it.tag })
            val paused = existing.count { it.tag in matched && it.paused }
            val pausedTags = existing.filter { it.paused }.mapTo(hashSetOf()) { it.tag }
            val targets = matched.filterTo(linkedSetOf()) { it !in pausedTags }
            return FolderRestorePlan(additions.values.toList(), targets, paused, pools, unknown, discovered, removed.size)
        }

        private fun isContainer(name: String): Boolean = name.lowercase().filter(Char::isLetterOrDigit) in setOf(
            "rule34downloader", "r34downloader", "download", "downloads", "image", "images", "video", "videos",
            "picture", "pictures", "original", "originals", "media", "artists", "authors",
            "backup", "backups", "backupfiles", "archive", "archives", "下载", "作者", "画师", "原图", "图片", "视频", "备份"
        ) || name.matches(Regex("(?:19|20)[0-9]{2}"))
    }
}
