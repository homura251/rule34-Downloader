package com.homura251.rule34downloader.network

import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.work.SyncControl
import kotlinx.coroutines.*
import okhttp3.*
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object MediaHttpClient {
    fun create(): OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).followSslRedirects(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
            .header("User-Agent", "rule34-Downloader/2.0 (+https://github.com/homura251/rule34-Downloader)").build()) }
        .build()
}

/** Media requests never use the API authentication client, cookies or a browser session. */
class HttpMediaTransport(private val client: OkHttpClient, private val control: SyncControl) : MediaTransport {
    override suspend fun open(request: MediaRequest): MediaStream = withContext(Dispatchers.IO) {
        control.checkActive()
        val call = client.newCall(Request.Builder().url(request.url).header("Referer", request.referer).build())
        val registration = control.register(call::cancel)
        try {
            val response = call.execute()
            try {
                requireResponse(response)
                val body = response.body ?: throw SourceFailure(FailureKind.FILE, "原文件响应为空。")
                val input = object : FilterInputStream(body.byteStream()) {
                    private fun readChecked(block: () -> Int): Int = try { block() } catch (_: IOException) {
                        control.checkActive()
                        throw SourceFailure(FailureKind.NETWORK, "原文件传输中断，等待重试。")
                    }
                    override fun read(): Int = readChecked { `in`.read() }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = readChecked { `in`.read(buffer, offset, length) }
                }
                MediaStream(input, body.contentType()?.toString(), body.contentLength()) {
                    try { response.close() } finally { registration.close() }
                }
            } catch (e: Exception) { response.close(); throw e }
        } catch (e: CancellationException) { registration.close(); throw e }
        catch (e: SourceFailure) { registration.close(); throw e }
        catch (_: IOException) {
            registration.close(); control.checkActive()
            throw SourceFailure(FailureKind.NETWORK, "原文件连接失败，等待重试。")
        }
    }
    companion object {
        fun requireResponse(response: Response) {
            when {
                response.code == 429 -> throw SourceFailure(FailureKind.RATE_LIMIT, "原文件服务器限流，稍后自动重试。", RetryAfter.millis(response.header("Retry-After")))
                response.code >= 500 -> throw SourceFailure(FailureKind.SERVER, "原文件服务器暂时不可用。", RetryAfter.millis(response.header("Retry-After")))
                response.code == 403 -> throw SourceFailure(FailureKind.BLOCKED, "原文件服务器拒绝访问，可稍后手动重试。")
                !response.isSuccessful -> throw SourceFailure(FailureKind.FILE, "原文件下载失败（HTTP ${response.code}）。")
            }
            val type = response.body?.contentType()
            if (type?.subtype.equals("html", true) || type?.subtype.equals("xhtml+xml", true))
                throw SourceFailure(FailureKind.BLOCKED, "原文件返回了验证网页，未保存文件。")
        }
    }
}
