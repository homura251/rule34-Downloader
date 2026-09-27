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

        val uri = parseRule34Uri(trimmed) ?: return null
        val params = queryParams(uri)
        return params["id"]?.toLongOrNull()?.takeIf { it > 0 }
    }

    fun parseArtistTag(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.contains("://")) {
            val uri = parseRule34Uri(trimmed) ?: return null
            val tags = queryParams(uri)["tags"] ?: return null
            val parts = tags
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
            if (parts.size != 1) return null
            return normalizeArtistTag(parts.single())
        }

        return normalizeArtistTag(trimmed)
    }

    fun normalizeArtistTag(input: String): String? {
        var value = input.trim()
        if (value.startsWith("artist:", ignoreCase = true)) {
            value = value.substringAfter(':').trim()
        }
        value = value
            .replace(Regex("\\s+"), "_")
            .trim('_')

        if (value.isEmpty()) return null
        if (value.length > 200) return null
        if (value.contains('*') || value.contains(',') || value.contains("://")) return null
        if (value.startsWith("-") || value.startsWith("~")) return null
        return value
    }

    private fun parseRule34Uri(value: String): URI? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "rule34.xxx" && host != "www.rule34.xxx") return null
        return uri
    }

    private fun queryParams(uri: URI): Map<String, String> =
        uri.rawQuery
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

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
