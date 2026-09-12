package com.anomalyco.opencode.domain.error

/**
 * The ONLY throwable the data layer is allowed to place into
 * `Result.failure` (enforced via the shared `apiCall` wrapper). ViewModels
 * read [error] through [toDisplayError] and never touch [getMessage], so the
 * message below stays a developer/debug hint (English, never localized,
 * never rendered).
 */
open class OpenCodeException(
    val error: OpenCodeError,
    cause: Throwable? = null,
) : Exception(error.debugHint(), cause)

/**
 * UI-side extraction of the typed error from a repository failure. Repos
 * already wrap everything in [OpenCodeException]; the [OpenCodeError.Unexpected]
 * fallback keeps ViewModels total even against fakes or programmer errors.
 */
fun Throwable.toDisplayError(): OpenCodeError =
    (this as? OpenCodeException)?.error ?: OpenCodeError.Unexpected(this)

/** English developer hint for logs/exceptions. Not user-facing text. */
private fun OpenCodeError.debugHint(): String = when (this) {
    OpenCodeError.NoServer -> "no server configured"
    OpenCodeError.AuthRejected -> "authentication rejected"
    is OpenCodeError.Network -> "network failure: $kind"
    is OpenCodeError.Http -> "server returned HTTP $code"
    is OpenCodeError.EndpointMissing -> "endpoint unavailable: $path"
    is OpenCodeError.InvalidInput -> "invalid input: $reason"
    is OpenCodeError.ServerNarrative -> "server said: ${text.take(200)}"
    is OpenCodeError.Unexpected -> cause?.message ?: "unexpected error"
}
