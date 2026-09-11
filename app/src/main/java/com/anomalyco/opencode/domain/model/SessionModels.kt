package com.anomalyco.opencode.domain.model

import kotlinx.serialization.Serializable

/**
 * Full session object as understood by the domain.
 *
 * Timestamps are epoch milliseconds (the OpenCode server uses `time.created`
 * / `time.updated`; the DTO layer normalises them into these flat fields).
 */
@Serializable
data class Session(
    val id: String,
    val title: String = "",
    val agent: String? = null,
    /** Server-side working directory this session operates in (Phase 5). */
    val directory: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** Cheap projection for list screens. */
    fun toSummary() = SessionSummary(
        id = id,
        title = title,
        agent = agent,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/**
 * Lightweight session descriptor used in list responses (`GET /session`).
 * Mirrors [Session] minus any heavy fields (cost, tokens, messages).
 */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String = "",
    val agent: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** Author of a [ChatMessage]. */
@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    ;

    companion object {
        /** Tolerant wire mapping: unknown or missing roles default to USER. */
        fun fromWire(value: String?): MessageRole = when (value?.lowercase()) {
            "assistant" -> ASSISTANT
            "system" -> SYSTEM
            else -> USER
        }
    }
}

/**
 * A single conversation turn: an [info][MessageRole]-tagged envelope plus its
 * ordered [MessagePart]s (text, reasoning, tool calls, ...).
 */
@Serializable
data class ChatMessage(
    val id: String,
    val sessionId: String = "",
    val role: MessageRole = MessageRole.USER,
    val parts: List<MessagePart> = emptyList(),
    val createdAt: Long = 0L,
)
