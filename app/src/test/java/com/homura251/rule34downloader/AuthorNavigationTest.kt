package com.homura251.rule34downloader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthorNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun theFirstTouchOnAnAuthorOpensItsGallery() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<Rule34Application>().graph
        ui.waitUntil(30_000) { graph.startup.value.status != StartupStatus.LOADING }
        assertTrue(graph.startup.value.toString(), graph.startup.value.status == StartupStatus.READY)
        val tag = "first_touch_artist"
        val images = MockWebServer()
        images.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("pending image")
                .setHeadersDelay(2, TimeUnit.SECONDS)
        }
        images.start()
        graph.library.addCollection(tag, 0)
        assertTrue(graph.library.beginRun(tag, "seed"))
        graph.library.savePage(tag, "seed", listOf(Rule34Post(1, images.url("/cover.png").toString(), "", listOf(tag))), ScanCheckpoint(0, 1, 1))
        graph.library.endRun(tag, "seed")
        graph.background.pause(tag)
        try {
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("artist-$tag").fetchSemanticsNodes().isNotEmpty() }
            repeat(2) {
                ui.mainClock.autoAdvance = false
                ui.onNodeWithTag("artist-$tag").performTouchInput {
                    if (it == 0) click() else click(center.copy(y = height - 24f))
                }
                ui.mainClock.advanceTimeBy(160)
                ui.onNodeWithTag("subscriptions-list").assertDoesNotExist()
                ui.onNodeWithTag("gallery-grid").assertIsDisplayed()
                ui.mainClock.autoAdvance = true
                ui.waitUntil(15_000) { ui.onAllNodesWithTag("gallery-post-$tag-1").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("gallery-post-$tag-1").assertIsDisplayed()
                ui.onNodeWithTag("image-preview-pager").assertDoesNotExist()
                ui.onNodeWithContentDescription("返回").performTouchInput { click() }
                ui.onNodeWithTag("artist-$tag").assertIsDisplayed()
            }
        } finally { graph.background.remove(tag); images.shutdown() }
    }
}
