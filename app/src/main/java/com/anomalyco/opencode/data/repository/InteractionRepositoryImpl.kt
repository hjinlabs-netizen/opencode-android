package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.data.remote.dto.PermissionResponseRequest
import com.anomalyco.opencode.data.remote.dto.QuestionReplyRequest
import com.anomalyco.opencode.data.remote.requireActiveServer
import com.anomalyco.opencode.domain.model.PermissionDecision
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import com.anomalyco.opencode.domain.repository.InteractionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [InteractionRepository]. Server resolution goes through the shared
 * [requireActiveServer] extension (same grace and normalization as every
 * other repository); failures surface as typed errors via the shared
 * [apiCall] wrapper.
 */
@Singleton
class InteractionRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val connectionRepository: ConnectionRepository,
) : InteractionRepository {

    override suspend fun respondPermission(requestId: String, decision: PermissionDecision): Result<Unit> =
        apiCall {
            val server = connectionRepository.requireActiveServer()
            api.respondPermission(
                server.baseUrl,
                server.token,
                requestId,
                PermissionResponseRequest(response = decision.wireValue),
            )
        }

    override suspend fun respondQuestion(questionId: String, answers: List<String>): Result<Unit> =
        apiCall {
            val server = connectionRepository.requireActiveServer()
            api.respondQuestion(
                server.baseUrl,
                server.token,
                questionId,
                QuestionReplyRequest.of(answers),
            )
        }
}
