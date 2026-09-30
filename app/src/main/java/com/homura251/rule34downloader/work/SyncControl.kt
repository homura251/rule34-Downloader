package com.homura251.rule34downloader.work

import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

class SyncPausedException : CancellationException("同步已暂停")

/** Keeps cancellation alive until the response body has been consumed/closed. */
class SyncControl(private val checkJob: () -> Unit = {}) {
    private val lock = Any()
    private val cancellations = mutableMapOf<Long, () -> Unit>()
    private var nextId = 0L
    @Volatile var paused = false
        private set

    fun checkActive() {
        checkJob()
        if (paused) throw SyncPausedException()
    }

    fun register(cancel: () -> Unit): Closeable {
        val id = synchronized(lock) {
            if (paused) null else (++nextId).also { cancellations[it] = cancel }
        }
        if (id == null) {
            cancel()
            throw SyncPausedException()
        }
        return Closeable { synchronized(lock) { cancellations.remove(id) }; Unit }
    }

    fun pause() {
        val actions = synchronized(lock) {
            paused = true
            cancellations.values.toList().also { cancellations.clear() }
        }
        actions.forEach { runCatching(it) }
    }

    val interceptor = Interceptor { chain ->
        checkActive()
        val registration = register { chain.call().cancel() }
        try {
            val response = chain.proceed(chain.request())
            try { checkActive() } catch (e: Exception) { response.close(); throw e }
            val body = response.body
            if (body == null) {
                registration.close()
                response
            } else {
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        checkActive()
                        return super.read(sink, byteCount)
                    }
                    override fun close() {
                        try { super.close() } finally { registration.close() }
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            }
        } catch (e: Exception) {
            registration.close()
            throw e
        }
    }
}

internal object SyncControls {
    private val active = ConcurrentHashMap<String, SyncControl>()
    private val gates = ConcurrentHashMap<String, Mutex>()
    // WorkManager replacement cancels asynchronously. Old cleanup must finish
    // before a new worker can alter this artist's rows or pending MediaStore files.
    fun gate(tag: String): Mutex = gates.getOrPut(tag) { Mutex() }
    fun activeTags(): List<String> = active.keys.toList()
    fun register(tag: String, control: SyncControl) { active.put(tag, control)?.pause() }
    fun unregister(tag: String, control: SyncControl) { active.remove(tag, control) }
    fun pause(tag: String): Boolean = active[tag]?.let { it.pause(); true } ?: false
}
