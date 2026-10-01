package com.homura251.rule34downloader

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homura251.rule34downloader.data.DownloadRecord
import com.homura251.rule34downloader.data.DownloadStatus
import com.homura251.rule34downloader.network.Rule34Network
import com.homura251.rule34downloader.storage.MediaStoreDownloader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in live origin probe. A challenge/timeout is a failure, never a mocked success or skipped test. */
@RunWith(AndroidJUnit4::class)
class LiveSiteEndToEndTest {
    @Test fun anonymouslyResolveAndDownloadTheReportedLivePost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val postId = InstrumentationRegistry.getArguments().getString("livePostId")?.toLong() ?: 18905312L
        val report = File(context.getExternalFilesDir(null), "live-e2e/result.txt").also { it.parentFile!!.mkdirs() }
        lateinit var network: Rule34Network
        InstrumentationRegistry.getInstrumentation().runOnMainSync { network = Rule34Network.get(context) }
        var uri: Uri? = null
        try {
            val resolved = network.htmlClient().getPostWithArtists(postId)
            assertTrue("Live original must include a reliable MD5", resolved.post.md5.matches(Regex("[a-fA-F0-9]{32}")))
            val record = DownloadRecord("test_live_e2e", postId, resolved.post.fileUrl, resolved.post.md5, DownloadStatus.PENDING, 0, 0)
            val saved = MediaStoreDownloader(context, network.client).download(record, {}, { _, _ -> })
            uri = saved.uri
            val digest = MessageDigest.getInstance("MD5")
            var readBytes = 0L
            context.contentResolver.openInputStream(saved.uri)!!.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count); readBytes += count }
            }
            assertEquals(saved.bytesWritten, readBytes)
            assertEquals(resolved.post.md5.lowercase(), digest.digest().joinToString("") { "%02x".format(it) })
            context.contentResolver.query(saved.uri, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            report.writeText("PASS: actual anonymous post -> original -> verified MediaStore publication\nPost: $postId\nBytes: $readBytes\nMD5: ${saved.verifiedMd5}\n${network.browserDiagnostics}\n")
        } catch (error: Throwable) {
            report.writeText("FAILED: actual anonymous origin did not complete\nPost: $postId\n${error.javaClass.simpleName}: ${error.message}\n${network.browserDiagnostics}\n")
            throw error
        } finally { uri?.let { context.contentResolver.delete(it, null, null) } }
    }
}
