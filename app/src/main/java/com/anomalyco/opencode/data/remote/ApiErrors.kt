package com.anomalyco.opencode.data.remote

import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.error.OpenCodeException

/**
 * Raised when a repository call needs a server but none is configured.
 * Carries the typed [OpenCodeError.NoServer] directly — user-facing text is
 * produced by the UI error mapper (`ui/common/ErrorUi.kt`), never here.
 */
class NoServerConfiguredException : OpenCodeException(OpenCodeError.NoServer)
