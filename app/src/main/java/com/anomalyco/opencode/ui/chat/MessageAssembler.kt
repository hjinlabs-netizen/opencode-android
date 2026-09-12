package com.anomalyco.opencode.ui.chat

import com.anomalyco.opencode.domain.model.ChatMessage
import com.anomalyco.opencode.domain.model.MessagePart
import com.anomalyco.opencode.domain.model.MessageRole
import com.anomalyco.opencode.domain.model.StepState
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.ToolStatus

/**
 * Pure projection of the streaming event feed onto the visible message list.
 *
 * While the assistant is answering, events are merged into a single synthetic
 * "live" message keyed by [liveMessageId]:
 *  - text/reasoning deltas append to (or create) the part matching `partID`;
 *  - tool events upsert the part matching `callID`;
 *  - step events upsert the part matching `stepID`.
 *
 * When the user sends the next prompt the live message is "rotated" to a
 * stable id ([rotateLiveMessage]) so a fresh live bubble starts, and a later
 * `loadMessages` refresh reconciles the transcript against the server truth.
 * Object-level unit-testable — no Android, coroutines or flows involved.
 */
object MessageAssembler {

    fun liveMessageId(sessionId: String) = "stream-live:$sessionId"

    fun apply(messages: List<ChatMessage>, sessionId: String, event: StreamEvent): List<ChatMessage> =
        when (event) {
            is StreamEvent.TextDelta -> withLive(messages, sessionId) { live ->
                val index = mergeTargetIndex(
                    parts = live.parts,
                    explicitId = event.partId,
                    matches = { it is MessagePart.TextPart },
                )
                val parts = if (index == -1) {
                    live.parts + MessagePart.TextPart(content = event.delta, id = event.partId)
                } else {
                    live.parts.replaceAt(index) { part ->
                        val text = part as MessagePart.TextPart
                        text.copy(content = text.content + event.delta)
                    }
                }
                live.copy(parts = parts)
            }

            is StreamEvent.ReasoningDelta -> withLive(messages, sessionId) { live ->
                val index = mergeTargetIndex(
                    parts = live.parts,
                    explicitId = event.partId,
                    matches = { it is MessagePart.ReasoningPart },
                )
                val parts = if (index == -1) {
                    live.parts + MessagePart.ReasoningPart(thinking = event.delta, id = event.partId)
                } else {
                    live.parts.replaceAt(index) { part ->
                        val reasoning = part as MessagePart.ReasoningPart
                        reasoning.copy(thinking = reasoning.thinking + event.delta)
                    }
                }
                live.copy(parts = parts)
            }

            is StreamEvent.ToolCalled -> withLive(messages, sessionId) { live ->
                val parts = live.parts.replaceOrAppend(
                    match = { it is MessagePart.ToolCallPart && it.callId == event.callId },
                    replacement = MessagePart.ToolCallPart(
                        callId = event.callId,
                        toolName = event.toolName,
                        args = event.args,
                        status = ToolStatus.RUNNING,
                        argsTruncated = event.argsTruncated,
                    ),
                )
                live.copy(parts = parts)
            }

            is StreamEvent.ToolUpdated -> withLive(messages, sessionId) { live ->
                val parts = live.parts.replaceOrAppend(
                    match = { it is MessagePart.ToolCallPart && it.callId == event.callId },
                    replacement = MessagePart.ToolCallPart(
                        callId = event.callId,
                        toolName = event.toolName.orEmpty(),
                        status = event.status,
                    ),
                )
                live.copy(parts = parts)
            }

            is StreamEvent.ToolFinished -> withLive(messages, sessionId) { live ->
                val index = live.parts.indexOfFirst { it is MessagePart.ToolCallPart && it.callId == event.callId }
                val parts = if (index == -1) {
                    live.parts + MessagePart.ToolCallPart(
                        callId = event.callId,
                        status = event.status,
                    )
                } else {
                    live.parts.replaceAt(index) { old ->
                        (old as MessagePart.ToolCallPart).copy(status = event.status)
                    }
                }
                live.copy(parts = parts)
            }

            is StreamEvent.StepStarted -> withLive(messages, sessionId) { live ->
                val parts = live.parts.replaceOrAppend(
                    match = { it is MessagePart.StepPart && it.stepId == event.stepId },
                    replacement = MessagePart.StepPart(
                        stepId = event.stepId,
                        description = event.description,
                        state = StepState.STARTED,
                    ),
                )
                live.copy(parts = parts)
            }

            is StreamEvent.StepFinished -> withLive(messages, sessionId) { live ->
                val parts = live.parts.replaceOrAppend(
                    match = { it is MessagePart.StepPart && it.stepId == event.stepId },
                    replacement = MessagePart.StepPart(
                        stepId = event.stepId,
                        description = event.description,
                        state = StepState.COMPLETED,
                    ),
                )
                live.copy(parts = parts)
            }

            // The assistant turn ended; close out reasoning bubbles.
            is StreamEvent.SessionIdle -> withLive(messages, sessionId) { live ->
                live.copy(
                    parts = live.parts.map { part ->
                        if (part is MessagePart.ReasoningPart) part.copy(isFinished = true) else part
                    },
                )
            }

            // History refresh / diagnostics are ViewModel concerns.
            is StreamEvent.MessageUpdated,
            is StreamEvent.SessionError,
            is StreamEvent.PermissionAsked,
            is StreamEvent.QuestionAsked,
            is StreamEvent.Unknown,
            -> messages
        }

    /**
     * Gives the synthetic live message a stable id so the next events open a
     * fresh bubble (called right before sending a new prompt).
     */
    fun rotateLiveMessage(
        messages: List<ChatMessage>,
        sessionId: String,
        newId: String,
    ): List<ChatMessage> = messages.map { message ->
        if (message.id == liveMessageId(sessionId)) message.copy(id = newId) else message
    }

    // ---- helpers ----

    /**
     * Which existing part a delta should merge into (index), or -1 to append
     * a new part.
     *
     * P0-4: servers that omit `partID` used to collapse every blank-keyed
     * delta into one `id = ""` part — corrupting unrelated parts. Without a
     * stable key the only deterministic target is the *trailing* part of the
     * same kind (a per-message tail buffer): continuation keeps working and
     * a later keyed part still opens its own bubble.
     */
    private fun mergeTargetIndex(
        parts: List<MessagePart>,
        explicitId: String,
        matches: (MessagePart) -> Boolean,
    ): Int = if (explicitId.isNotBlank()) {
        parts.indexOfFirst { matches(it) && partIdOf(it) == explicitId }
    } else {
        parts.indexOfLast(matches)
    }

    private fun partIdOf(part: MessagePart): String = when (part) {
        is MessagePart.TextPart -> part.id
        is MessagePart.ReasoningPart -> part.id
        else -> ""
    }

    private inline fun withLive(
        messages: List<ChatMessage>,
        sessionId: String,
        transform: (ChatMessage) -> ChatMessage,
    ): List<ChatMessage> {
        val id = liveMessageId(sessionId)
        val index = messages.indexOfFirst { it.id == id }
        if (index == -1) {
            val fresh = transform(ChatMessage(id = id, sessionId = sessionId, role = MessageRole.ASSISTANT))
            return messages + fresh
        }
        return messages.replaceAt(index, transform)
    }

    private inline fun <T> List<T>.replaceAt(index: Int, transform: (T) -> T): List<T> =
        toMutableList().also { it[index] = transform(it[index]) }

    private fun <T> List<T>.replaceOrAppend(match: (T) -> Boolean, replacement: T): List<T> {
        val index = indexOfFirst(match)
        return if (index == -1) this + replacement else replaceAt(index) { replacement }
    }
}
