package com.homura251.rule34downloader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PagedPreviewTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun authorPreviewLoadsPastItsFirstPageWithoutApiCredentials() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Rule34Application>()
        val graph = app.graph
        ui.waitUntil(30_000) { graph.startup.value.status == StartupStatus.READY }
        for (collection in graph.library.getCollections()) graph.background.remove(collection.tag)
        graph.account.save(ApiCredentials("42", "fixture-key"))
        val template = graph.catalog.getPost(2)
        val tag = "paged_preview_artist"
        graph.library.addCollection(tag, 0)
        assertTrue(graph.library.beginRun(tag, "seed"))
        graph.library.savePage(tag, "seed", (1L..365L).map { template.copy(id = it, tags = listOf(tag)) }, ScanCheckpoint(0, 1, 365))
        graph.library.endRun(tag, "seed")
        graph.background.pause(tag)
        graph.account.clear()
        try {
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("artist-$tag").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("artist-$tag").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("365 件作品", substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("gallery-grid").performScrollToIndex(50)
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("gallery-post-$tag-315").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("gallery-post-$tag-315").performClick()
            ui.onNodeWithText("#315 · 51/365").assertExists()
            repeat(25) { ui.onNodeWithTag("image-preview-pager").performTouchInput { swipeLeft() } }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("#290", substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("关闭预览").performClick()
            ui.onNodeWithTag("gallery-post-$tag-315").assertIsDisplayed()
            assertFalse(graph.account.state.value.canUseApi)
        } finally { graph.background.remove(tag) }
    }
}
