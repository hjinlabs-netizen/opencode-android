package com.anomalyco.opencode.data.remote

import android.util.Log
import com.anomalyco.opencode.BuildConfig
import io.ktor.client.plugins.logging.Logger

/**
 * Pure sanitizer for Ktor `Logging` lines (Sprint L.1). Ktor's INFO level
 * already excludes headers and bodies; this is the second, independent layer
 * so a level bump or a plugin change can never leak:
 *
 *  - any line mentioning credentials (`Authorization`, `Bearer`, `token=`,
 *    `password`, API keys) is DROPPED entirely,
 *  - URL query strings are redacted (the file endpoints carry `?path=…` —
 *    private server paths),
 *  - `user:pass@` userinfo inside URLs is redacted,
 *  - lines are capped at [MAX_LINE] chars.
 *
 * Exposed as a pure function so the rules are unit-testable without
 * `android.util.Log`.
 */
object HttpLogSanitizer {

    const val MAX_LINE = 500

    private val CREDENTIAL_LINE =
        Regex("""(?i)authorization|bearer|password|token\s*=|api[_-]?key""")
    private val URL_QUERY = Regex("""(https?://[^\s"]*)\?[^\s"]*""")
    private val URL_USERINFO = Regex("""(https?://)[^\s/@"]+:[^\s/@"]+@""")

    /** @return the safe line to log, or `null` when the line must be dropped. */
    fun sanitize(line: String): String? {
        if (CREDENTIAL_LINE.containsMatchIn(line)) return null
        var out = URL_QUERY.replace(line) { match -> "${match.groupValues[1]}?<redacted>" }
        out = URL_USERINFO.replace(out) { match -> "${match.groupValues[1]}***@" }
        return if (out.length > MAX_LINE) out.take(MAX_LINE) + "..." else out
    }
}

/**
 * Debug-build-only Ktor logger writing sanitized lines to Logcat under
 * `OpenCodeHttp`. Installed by `NetworkModule` only when `BuildConfig.DEBUG`;
 * release builds never install the plugin at all.
 */
object AndroidDebugLogger : Logger {
    override fun log(message: String) {
        if (!BuildConfig.DEBUG) return
        HttpLogSanitizer.sanitize(message)?.let { Log.d(TAG, it) }
    }

    private const val TAG = "OpenCodeHttp"
}
