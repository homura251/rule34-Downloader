package com.homura251.rule34downloader.network

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object PostUrlParser {
    fun parsePostId(input: String): Long? {
        val trimmed = input.trim()
        if (trimmed.matches(Regex("^[0-9]+$"))) {
            return trimmed.toLongOrNull()?.takeIf { it > 0 }
        }

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "rule34.xxx" && host != "www.rule34.xxx") return null

        val params = uri.rawQuery
            ?.split('&')
            ?.mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = decode(pair.substring(0, separator))
                val value = decode(pair.substring(separator + 1))
                key to value
            }
            .orEmpty()
            .toMap()

        return params["id"]?.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
