package com.homura251.rule34downloader.domain

import org.junit.Assert.*
import org.junit.Test

class ApiCredentialsTest {
    @Test fun diagnosticTextNeverContainsTheApiKey() {
        val credentials = ApiCredentials("42", "private-test-key")
        assertFalse(credentials.toString().contains(credentials.apiKey))
        assertFalse(credentials.copy(userId = "43").toString().contains(credentials.apiKey))
    }
}
