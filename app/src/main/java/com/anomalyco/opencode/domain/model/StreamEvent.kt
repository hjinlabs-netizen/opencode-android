package com.anomalyco.opencode.domain.model

/**
 * Domain-level events decoded from the server's streaming event feed
 * (`session.next.text.delta`, `session.next.tool.*`, `session.next.step.*`, ...).
 *
 * Deliberately NOT @Serializable: the wire shape lives in
 * `data/remote/dto/StreamEventDto.kt` and is translated by
 * `data/remote/stream/StreamEventDecoder`. Unknown server event kinds decode
 * to [StreamEvent.Unknown] so newer servers never break this client.
 */
sealed interface StreamEvent {

    /** Incremental text appended to a message part. */
    data class TextDelta(
        val sessionId: String,
        val partId: String,
        val delta: String,
    ) : StreamEvent

    /** Incremental reasoning ("thinking") appended to a part. */
    data class ReasoningDelta(
        val sessionId: String,
        val partId: String,
        val delta: String,
    ) : StreamEvent

    /** A tool invocation was announced with its resolved arguments. */
    data class ToolCalled(
        val sessionId: String,
        val callId: String,
        val toolName: String,
        val args: String,
    ) : StreamEvent

    /** A running tool reported progress / partial output. */
    data class ToolUpdated(
        val sessionId: String,
        val callId: String,
        val toolName: String?,
        val status: ToolStatus,
        val output: String?,
    ) : StreamEvent

    /** A tool invocation reached a terminal state. */
    data class ToolFinished(
        val sessionId: String,
        val callId: String,
        val status: ToolStatus,
        val output: String?,
    ) : StreamEvent

    /** The agent began a new step. */
    data class StepStarted(
        val sessionId: String,
        val stepId: String,
        val description: String,
    ) : StreamEvent

    /** The agent ended a step. */
    data class StepFinished(
        val sessionId: String,
        val stepId: String,
        val description: String,
    ) : StreamEvent

    /** A message gained/changed metadata; listeners should refresh it. */
    data class MessageUpdated(
        val sessionId: String,
        val messageId: String,
    ) : StreamEvent

    /** The session finished all pending work. */
    data class SessionIdle(val sessionId: String) : StreamEvent

    /** The session errored out. */
    data class SessionError(
        val sessionId: String?,
        val message: String,
    ) : StreamEvent

    /** Fallback for event types this client build does not model. */
    data class Unknown(val type: String) : StreamEvent
}

/** Connection lifecycle of the streaming feed surfaced to the UI. */
sealed interface StreamStatus {
    /** No stream is active and none is being attempted. */
    data object Disconnected : StreamStatus

    /** Opening the stream (first attempt or a reconnect in progress). */
    data object Connecting : StreamStatus

    /** Stream is open; at least one frame has been received. */
    data object Connected : StreamStatus

    /** The stream dropped; a reconnect with backoff will follow. */
    data class Error(val message: String) : StreamStatus
}
