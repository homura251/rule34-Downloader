package com.homura251.rule34downloader.domain

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object RetryAfter {
    fun millis(value: String?, now: Long = System.currentTimeMillis()): Long? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        text.toLongOrNull()?.let { seconds ->
            return runCatching { Math.multiplyExact(seconds.coerceAtLeast(0), 1000L) }.getOrNull()
        }
        return runCatching {
            (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0)
        }.getOrNull()
    }
}
