package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.NoServerConfiguredException
import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.data.remote.dto.PermissionResponseRequest
import com.anomalyco.opencode.data.remote.dto.QuestionReplyRequest
import com.anomalyco.opencode.data.remote.toFriendlyApiException
import com.anomalyco.opencode.domain.model.PermissionDecision
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import com.anomalyco.opencode.domain.repository.InteractionRepository
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [InteractionRepository]. Resolves the active server from the
 * persisted [ConnectionRepository] (giving the encrypted store's async load a
 * short grace period) and posts the resolution; transport/API failures map to
 * friendly errors like everywhere else in the data layer.
 */
@Singleton
class InteractionRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val connectionRepository: ConnectionRepository,
) : InteractionRepository {

    override suspend fun respondPermission(requestId: String, decision: PermissionDecision): Result<Unit> =
        guarded {
            val server = requireServer()
            api.respondPermission(
                server.baseUrl,
                server.token,
                requestId,
                PermissionResponseRequest(response = decision.wireValue),
            )
        }

    override suspend fun respondQuestion(questionId: String, answers: List<String>): Result<Unit> =
        guarded {
            val server = requireServer()
            api.respondQuestion(
                server.baseUrl,
                server.token,
                questionId,
                QuestionReplyRequest.of(answers),
            )
        }

    private suspend fun requireServer(): ServerConfig =
        withTimeoutOrNull(SERVER_RESOLVE_TIMEOUT_MS) {
            connectionRepository.config.filterNotNull().first()
        }?.let { it.copy(baseUrl = it.normalizedUrl) }
            ?: throw NoServerConfiguredException()

    private suspend fun guarded(block: suspend () -> Unit): Result<Unit> =
        runCatching { block() }
            .recoverCatching { throw it.toFriendlyApiException() }

    private companion object {
        const val SERVER_RESOLVE_TIMEOUT_MS = 3_000L
    }
}
