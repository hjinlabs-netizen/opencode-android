package com.anomalyco.opencode.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.anomalyco.opencode.R
import com.anomalyco.opencode.domain.error.OpenCodeError

/**
 * Single presentation mapping from typed [OpenCodeError] kinds to localized
 * text. Every user-visible error string in the app lives in `strings.xml`
 * (en + tr) and is produced here — the data layer and ViewModels never carry
 * prose. [messageRes] is a pure function so JVM tests can assert the mapping
 * and the resource-existence checks without Compose.
 */
@StringRes
fun OpenCodeError.messageRes(): Int = when (this) {
    OpenCodeError.NoServer -> R.string.error_no_server
    OpenCodeError.AuthRejected -> R.string.error_auth_rejected
    is OpenCodeError.Network -> when (kind) {
        OpenCodeError.NetworkKind.Dns -> R.string.error_network_dns
        OpenCodeError.NetworkKind.Connect -> R.string.error_network_connect
        OpenCodeError.NetworkKind.Timeout -> R.string.error_network_timeout
        OpenCodeError.NetworkKind.Tls -> R.string.error_network_tls
        OpenCodeError.NetworkKind.Closed -> R.string.error_network_closed
    }
    is OpenCodeError.Http -> when (code) {
        404 -> R.string.error_http_not_found
        429 -> R.string.error_http_rate_limited
        else -> R.string.error_http
    }
    is OpenCodeError.EndpointMissing -> R.string.error_endpoint_missing
    is OpenCodeError.ResponseTooLarge -> R.string.error_response_too_large
    is OpenCodeError.InvalidInput -> when (reason) {
        OpenCodeError.InvalidReason.EmptyUrl -> R.string.error_invalid_url_empty
        OpenCodeError.InvalidReason.UrlScheme -> R.string.error_invalid_url_scheme
        OpenCodeError.InvalidReason.DirectoryUnresolved -> R.string.error_invalid_directory
    }
    // Server-authored text is rendered verbatim; it cannot be localized.
    is OpenCodeError.ServerNarrative -> 0
    is OpenCodeError.Unexpected -> R.string.error_unexpected
}

/** Format arguments for the resource returned by [messageRes]. */
fun OpenCodeError.messageArgs(): List<Any> = when (this) {
    is OpenCodeError.Http -> listOf(code)
    is OpenCodeError.EndpointMissing -> listOf(path, observedContentType ?: "-")
    is OpenCodeError.ResponseTooLarge -> listOf((maxBytes / (1024 * 1024)).toInt())
    else -> emptyList()
}

/** Localized user-facing text for [error]; [OpenCodeError.ServerNarrative] passes through. */
@Composable
fun stringForError(error: OpenCodeError): String = LocalContext.current.errorText(error)

/**
 * Imperative variant for `LaunchedEffect` snackbar sites (where
 * `stringResource` cannot run). Same single mapping — no screen authors its
 * own error prose.
 */
fun Context.errorText(error: OpenCodeError): String {
    val res = error.messageRes()
    return if (res == 0) {
        (error as OpenCodeError.ServerNarrative).text
    } else {
        getString(res, *error.messageArgs().toTypedArray())
    }
}
