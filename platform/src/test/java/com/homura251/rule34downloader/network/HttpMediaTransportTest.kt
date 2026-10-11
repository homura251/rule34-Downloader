package com.homura251.rule34downloader.network

import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.work.SyncControl
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class HttpMediaTransportTest {
    @Test fun disconnectedResponseBodyIsRetryableNetworkFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(ByteArray(128 * 1024)))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val transport = HttpMediaTransport(OkHttpClient(), SyncControl {})
            transport.open(MediaRequest(server.url("/original.png").toString(), "https://rule34.xxx/")).use { stream ->
                try { stream.input.readBytes(); fail("A disconnected body must not complete") }
                catch (error: SourceFailure) { assertEquals(FailureKind.NETWORK, error.kind); assertTrue(error.retryable) }
            }
        }
    }
}
