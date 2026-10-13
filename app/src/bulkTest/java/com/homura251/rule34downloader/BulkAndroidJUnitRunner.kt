package com.homura251.rule34downloader

import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.homura251.rule34downloader.data.CredentialsStore
import com.homura251.rule34downloader.domain.ApiCredentials

/** Configure adapters before Application.onCreate can recover an interrupted worker. */
class BulkAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        val phase = arguments.getString("bulkPhase") ?: "api"
        val count = arguments.getString("bulkCount")?.toInt() ?: 84
        val key = arguments.getString("bulkKey") ?: "manual"
        val prefix = when (phase) { "api" -> "api_"; "crash", "recover" -> "crash_"; else -> "" }
        startFixture { BulkTestEnvironment.origin = BulkOrigin("test_bulk_" + prefix + key, count, phase in setOf("api", "checkpoint", "crash")) }
        super.onCreate(arguments)
    }
    override fun callApplicationOnCreate(app: Application) {
        val store = CredentialsStore(app)
        BulkTestEnvironment.credentialsPresentBeforeSeed = store.get() != null
        store.save(ApiCredentials("42", "fixture-key"))
        val origin = BulkTestEnvironment.origin
        (app as Rule34Application).installGraphForTests(AppGraph(app, origin.client, origin.client, origin.endpoint))
        super.callApplicationOnCreate(app)
    }
    override fun finish(resultCode: Int, results: Bundle?) { BulkTestEnvironment.origin.close(); super.finish(resultCode, results) }
}
internal object BulkTestEnvironment {
    lateinit var origin: BulkOrigin
    var credentialsPresentBeforeSeed = false
}
