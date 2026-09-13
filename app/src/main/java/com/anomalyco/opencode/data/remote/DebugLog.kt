package com.anomalyco.opencode.data.remote

import android.util.Log
import com.anomalyco.opencode.BuildConfig

/**
 * Debug-only structured breadcrumbs for the SSE stream supervisor
 * (Sprint L.2): connection lifecycle, reconnect attempts and probe-winner
 * changes. Messages carry NO URLs, tokens or paths beyond the endpoint name —
 * the whole point of the lane is that field debugging never leaks anything
 * the user did not type into the server URL bar.
 */
object DebugLog {

    fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private const val TAG = "OpenCodeSse"
}
