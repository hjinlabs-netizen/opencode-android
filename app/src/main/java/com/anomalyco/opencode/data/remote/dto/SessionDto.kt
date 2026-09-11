package com.anomalyco.opencode.data.remote.dto

import com.anomalyco.opencode.domain.model.Session
import com.anomalyco.opencode.domain.model.SessionSummary
import kotlinx.serialization.Serializable

/**
 * Wire representation of an OpenCode session.
 *
 * Every field except [id] is optional on purpose: the server evolves quickly
 * (projectID, directory, cost, tokens, share, permission ...), and unknown
 * keys are already ignored by the shared `Json`. Unknown/missing values fall
 * back to safe defaults instead of throwing.
 */
@Serializable
data class SessionDto(
    val id: String = "",
    val title: String? = null,
    val agent: String? = null,
    val directory: String? = null,
    val projectID: String? = null,
    val time: TimeDto? = null,
    /** Fallbacks for builds that expose flat timestamps. */
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val version: String? = null,
) {
    @Serializable
    data class TimeDto(
        val created: Long? = null,
        val updated: Long? = null,
    )

    private fun createdMillis(): Long = time?.created ?: createdAt ?: 0L

    private fun updatedMillis(): Long = time?.updated ?: updatedAt ?: createdMillis()

    fun toDomain(): Session = Session(
        id = id,
        title = title.orEmpty(),
        agent = agent,
        directory = directory,
        createdAt = createdMillis(),
        updatedAt = updatedMillis(),
    )

    fun toSummary(): SessionSummary = toDomain().toSummary()
}

/**
 * Request body for `POST /session`. All fields optional server-side; the
 * shared Json omits nulls (`explicitNulls = false`) so a quick-create sends
 * a bare `{}` and a directory-scoped create sends `{"directory": "..."}`.
 */
@Serializable
data class CreateSessionRequest(
    val title: String? = null,
    val agent: String? = null,
    val directory: String? = null,
)
