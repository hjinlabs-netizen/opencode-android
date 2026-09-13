package com.anomalyco.opencode.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules of the debug HTTP-log sanitizer (Sprint L.1). */
class HttpLogSanitizerTest {

    @Test
    fun `plain request and response lines pass through untouched`() {
        assertEquals(
            "GET http://192.168.1.20:4096/session",
            HttpLogSanitizer.sanitize("GET http://192.168.1.20:4096/session"),
        )
        assertEquals(
            "END http://host:4096/global/health (200 OK)",
            HttpLogSanitizer.sanitize("END http://host:4096/global/health (200 OK)"),
        )
    }

    @Test
    fun `url query strings are redacted - server paths never reach logcat`() {
        val line = "GET http://10.0.0.5:4096/file?path=C:\\Users\\zuley\\Desktop\\secret-project\\secrets.kt"
        val safe = HttpLogSanitizer.sanitize(line)!!
        assertTrue(safe.endsWith("?<redacted>"))
        assertTrue(!safe.contains("zuley"))
        assertTrue(!safe.contains("secrets"))
    }

    @Test
    fun `any credential-bearing line is dropped entirely`() {
        assertNull(HttpLogSanitizer.sanitize("Authorization: Bearer super-secret-token"))
        assertNull(HttpLogSanitizer.sanitize("authorization: Basic YWJj"))
        assertNull(HttpLogSanitizer.sanitize("POST ...?token=abc123"))
        assertNull(HttpLogSanitizer.sanitize("password rejected for user"))
        assertNull(HttpLogSanitizer.sanitize("header api_key present"))
    }

    @Test
    fun `userinfo inside urls is redacted`() {
        val safe = HttpLogSanitizer.sanitize("GET http://admin:hunter2@192.168.1.20:4096/session")!!
        assertEquals("GET http://***@192.168.1.20:4096/session", safe)
        assertTrue(!safe.contains("hunter2"))
    }

    @Test
    fun `lines are capped at the configured maximum`() {
        val long = "x".repeat(HttpLogSanitizer.MAX_LINE + 500)
        val safe = HttpLogSanitizer.sanitize(long)!!
        assertEquals(HttpLogSanitizer.MAX_LINE + 3, safe.length)
        assertTrue(safe.endsWith("..."))
    }

    @Test
    fun `query redaction keeps the rest of a multi-url line safe`() {
        val line = "redirect http://a/x?q=1 -> http://b/y"
        val safe = HttpLogSanitizer.sanitize(line)!!
        assertEquals("redirect http://a/x?<redacted> -> http://b/y", safe)
    }
}
