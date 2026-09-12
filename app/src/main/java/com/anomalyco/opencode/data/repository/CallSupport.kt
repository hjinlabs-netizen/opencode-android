package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.toOpenCodeException
import com.anomalyco.opencode.domain.error.OpenCodeException

/**
 * The shared `Result` wrapper for every remote/repository call: runs [block],
 * and guarantees that a failure is exactly an [OpenCodeException] carrying a
 * typed error. Replaces the per-repository `guarded` / `safe` copies of this
 * logic; repositories keep their `Result<T>` contracts unchanged.
 */
internal suspend fun <T> apiCall(block: suspend () -> T): Result<T> =
    runCatching { block() }.recoverCatching { throw it.toOpenCodeException() }
