package com.homura251.rule34downloader

import android.content.Context
import com.homura251.rule34downloader.data.*
import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.network.*
import com.homura251.rule34downloader.work.BackgroundRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AppGraph(context: Context, apiHttp: OkHttpClient? = null, val mediaHttp: OkHttpClient = MediaHttpClient.create(), endpoint: HttpUrl? = null) : AppServices {
    private val mutableStartup = MutableStateFlow(StartupState())
    override val startup = mutableStartup.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        mutableStartup.value = StartupState(StartupStatus.ERROR, "无法打开资料库或设置。请重新尝试；已有文件会保留。")
    })
    private val started = AtomicBoolean()
    private val initializing = AtomicBoolean()
    override val library = RoomLibraryRepository.create(context)
    override val account = AndroidAccountRepository(context, scope)
    override val settings = DataStoreSettingsRepository(context, scope)
    private val apiClient = apiHttp ?: OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "rule34-Downloader/2.0 (+https://github.com/homura251/rule34-Downloader)").build()) }.build()
    override val catalog: CatalogGateway = if (endpoint == null) Rule34ApiClient(account, apiClient) else {
        check(BuildConfig.DEBUG && endpoint.host in setOf("127.0.0.1", "localhost"))
        Rule34ApiClient(account, apiClient, ApiRateLimiter(0), endpoint)
    }
    val background = BackgroundRuntime(context.applicationContext, library, account, settings, catalog, mediaHttp)
    override val tasks: TaskController = background
    override val folders: DownloadFolderAccess = background
    fun start() {
        if (!started.compareAndSet(false, true)) return
        retryStartup()
        scope.launch { account.state.filter { it.status == AccountStatus.INVALID }.collectLatest { background.authenticationChanged() } }
    }
    override fun retryStartup() {
        if (!initializing.compareAndSet(false, true)) return
        mutableStartup.value = StartupState()
        scope.launch {
            try { background.initialize(); mutableStartup.value = StartupState(StartupStatus.READY) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableStartup.value = StartupState(StartupStatus.ERROR, "无法打开资料库或设置。请重新尝试；已有文件会保留。") }
            finally { initializing.set(false) }
        }
    }
    fun close() { scope.cancel() }
}
