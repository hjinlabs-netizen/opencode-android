package com.anomalyco.opencode.domain.repository

import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.StreamStatus
import kotlinx.coroutines.flow.Flow

/**
 * Live event feed of the connected OpenCode server.
 *
 * The stream follows the persisted server configuration automatically:
 * connecting a server opens it, clearing the configuration closes it, and a
 * dropped connection reconnects with exponential backoff. Late subscribers
 * do NOT receive buffered history — fetch it from [SessionRepository] first.
 */
interface ChatStreamRepository {

    /** Decoded server events. Hot shared flow; order per connection is preserved. */
    val events: Flow<StreamEvent>

    /** Current connection status of the underlying stream client. */
    val status: Flow<StreamStatus>

    /** Force an immediate reconnect attempt (resets the backoff counter). */
    suspend fun reconnect()
}
