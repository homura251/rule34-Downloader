package com.homura251.rule34downloader

import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApiAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        startFixture { ApiTestEnvironment.origin = TestApiOrigin("test_api_controlled", 3) }
        super.onCreate(arguments)
    }
    override fun callApplicationOnCreate(app: Application) {
        (app as Rule34Application).installGraphForTests(AppGraph(app, ApiTestEnvironment.origin.client, ApiTestEnvironment.origin.client, ApiTestEnvironment.origin.endpoint))
        super.callApplicationOnCreate(app)
    }
    override fun finish(resultCode: Int, results: Bundle?) { ApiTestEnvironment.origin.close(); super.finish(resultCode, results) }
}
internal object ApiTestEnvironment { lateinit var origin: TestApiOrigin }
internal fun startFixture(create: () -> Unit) {
    val ready = CountDownLatch(1)
    var failure: Throwable? = null
    Thread { try { create() } catch (e: Throwable) { failure = e } finally { ready.countDown() } }.start()
    check(ready.await(30, TimeUnit.SECONDS)) { "HTTP fixture did not start" }
    failure?.let { throw it }
}
