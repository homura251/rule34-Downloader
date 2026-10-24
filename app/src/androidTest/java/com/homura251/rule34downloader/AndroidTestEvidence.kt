package com.homura251.rule34downloader

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.OutputStream

/** Public test evidence survives AGP uninstalling both APKs after instrumentation finishes. */
internal fun writeTestEvidence(context: Context, scope: String, name: String, mime: String, write: (OutputStream) -> Unit) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Rule34 Downloader Test Evidence/$scope/")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create test evidence")
    try {
        context.contentResolver.openOutputStream(uri)!!.use(write)
        check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
    } catch (error: Throwable) { context.contentResolver.delete(uri, null, null); throw error }
}

internal fun writeTestText(context: Context, scope: String, name: String, text: String) =
    writeTestEvidence(context, scope, name, if (name.endsWith(".json", true)) "application/json" else "text/plain") { it.write(text.toByteArray()) }

/** PermissionController resource owners differ between AOSP and Google system images. */
internal fun allowNotificationsIfPrompted() {
    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
    val button = androidx.test.uiautomator.By.res(java.util.regex.Pattern.compile(".*:id/permission_allow_button"))
    val allow = device.wait(androidx.test.uiautomator.Until.findObject(button), 3_000)
        ?: device.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)allow")))
    if (allow != null) {
        allow.click()
        check(device.wait(androidx.test.uiautomator.Until.gone(button), 10_000)) { "Notification dialog did not close" }
        device.waitForIdle()
        instrumentation.waitForIdleSync()
    }
}
