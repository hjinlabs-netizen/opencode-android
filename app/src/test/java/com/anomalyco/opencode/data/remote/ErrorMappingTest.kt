package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.error.OpenCodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
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
        val cause = IllegalStateException("programmer error")
        val error = cause.toOpenCodeError()
        assertEquals(OpenCodeError.Unexpected(cause), error)
    }

    @Test
    fun `raw IOExceptions classify as Network Connect with the exception kept as cause`() {
        val boom = IOException("unexpected end of stream")
        val error = boom.toOpenCodeError()
        assertEquals(OpenCodeError.Network(OpenCodeError.NetworkKind.Connect, boom), error)
        assertSame(boom, (error as OpenCodeError.Network).cause)
    }

    @Test
    fun `EOF and socket deaths are Network Connect while specific kinds keep precedence`() {
        val eof = EOFException("stream closed mid-body")
        val socketDeath = SocketException("Software caused connection abort")
        assertEquals(OpenCodeError.Network(OpenCodeError.NetworkKind.Connect, eof), eof.toOpenCodeError())
        assertEquals(OpenCodeError.Network(OpenCodeError.NetworkKind.Connect, socketDeath), socketDeath.toOpenCodeError())
        // IOException subclasses with dedicated kinds must NOT be swallowed by the generic rule.
        assertEquals(OpenCodeError.Network(OpenCodeError.NetworkKind.Timeout), SocketTimeoutException().toOpenCodeError())
        assertEquals(OpenCodeError.Network(OpenCodeError.NetworkKind.Dns), UnknownHostException("srv").toOpenCodeError())
        assertEquals(
            OpenCodeError.Network(OpenCodeError.NetworkKind.Tls),
            SSLException("handshake").toOpenCodeError(),
        )
        assertEquals(
            OpenCodeError.Network(OpenCodeError.NetworkKind.Connect),
            ConnectException("refused").toOpenCodeError(),
        )
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
