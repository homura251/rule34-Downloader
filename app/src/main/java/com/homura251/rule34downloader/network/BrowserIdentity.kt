package com.homura251.rule34downloader.network

import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Keep the UA, engine version and Chromium client hints consistent, as Mihon does. */
internal object BrowserIdentity {
    fun userAgent(default: String): String = default
        .replace(Regex("; Android .*?\\)"), "; Android 10; K)")
        .replace(Regex("Version/[^ ]+\\s+Chrome/"), "Chrome/")

    fun apply(view: WebView, userAgent: String) {
        view.settings.userAgentString = userAgent
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val version = Regex("Chrome/(\\d+)([.\\d]*)").find(userAgent) ?: return
        val major = version.groupValues[1]
        val full = major + version.groupValues[2].ifBlank { ".0.0.0" }
        val metadata = WebSettingsCompat.getUserAgentMetadata(view.settings)
        val brands = metadata.brandVersionList.map { brand ->
            if (brand.brand !in setOf("Android WebView", "Chromium", "Google Chrome")) return@map brand
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand(if (brand.brand == "Android WebView") "Google Chrome" else brand.brand)
                .setMajorVersion(major).setFullVersion(full).build()
        }.toMutableList()
        if (brands.none { it.brand == "Google Chrome" }) {
            brands.add(UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome")
                .setMajorVersion(major).setFullVersion(full).build())
        }
        WebSettingsCompat.setUserAgentMetadata(view.settings, UserAgentMetadata.Builder(metadata)
            .setBrandVersionList(brands).setFullVersion(full).build())
    }
}
