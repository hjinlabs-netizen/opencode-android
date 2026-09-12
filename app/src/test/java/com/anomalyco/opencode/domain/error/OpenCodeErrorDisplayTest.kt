package com.anomalyco.opencode.domain.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the UI-side typed-error extraction helper. */
class OpenCodeErrorDisplayTest {

    @Test
    fun `toDisplayError reads the typed error off the carrier`() {
        val error = OpenCodeError.EndpointMissing("/fs/list", "text/html")
        assertEquals(error, OpenCodeException(error).toDisplayError())
    }

    @Test
    fun `foreign throwables degrade to Unexpected keeping the cause for logs`() {
        val cause = RuntimeException("unexpected programmer error")
        val displayed = cause.toDisplayError()
        assertTrue(displayed is OpenCodeError.Unexpected)
        assertEquals(cause, (displayed as OpenCodeError.Unexpected).cause)
    }

    @Test
    fun `carrier message stays an English debug hint, never user text`() {
        val message = OpenCodeException(OpenCodeError.Http(503, "down")).message.orEmpty()
        assertEquals("server returned HTTP 503", message)
    }
}
