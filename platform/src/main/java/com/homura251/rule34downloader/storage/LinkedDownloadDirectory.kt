package com.homura251.rule34downloader.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.homura251.rule34downloader.domain.SavedDownloadDirectory
import com.homura251.rule34downloader.domain.files.SavedFileIdentity
import java.io.IOException

/** Shared SAF traversal for discovery and reuse. Document IDs are opaque, never filesystem paths. */
internal class LinkedDownloadDirectory(private val resolver: ContentResolver, private val tree: Uri,
    private val checkActive: () -> Unit, private val knownFolders: Set<String> = emptySet()) {
    data class Entry(val id: String, val name: String, val directory: Boolean, val mimeType: String = "")
    data class Scan(val directories: List<SavedDownloadDirectory>, val wholeLibrary: Boolean,
        val name: String, val mediaFiles: Int, val identifiedFiles: Int, val unreadableDirectories: List<String>, val totalFiles: Int)
    private data class Node(val entry: Entry, val ancestors: List<Entry>, val items: List<Entry>?)
    private val rootId = try { DocumentsContract.getTreeDocumentId(tree) }
        catch (_: IllegalArgumentException) { throw IOException("所选位置不是有效的目录，请通过系统目录选择器重新选择。") }
    private val root by lazy {
        checkActive()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val name = resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null
        } ?: throw IOException("无法读取所选目录，目录可能已移动，请重新选择。")
        Entry(rootId, name, true)
    }
    private val childrenCache = mutableMapOf<String, List<Entry>>()
    private var nodeCache: List<Node>? = null
    private val unreadable = linkedSetOf<String>()

    fun children(id: String): List<Entry> = childrenCache.getOrPut(id) {
        checkActive()
        val result = mutableListOf<Entry>()
        (resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?: throw IOException("系统未返回目录内容，请检查授权后重新选择。")).use { cursor ->
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
                throw IOException("系统目录仍在加载，请等待文件显示完整后重新关联。")
            if (!cursor.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank())
                throw IOException("系统目录服务暂时无法读取文件，请稍后重新关联。")
            while (cursor.moveToNext()) {
                checkActive()
                val documentId = cursor.getString(0)?.takeIf(String::isNotBlank)
                    ?: throw IOException("系统返回了不完整的文件信息，请重新关联目录。")
                val name = cursor.getString(1)?.takeIf(String::isNotBlank)
                    ?: throw IOException("系统返回了不完整的文件名，请重新关联目录。")
                val type = cursor.getString(2).orEmpty()
                result += Entry(documentId, name, type == DocumentsContract.Document.MIME_TYPE_DIR, type)
                if (result.size > MAX_ENTRIES) throw IOException("目录内容过多，请改为选择作者目录或下载资料库目录。")
            }
        }
        result
    }

    private fun nodes(): List<Node> {
        nodeCache?.let { return it }
        val result = mutableListOf<Node>()
        val visited = hashSetOf<String>()
        fun visit(entry: Entry, ancestors: List<Entry>) {
            checkActive()
            if (!visited.add(entry.id)) return
            if (visited.size > MAX_DIRECTORIES || ancestors.size > MAX_DEPTH)
                throw IOException("目录层级或数量过多，请改为选择更接近作者文件的目录。")
            val items = try { children(entry.id) }
            catch (e: IOException) { if (entry.id == rootId) throw e; unreadable += entry.name; null }
            catch (e: SecurityException) { if (entry.id == rootId) throw e; unreadable += entry.name; null }
            result += Node(entry, ancestors, items)
            items.orEmpty().filter { it.directory }.forEach { visit(it, ancestors + entry) }
        }
        visit(root, emptyList())
        return result.also { nodeCache = it }
    }

    private fun owner(node: Node): Entry {
        val path = node.ancestors + node.entry
        path.lastOrNull { entry -> knownFolders.any { it.equals(entry.name, ignoreCase = true) } }?.let { return it }
        val app = path.indexOfLast { isAppDirectory(it.name) }
        if (app >= 0) return path.drop(app + 1).firstOrNull { !isMediaContainer(it.name) && !isWrapper(it.name) }
            ?: path[app]
        return path.lastOrNull { !isMediaContainer(it.name) && !isWrapper(it.name) } ?: root
    }

    fun scan(): Scan {
        val nodes = nodes()
        val filesByAuthor = linkedMapOf<Entry, MutableList<Entry>>()
        for (node in nodes) {
            val media = node.items.orEmpty().filter(::isMedia)
            if (media.isNotEmpty() || node.entry.name in knownFolders || node.items?.isEmpty() == true)
                filesByAuthor.getOrPut(owner(node)) { mutableListOf() }.addAll(media)
        }
        val directories = filesByAuthor.map { (author, media) ->
            val unique = media.distinctBy { it.id }
            val identities = unique.mapNotNull { SavedFileIdentity.parse(it.name) }
            SavedDownloadDirectory(author.name, identities.mapNotNull { it.postId }.minOrNull(), identities.size, unique.size)
        }
        return Scan(directories, nodes.any { isAppDirectory(it.entry.name) }, root.name,
            directories.sumOf { it.mediaFileCount }, directories.sumOf { it.fileCount }, unreadable.toList(),
            nodes.flatMap { it.items.orEmpty() }.filterNot { it.directory }.distinctBy { it.id }.size)
    }

    private fun collect(id: String): List<Entry> {
        val result = mutableListOf<Entry>()
        val visited = hashSetOf<String>()
        fun visit(folder: String, depth: Int) {
            checkActive()
            if (!visited.add(folder)) return
            if (depth > MAX_DEPTH || visited.size > MAX_DIRECTORIES)
                throw IOException("目录层级或数量过多，请选择作者目录重新关联。")
            for (entry in children(folder)) {
                if (entry.directory) visit(entry.id, depth + 1) else result += entry
            }
        }
        visit(id, 0)
        return result.distinctBy { it.id }
    }

    fun filesForAuthor(folderName: String): List<Entry> {
        if (root.name.equals(folderName, ignoreCase = true)) return collect(rootId)
        val top = children(rootId).filter { it.directory }
        val direct = top.filter { it.name.equals(folderName, ignoreCase = true) }
        if (direct.isNotEmpty()) return direct.flatMap { collect(it.id) }.distinctBy { it.id }
        val common = top.filter { isAppDirectory(it.name) }.flatMap { children(it.id) }
            .filter { it.directory && it.name.equals(folderName, ignoreCase = true) }
        if (common.isNotEmpty()) return common.flatMap { collect(it.id) }.distinctBy { it.id }
        val all = nodes()
        val matched = all.filter { it.entry.name.equals(folderName, ignoreCase = true) }
        if (matched.isNotEmpty()) return matched.flatMap { collect(it.entry.id) }.distinctBy { it.id }
        // Renamed folders and flat archives remain eligible by post ID and content checksum.
        return all.flatMap { it.items.orEmpty() }.filterNot { it.directory }.distinctBy { it.id }
    }

    fun uri(entry: Entry): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
    companion object {
        private const val MAX_DEPTH = 32
        private const val MAX_DIRECTORIES = 20_000
        private const val MAX_ENTRIES = 250_000
        private val MEDIA_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "apng", "jxl", "heic", "heif", "tif", "tiff", "mp4", "webm", "m4v", "mov", "swf")
        private fun normalized(name: String) = name.lowercase().filter(Char::isLetterOrDigit)
        private fun isAppDirectory(name: String) = normalized(name) in setOf("rule34downloader", "r34downloader")
        private fun isWrapper(name: String) = normalized(name) in setOf("artists", "authors", "downloads", "download", "backup", "backups", "backupfiles", "archive", "archives", "下载", "作者", "画师", "备份")
        private fun isMediaContainer(name: String) = normalized(name) in setOf("images", "image", "videos", "video", "pictures", "originals", "original", "media", "图片", "视频", "原图") || name.matches(Regex("(?:19|20)[0-9]{2}"))
        private fun isMedia(entry: Entry) = !entry.directory && (entry.mimeType.startsWith("image/") ||
            entry.mimeType.startsWith("video/") || entry.name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS ||
            SavedFileIdentity.parse(entry.name)?.md5 != null)
    }
}
