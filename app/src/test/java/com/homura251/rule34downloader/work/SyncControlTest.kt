package com.homura251.rule34downloader.work

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncControlTest {
    @Test fun pauseCancelsActiveRequestsOnlyOnce() {
        val control = SyncControl()
        var active = 0
        var closed = 0
        control.register { active++ }
        control.register { closed++ }.close()
        control.pause(); control.pause()
        assertEquals(1, active); assertEquals(0, closed)
        assertThrows(SyncPausedException::class.java) { control.checkActive() }
        var late = false
        assertThrows(SyncPausedException::class.java) { control.register { late = true } }
        assertTrue(late)
    }
    @Test fun anOlderWorkerCannotRemoveTheResumedWorkersControl() {
        val old = SyncControl()
        val resumed = SyncControl()
        SyncControls.register("test-artist", old)
        SyncControls.register("test-artist", resumed)
        SyncControls.unregister("test-artist", old)
        assertTrue(old.paused); assertFalse(resumed.paused)
        assertTrue(SyncControls.pause("test-artist")); assertTrue(resumed.paused)
        SyncControls.unregister("test-artist", resumed)
    }
    @Test fun pauseCancelsAnAlreadyStreamingResponseBody() {
        val control = SyncControl()
        val executor = Executors.newSingleThreadExecutor()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100_000)).throttleBody(1024, 1, TimeUnit.SECONDS))
            val client = OkHttpClient.Builder().addInterceptor(control.interceptor).build()
            val firstByte = CountDownLatch(1)
            try {
                val future = executor.submit<Throwable?> {
                    try {
                        client.newCall(Request.Builder().url(server.url("/media")).build()).execute().use {
                            val input = it.body!!.byteStream()
                            input.read(); firstByte.countDown(); input.readBytes()
                        }
                        null
                    } catch (e: Exception) { e }
                }
                assertTrue(firstByte.await(10, TimeUnit.SECONDS))
                control.pause()
                assertNotNull(future.get(10, TimeUnit.SECONDS))
            } finally {
                control.pause(); executor.shutdownNow()
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            }
        }
    }
}
