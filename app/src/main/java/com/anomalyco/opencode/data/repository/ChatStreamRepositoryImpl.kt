package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.stream.OpenCodeStreamClient
import com.anomalyco.opencode.di.ApplicationScope
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.StreamStatus
import com.anomalyco.opencode.domain.repository.ChatStreamRepository
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [ChatStreamRepository]: a thin domain façade over the raw
 * [OpenCodeStreamClient].
 *
 * The stream *follows the connection*: whenever a server configuration is
 * present it is opened (surviving process restarts and network drops via the
 * client's reconnect loop), and closing/clearing the connection stops it.
 * Callers never manage the socket themselves.
 */
@Singleton
class ChatStreamRepositoryImpl @Inject constructor(
    private val streamClient: OpenCodeStreamClient,
    connectionRepository: ConnectionRepository,
    @ApplicationScope scope: CoroutineScope,
) : ChatStreamRepository {

    override val events: Flow<StreamEvent> = streamClient.events
    override val status: Flow<StreamStatus> = streamClient.status

    init {
        scope.launch {
            connectionRepository.config.collect { config ->
                if (config != null) streamClient.start(config) else streamClient.stop()
            }
        }
    }

    override suspend fun reconnect() = streamClient.reconnect()
}
