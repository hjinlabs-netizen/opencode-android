package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.error.OpenCodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Table-driven coverage of the exception → typed-error classifier. */
class ErrorMappingTest {

    @Test
    fun `transport exceptions map to network kinds`() {
        assertEquals(
            OpenCodeError.Network(OpenCodeError.NetworkKind.Dns),
            UnknownHostException("srv").toOpenCodeError(),
        )
        assertEquals(
            OpenCodeError.Network(OpenCodeError.NetworkKind.Connect),
            ConnectException().toOpenCodeError(),
        )
        assertEquals(
            OpenCodeError.Network(OpenCodeError.NetworkKind.Timeout),
            SocketTimeoutException().toOpenCodeError(),
        )
        assertEquals(
            OpenCodeError.Network(OpenCodeError.NetworkKind.Tls),
            SSLException("cert").toOpenCodeError(),
        )
    }

    @Test
    fun `auth statuses collapse to AuthRejected and other codes stay typed Http`() {
        assertEquals(
            OpenCodeError.AuthRejected,
            OpenCodeHttpException(401, "nope").toOpenCodeError(),
        )
        assertEquals(
            OpenCodeError.AuthRejected,
            OpenCodeHttpException(403, "").toOpenCodeError(),
        )
        assertEquals(
            OpenCodeError.Http(500, null),
            OpenCodeHttpException(500, "").toOpenCodeError(),
        )
        assertEquals(
            OpenCodeError.Http(404, "not found"),
            OpenCodeHttpException(404, "not found").toOpenCodeError(),
        )
        assertEquals(
            OpenCodeError.Http(429, null),
            OpenCodeHttpException(429, "   ").toOpenCodeError(),
        )
    }

    @Test
    fun `http body snippets are capped so huge error pages never ride into memory`() {
        val huge = "x".repeat(4096)
        val error = OpenCodeHttpException(502, huge).toOpenCodeError()
        assertEquals(512, (error as OpenCodeError.Http).bodySnippet?.length)
    }

    @Test
    fun `probe misses map to EndpointMissing with their diagnostics`() {
        assertEquals(
            OpenCodeError.EndpointMissing("/fs/list", "text/html"),
            UnsupportedResponseException("/fs/list", "text/html").toOpenCodeError(),
        )
    }

    @Test
    fun `no-server is carried as the typed NoServer error`() {
        assertEquals(OpenCodeError.NoServer, NoServerConfiguredException().toOpenCodeError())
    }

    @Test
    fun `unknown throwables are preserved as Unexpected with the cause attached`() {
        val cause = IOException("boom")
        val error = cause.toOpenCodeError()
        assertEquals(OpenCodeError.Unexpected(cause), error)
    }

    @Test
    fun `already-classified exceptions pass through unwrapped`() {
        val original = OpenCodeException(OpenCodeError.AuthRejected)
        assertSame(original, original.toOpenCodeException())
        assertSame(original.error, original.toOpenCodeError())
    }

    @Test
    fun `wrapping attaches the original as cause for logging`() {
        val cause = ConnectException("refused")
        val wrapped = cause.toOpenCodeException()
        assertEquals(OpenCodeError.Network(OpenCodeError.NetworkKind.Connect), wrapped.error)
        assertSame(cause, wrapped.cause)
    }
}
