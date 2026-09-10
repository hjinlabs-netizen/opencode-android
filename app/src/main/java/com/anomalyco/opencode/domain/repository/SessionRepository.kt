package com.anomalyco.opencode.domain.repository

import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.Session
import com.anomalyco.opencode.domain.model.SessionSummary
import kotlinx.coroutines.flow.Flow

/**
 * Sessions and their message history, backed by the OpenCode HTTP API.
 *
 * Implementations keep an in-memory cache of the last known session list so
 * UI observers see instant updates after [refreshSessions] / [createSession]
 * without a round-trip. All network operations are wrapped in [Result].
 */
interface SessionRepository {

    /** Cached session list (most recently updated first). Never errors. */
    val sessions: Flow<List<SessionSummary>>

    /** Pull the session list from the server and update the cache. */
    suspend fun refreshSessions(): Result<List<SessionSummary>>

    /** Create a session on the server; optionally naming the driving agent. */
    suspend fun createSession(agent: String? = null, title: String? = null): Result<Session>

    /** Fetch a single session's details. */
    suspend fun getSession(sessionId: String): Result<Session>

    /** Fetch the full message history (with parts) of a session. */
    suspend fun loadMessages(sessionId: String): Result<List<ChatMessage>>

    /**
     * Send a user prompt to [sessionId]. The server responds with the created
     * user message; assistant output then arrives via the event stream.
     * [agent] optionally selects the driving agent (e.g. "build" or "plan").
     */
    suspend fun sendPrompt(sessionId: String, text: String, agent: String? = null): Result<ChatMessage>
}
