package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.NoServerConfiguredException
import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.data.remote.dto.CreateSessionRequest
import com.anomalyco.opencode.data.remote.dto.MessageDto
import com.anomalyco.opencode.data.remote.dto.PartInputDto
import com.anomalyco.opencode.data.remote.dto.SendMessageRequest
import com.anomalyco.opencode.data.remote.dto.SessionDto
import com.anomalyco.opencode.data.remote.toFriendlyApiException
import com.anomalyco.opencode.data.settings.SecureSettingsStore
import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.model.Session
import com.anomalyco.opencode.domain.model.SessionSummary
import com.anomalyco.opencode.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [SessionRepository]. Talks to the OpenCode HTTP API and keeps a
 * small in-memory cache of the session list so observers (and future Compose
 * screens) get instant updates after every mutation.
 *
 * The active server is resolved synchronously from the encrypted settings
 * store; every failure is mapped to a friendly [Result.failure].
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val settings: SecureSettingsStore,
) : SessionRepository {

    private val cacheMutex = Mutex()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    override val sessions: Flow<List<SessionSummary>> = _sessions.asStateFlow()

    override suspend fun refreshSessions(): Result<List<SessionSummary>> = guarded {
        val server = requireServer()
        val fresh = api.listSessions(server.baseUrl, server.token)
            .map(SessionDto::toSummary)
            .sortedByDescending(SessionSummary::updatedAt)
        _sessions.value = fresh
        fresh
    }

    override suspend fun createSession(agent: String?, title: String?): Result<Session> = guarded {
        val server = requireServer()
        val created = api.createSession(
            server.baseUrl,
            server.token,
            CreateSessionRequest(title = title, agent = agent),
        ).toDomain()
        cacheSession(created)
        created
    }

    override suspend fun getSession(sessionId: String): Result<Session> = guarded {
        val server = requireServer()
        api.getSession(server.baseUrl, server.token, sessionId)
            .toDomain()
            .also(::cacheSession)
    }

    override suspend fun loadMessages(sessionId: String): Result<List<ChatMessage>> = guarded {
        val server = requireServer()
        api.listMessages(server.baseUrl, server.token, sessionId)
            .map(MessageDto::toDomain)
    }

    override suspend fun sendPrompt(sessionId: String, text: String, agent: String?): Result<ChatMessage> = guarded {
        val server = requireServer()
        api.sendPrompt(
            server.baseUrl,
            server.token,
            sessionId,
            SendMessageRequest(parts = listOf(PartInputDto(text = text)), agent = agent),
        ).toDomain()
    }

    private fun requireServer(): ServerConfig =
        settings.current?.let { it.copy(baseUrl = it.normalizedUrl) }
            ?: throw NoServerConfiguredException()

    private fun cacheSession(session: Session) {
        _sessions.update { list ->
            (list.filterNot { it.id == session.id } + session.toSummary())
                .sortedByDescending(SessionSummary::updatedAt)
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
        cacheMutex.withLock {
            runCatching { block() }
                .recoverCatching { throw it.toFriendlyApiException() }
        }
}
