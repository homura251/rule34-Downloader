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
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import com.homura251.rule34downloader.network.HtmlChallengeException
import com.homura251.rule34downloader.ui.WebVerificationActivity
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Opt-in live origin probe. A challenge/timeout is a failure, never a mocked success or skipped test. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveSiteEndToEndTest {
    @Test fun a_anonymouslyResolveAndDownloadTheReportedLivePost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val postId = InstrumentationRegistry.getArguments().getString("livePostId")?.toLong() ?: 18905312L
        lateinit var network: Rule34Network
        InstrumentationRegistry.getInstrumentation().runOnMainSync { network = Rule34Network.get(context) }
        var uri: Uri? = null
        try {
            val resolved = network.htmlClient().getPostWithArtists(postId)
            writeTestText(context, "live", "source-artists.txt", "Source post: $postId\n" +
                resolved.artists.joinToString("\n") { "Artist: ${it.name}; site count: ${it.count}" })
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
            writeTestText(context, "live", "result.txt", "PASS: actual anonymous post -> original -> verified MediaStore publication\nPost: $postId\nBytes: $readBytes\nMD5: ${saved.verifiedMd5}\n${network.browserDiagnostics}\n")
        } catch (error: Throwable) {
            writeTestText(context, "live", "result.txt", "FAILED: actual anonymous origin did not complete\nPost: $postId\n${error.javaClass.simpleName}: ${error.message}\n${network.browserDiagnostics}\n")
            throw error
        } finally { uri?.let { context.contentResolver.delete(it, null, null) } }
    }
    @Test fun b_discoverEveryReportedArtistPostByIdCursor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val postId = InstrumentationRegistry.getArguments().getString("livePostId")?.toLong() ?: 18905312L
        lateinit var network: Rule34Network
        InstrumentationRegistry.getInstrumentation().runOnMainSync { network = Rule34Network.get(context) }
        var pages = 0
        val ids = linkedSetOf<Long>()
        try {
            val artist = network.htmlClient().getPostWithArtists(postId).artists.first()
            var before: Long? = null
            while (true) {
                val page = try { network.htmlClient().getSearchPage(artist.name, 0, before) }
                catch (challenge: HtmlChallengeException) {
                    // Exercise the app's visible verification handoff. Do not click or solve CAPTCHA.
                    ActivityScenario.launch(WebVerificationActivity::class.java).use { activity ->
                        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
                        while (activity.state != Lifecycle.State.DESTROYED && System.nanoTime() < deadline) Thread.sleep(300)
                        if (activity.state != Lifecycle.State.DESTROYED) throw challenge
                    }
                    network.htmlClient().getSearchPage(artist.name, 0, before)
                }
                ids.addAll(page.ids)
                pages++
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "Live inventory: ${artist.name}; pages=$pages; distinct IDs=${ids.size}\n")
                })
                if (page.ids.size < 42) break
                check(pages < 500) { "Live inventory exceeded 21,000 posts; result is incomplete." }
                before = page.ids.min()
                // A list-only probe requests new search pages much faster than the real worker,
                // which resolves and downloads up to 42 originals before changing the cursor.
                Thread.sleep(5_000)
            }
            writeTestText(context, "live", "source-inventory.txt",
                "PASS complete anonymous ID discovery\nArtist: ${artist.name}\nPosts: ${ids.size}\nPages: $pages\n" +
                    "Inventory only; the original-file test is separate\n")
        } catch (error: Throwable) {
            writeTestText(context, "live", "source-inventory.txt",
                "FAILED incomplete anonymous ID discovery\nPages: $pages\nDistinct IDs: ${ids.size}\n" +
                    "${error.javaClass.simpleName}: ${error.message}\n${network.browserDiagnostics}\n")
            throw error
        }
    }

}
