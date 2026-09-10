package com.anomalyco.opencode.data.remote.dto

import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.domain.model.MessageRole
import com.anomalyco.opencode.domain.model.StepState
import com.anomalyco.opencode.domain.model.ToolStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A message "envelope + parts" pair, matching the `GET /session/{id}/message`
 * and `POST /session/{id}/message` response shape:
 * `{"info": {...}, "parts": [...]}`.
 */
@Serializable
data class MessageDto(
    val info: MessageInfoDto = MessageInfoDto(),
    val parts: List<PartDto> = emptyList(),
) {
    fun toDomain(): ChatMessage = ChatMessage(
        id = info.id,
        sessionId = info.sessionID.orEmpty(),
        role = MessageRole.fromWire(info.role),
        parts = parts.mapNotNull { it.toDomain() },
        createdAt = info.time?.created ?: 0L,
    )
}

@Serializable
data class MessageInfoDto(
    val id: String = "",
    val sessionID: String? = null,
    val role: String? = null,
    val time: TimeDto? = null,
    val agent: String? = null,
) {
    @Serializable
    data class TimeDto(val created: Long? = null, val completed: Long? = null)
}

/**
 * Tolerant union of every server message-part shape. Which fields are present
 * depends on [type]; anything unrecognised maps to `null` and is dropped by
 * [toDomain] consumers rather than crashing the whole history load.
 */
@Serializable
data class PartDto(
    val id: String = "",
    val type: String = "",
    val sessionID: String? = null,
    val messageID: String? = null,
    /** text / reasoning payloads. */
    val text: String? = null,
    val thinking: String? = null,
    /** tool payloads. */
    val callID: String? = null,
    val tool: String? = null,
    val state: ToolStateDto? = null,
    /** shell payloads. */
    val command: String? = null,
    val output: String? = null,
    val exit: Int? = null,
    /** step payloads. */
    val title: String? = null,
) {
    @Serializable
    data class ToolStateDto(
        val status: String? = null,
        val input: JsonObject? = null,
        val output: String? = null,
        val error: String? = null,
        val title: String? = null,
        val metadata: JsonObject? = null,
    )

    fun toDomain(): MessagePart? = when (type) {
        "text" -> MessagePart.TextPart(content = text.orEmpty(), id = id)

        "reasoning" -> MessagePart.ReasoningPart(
            thinking = (thinking ?: text).orEmpty(),
            // Parts in a completed history payload are finished by definition.
            isFinished = true,
            id = id,
        )

        "tool" -> {
            val toolName = tool.orEmpty()
            val state = state
            val output = state?.output ?: state?.error
            if (toolName.equals("bash", ignoreCase = true) || toolName.equals("shell", ignoreCase = true)) {
                MessagePart.ShellPart(
                    command = state?.input?.optString("command").orEmpty().ifEmpty { command.orEmpty() },
                    output = output.orEmpty(),
                    exitCode = state?.metadata?.optInt("exit") ?: exit,
                )
            } else {
                MessagePart.ToolCallPart(
                    callId = callID ?: id,
                    toolName = toolName,
                    args = state?.input?.toString() ?: "{}",
                    status = ToolStatus.fromWire(state?.status),
                )
            }
        }

        "shell" -> MessagePart.ShellPart(
            command = command.orEmpty(),
            output = output.orEmpty(),
            exitCode = exit,
        )

        "step-start", "step" -> MessagePart.StepPart(
            stepId = id,
            description = title.orEmpty(),
            state = StepState.STARTED,
        )

        "step-finish" -> MessagePart.StepPart(
            stepId = id,
            description = title.orEmpty(),
            state = StepState.COMPLETED,
        )

        // "file", "patch", "todo", future kinds ... -> not modelled yet.
        else -> null
    }
}

/** Request body for `POST /session/{id}/message`. */
@Serializable
data class SendMessageRequest(
    val parts: List<PartInputDto>,
    val agent: String? = null,
)

/** One input part; currently the client only ever sends plain text. */
@Serializable
data class PartInputDto(
    val type: String = "text",
    val text: String,
)

private fun JsonObject.optString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.optInt(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull
