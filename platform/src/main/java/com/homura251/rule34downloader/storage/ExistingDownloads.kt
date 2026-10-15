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
    data class Match(val uri: Uri, val bytes: Long, val verifiedMd5: String)
    data class Lookup(val match: Match?, val rejection: String? = null)
    private data class Candidate(val uri: Uri, val identity: SavedFileIdentity?)
    private val resolver = context.contentResolver
    private val byId = mutableMapOf<Long, MutableList<Candidate>>()
    private val byHash = mutableMapOf<String, MutableList<Candidate>>()
    private val seen = hashSetOf<String>()
    private val unidentified = ArrayDeque<Candidate>()
    private val fingerprints = mutableMapOf<String, MutableList<Candidate>>()
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
            unidentified.addLast(file)
        }
    }

    private fun loadOwned() {
        val selected = currentFolder()
        if (ownedLoaded && indexedTree != selected) {
            byId.clear(); byHash.clear(); seen.clear(); unidentified.clear(); fingerprints.clear()
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

    fun lookup(record: DownloadRecord, checkActive: () -> Unit): Lookup {
        val expected = FileChecksum.expectedForReuse(record.md5, record.verifiedMd5, record.fileUrl)
            ?: return Lookup(null, "帖子 #" + record.postId + " 缺少可靠 MD5，无法校验旧文件")
        var candidateFound = false
        fun fingerprint(uri: Uri): SavedFileIdentity.Companion.Fingerprint? {
            checkActive()
            val result = try { resolver.openInputStream(uri)?.use { SavedFileIdentity.fingerprint(it, checkActive) } }
                catch (_: SecurityException) { null }
                catch (_: IOException) { null }
            checkActive()
            return result
        }
        fun verify(uri: Uri): Match? {
            candidateFound = true
            return fingerprint(uri)?.takeIf { it.md5 == expected }?.let { Match(uri, it.bytes, expected) }
        }
        // A valid recorded URI must remain usable even when an unrelated old tree grant has expired.
        record.localUri?.let { verify(Uri.parse(it))?.let { match -> return Lookup(match) } }
        loadOwned()
        fun indexed(): Match? {
            for (file in byId[record.postId].orEmpty() + byHash[expected].orEmpty()) {
                checkActive()
                verify(file.uri)?.let { return it }
            }
            return null
        }
        indexed()?.let { return Lookup(it) }
        loadLinked()
        indexed()?.let { return Lookup(it) }
        for (candidate in fingerprints[expected].orEmpty()) {
            verify(candidate.uri)?.let { return Lookup(it) }
        }
        // Renamed files have no reliable filename index. Read each at most once per run to
        // build a content index, then require the same trusted checksum as all other reuse.
        while (unidentified.isNotEmpty()) {
            val candidate = unidentified.removeFirst()
            val value = fingerprint(candidate.uri) ?: continue
            fingerprints.getOrPut(value.md5) { mutableListOf() } += candidate
            if (value.md5 == expected) return Lookup(Match(candidate.uri, value.bytes, expected))
        }
        return Lookup(null, if (candidateFound)
            "帖子 #" + record.postId + " 的候选旧文件校验未通过或已无法读取"
        else "未在已授权目录中找到与帖子 #" + record.postId + " 匹配的旧文件")
    }
}
