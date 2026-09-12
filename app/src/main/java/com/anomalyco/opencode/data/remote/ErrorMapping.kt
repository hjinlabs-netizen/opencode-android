package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.error.OpenCodeException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The data layer's single exception-to-typed-error classifier. Replaces the
 * legacy string-producing error mapper: transport classes become
 * [OpenCodeError.Network] kinds, API statuses become
 * [OpenCodeError.AuthRejected] / [OpenCodeError.Http], probe misses become
 * [OpenCodeError.EndpointMissing], and everything else is preserved as
 * [OpenCodeError.Unexpected] with its cause attached for logging.
 */
internal fun Throwable.toOpenCodeError(): OpenCodeError = when (this) {
    // Covers NoServerConfiguredException and anything already classified.
    is OpenCodeException -> error
    is UnknownHostException -> OpenCodeError.Network(OpenCodeError.NetworkKind.Dns)
    is ConnectException -> OpenCodeError.Network(OpenCodeError.NetworkKind.Connect)
    is SocketTimeoutException -> OpenCodeError.Network(OpenCodeError.NetworkKind.Timeout)
    is SSLException -> OpenCodeError.Network(OpenCodeError.NetworkKind.Tls)
    is OpenCodeHttpException ->
        if (code == 401 || code == 403) {
            OpenCodeError.AuthRejected
        } else {
            OpenCodeError.Http(code, bodyText.take(MAX_BODY_SNIPPET).ifBlank { null })
        }
    is UnsupportedResponseException -> OpenCodeError.EndpointMissing(path, contentType)
    // Generic transport-level deaths (mid-stream socket resets, unexpected
    // end-of-stream) are CONNECT failures. Placed AFTER the specific
    // IOException subclasses above, which keep their own kinds.
    is IOException -> OpenCodeError.Network(OpenCodeError.NetworkKind.Connect, this)
    else -> OpenCodeError.Unexpected(this)
}

/**
 * Wraps any throwable as the single allowed failure carrier, attaching the
 * original as `cause` so stack traces and legacy `failure.cause` assertions
 * survive classification.
 */
internal fun Throwable.toOpenCodeException(): OpenCodeException =
    (this as? OpenCodeException) ?: OpenCodeException(toOpenCodeError(), this)

private const val MAX_BODY_SNIPPET = 512
