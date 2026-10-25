package com.homura251.rule34downloader.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.domain.files.SavedFileIdentity
import com.homura251.rule34downloader.domain.files.FileChecksum
import java.io.IOException

class ExistingDownloads(
    private val context: Context,
    private val artistTag: String,
    private val checkActive: () -> Unit,
    private val currentFolder: () -> String? = { null },
) {
    data class Match(val uri: Uri, val bytes: Long, val verifiedMd5: String? = null)
    data class Lookup(val match: Match?, val rejection: String? = null)
    private data class Candidate(val uri: Uri, val identity: SavedFileIdentity?)
    private val resolver = context.contentResolver
    private val byId = mutableMapOf<Long, MutableList<Candidate>>()
    private val byHash = mutableMapOf<String, MutableList<Candidate>>()
    private val seen = hashSetOf<String>()
    private var indexedTree: String? = null
    private var ownedLoaded = false
    private var linkedLoaded = false

    private fun add(uri: Uri, name: String) {
        if (!seen.add(uri.toString())) return
        val file = Candidate(uri, SavedFileIdentity.parse(name))
        val id = file.identity?.postId
        if (id != null) byId.getOrPut(id) { mutableListOf() } += file
        else {
            file.identity?.md5?.let { byHash.getOrPut(it) { mutableListOf() } += file }
        }
    }

    private fun loadOwned() {
        val selected = currentFolder()
        if (ownedLoaded && indexedTree != selected) {
            byId.clear(); byHash.clear(); seen.clear()
            ownedLoaded = false; linkedLoaded = false
        }
        if (ownedLoaded) return
        indexedTree = selected
        try {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "relative_path = ? AND is_pending = 0 AND _size > 0",
                arrayOf(MediaStoreDownloader.buildRelativePath(artistTag)), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    add(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)), cursor.getString(1))
                }
            }
        } catch (_: SecurityException) {
            // A reinstall obtains access through SAF instead.
        }
        ownedLoaded = true
    }

    private fun loadLinked() {
        if (linkedLoaded) return
        indexedTree?.let { value ->
            try {
                val directory = LinkedDownloadDirectory(resolver, Uri.parse(value), checkActive)
                for (entry in directory.filesForAuthor(MediaStoreDownloader.sanitizeFolderName(artistTag))) {
                    checkActive()
                    add(directory.uri(entry), entry.name)
                }
            } catch (_: SecurityException) {
                throw SourceFailure(FailureKind.FILE, "旧下载目录授权已失效，请在设置中重新选择目录。")
            } catch (e: IOException) {
                throw SourceFailure(FailureKind.FILE, if (e.message.orEmpty().contains("加载"))
                    "旧下载目录仍在加载，请待文件显示完整后重新关联。" else "无法读取旧下载目录，请在设置中重新选择目录。")
            } catch (_: IllegalArgumentException) {
                throw SourceFailure(FailureKind.FILE, "旧下载目录位置无效，请在设置中重新选择目录。")
            }
        }
        linkedLoaded = true
    }

    fun find(record: DownloadRecord, checkActive: () -> Unit): Match? = lookup(record, checkActive).match

    // No content scan during ordinary bulk restoration of unverified files.
    // Previously verified files are checked if they become queued for recovery.
    fun lookup(record: DownloadRecord, checkActive: () -> Unit): Lookup =
        lookup(record, verifyContent = record.verifiedMd5 != null, checkActive = checkActive)

    /** Explicitly enable content verification for a single suspect file; stat-only reuse is never verified. */
    fun lookup(record: DownloadRecord, verifyContent: Boolean, checkActive: () -> Unit): Lookup {
        val expected = FileChecksum.expectedForReuse(record.md5, record.verifiedMd5, record.fileUrl)
        val inspected = hashSetOf<String>()
        var checksumRejected = false
        fun readable(uri: Uri): Match? {
            if (!inspected.add(uri.toString())) return null
            checkActive()
            return try {
                val bytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
                if (bytes <= 0 || (record.totalBytes > 0 && bytes != record.totalBytes)) return null
                if (verifyContent && expected != null) {
                    val actual = resolver.openInputStream(uri)?.use { SavedFileIdentity.fingerprint(it, checkActive) }
                        ?: return null
                    checkActive()
                    if (actual.bytes != bytes || actual.md5 != expected) {
                        checksumRejected = true
                        null
                    } else Match(uri, bytes, expected)
                } else Match(uri, bytes) // A filename or recorded URI is not a checksum proof.
            } catch (_: SecurityException) { null } catch (_: IOException) { null }
        }
        record.localUri?.let { readable(Uri.parse(it))?.let { match -> return Lookup(match) } }
        loadOwned()
        fun indexed(): Match? {
            val candidates = byId[record.postId].orEmpty() + expected?.let { byHash[it] }.orEmpty()
            for (file in candidates) {
                checkActive()
                readable(file.uri)?.let { return it }
            }
            return null
        }
        indexed()?.let { return Lookup(it) }
        loadLinked()
        indexed()?.let { return Lookup(it) }
        return Lookup(null, if (checksumRejected) "帖子 #" + record.postId + " 的旧文件 MD5 校验失败，将重新下载。" else null)
    }
}
