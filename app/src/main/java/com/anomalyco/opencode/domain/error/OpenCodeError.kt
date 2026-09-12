package com.anomalyco.opencode.domain.error

/**
 * Typed, closed-set description of everything that can fail while talking to
 * an OpenCode server (or while validating input for it).
 *
 * This is the SINGLE source of error semantics in the app: repositories carry
 * an [OpenCodeError] inside [OpenCodeException] within `Result.failure`, and
 * the UI layer maps the kinds to localized resources (see
 * `ui/common/ErrorUi.kt`). No user-visible string is ever authored here —
 * kinds and structured data only.
 */
sealed interface OpenCodeError {

    /** No server has been configured yet (or the stored config never loaded). */
    data object NoServer : OpenCodeError

    /** The server rejected our credentials: HTTP 401/403. */
    data object AuthRejected : OpenCodeError

    /** Transport-level classification of connection failures. */
    enum class NetworkKind {
        /** Hostname could not be resolved. */
        Dns,

        /** Host resolved but the TCP connection failed (server down? wrong port?). */
        Connect,

        /** Connect/socket timeout. */
        Timeout,

        /** TLS handshake or certificate validation failure. */
        Tls,

        /** A previously healthy stream was closed by the server; reconnect is automatic. */
        Closed,
    }

    /** The server (or its address) could not be reached at the transport level. */
    data class Network(val kind: NetworkKind) : OpenCodeError

    /**
     * Any non-2xx HTTP response that is not credential rejection. [code] is
     * surfaced verbatim in the localized text; [bodySnippet] (truncated by
     * the data layer) is debug detail only — never rendered as the message.
     */
    data class Http(val code: Int, val bodySnippet: String? = null) : OpenCodeError

    /**
     * The endpoint does not exist on this server build or answered with a
     * non-JSON payload (typically the SPA `index.html` fallback). Raised by
     * the endpoint probe chains once every candidate misses.
     */
    data class EndpointMissing(val path: String, val observedContentType: String? = null) : OpenCodeError

    /** Why client-side input validation rejected the user's entry. */
    enum class InvalidReason {
        /** The server address field was empty. */
        EmptyUrl,

        /** The address lacked an http:// or https:// scheme. */
        UrlScheme,

        /** A working directory could not be probed/validated server-side. */
        DirectoryUnresolved,
    }

    /** The user's own input was rejected before any network call. */
    data class InvalidInput(val reason: InvalidReason) : OpenCodeError

    /**
     * Free-form text authored by the SERVER (e.g. `session.error` payloads).
     * Deliberately rendered as-is: it cannot be localized and is the most
     * useful diagnostic the user can get for agent-side failures.
     */
    data class ServerNarrative(val text: String) : OpenCodeError

    /**
     * Anything unmapped. [cause] is retained for logging only; the UI shows
     * the generic unexpected-failure text instead of echoing the raw message.
     */
    data class Unexpected(val cause: Throwable? = null) : OpenCodeError
}
