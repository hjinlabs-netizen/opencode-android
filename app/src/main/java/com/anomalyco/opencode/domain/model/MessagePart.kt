package com.anomalyco.opencode.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Polymorphic building blocks of a chat message.
 *
 * Serialised as a kotlinx sealed hierarchy using the default `type` class
 * discriminator (e.g. `{"type":"text","content":"hi"}`). The shared app [Json]
 * sets `ignoreUnknownKeys = true`, so new part kinds added by future server
 * versions never crash parsing of the parts we do know.
 *
 * The wire formats of the OpenCode server differ per version; the DTO layer
 * (`data/remote/dto`) adapts them onto these clean domain shapes.
 */
@Serializable
sealed interface MessagePart {

    /** Plain assistant/user text. */
    @Serializable
    @SerialName("text")
    data class TextPart(
        val content: String = "",
        /** Server part id; stream deltas are merged by this key. */
        val id: String = "",
        /** True when history [content] was cut at the memory limit (M.5). */
        val contentTruncated: Boolean = false,
    ) : MessagePart

    /** Model chain-of-thought ("thinking") streamed for a message. */
    @Serializable
    @SerialName("reasoning")
    data class ReasoningPart(
        val thinking: String = "",
        val isFinished: Boolean = false,
        val id: String = "",
        /** True when history [thinking] was cut at the memory limit (M.5). */
        val thinkingTruncated: Boolean = false,
    ) : MessagePart

    /** A non-shell tool invocation (edit, write, webfetch, ...). */
    @Serializable
    @SerialName("tool")
    data class ToolCallPart(
        val callId: String = "",
        val toolName: String = "",
        /** Raw JSON string of the tool input arguments. */
        val args: String = "",
        val status: ToolStatus = ToolStatus.PENDING,
        /** True when [args] was cut at the memory limit at decode time. */
        val argsTruncated: Boolean = false,
    ) : MessagePart

    /** A shell/bash command execution with its captured output. */
    @Serializable
    @SerialName("shell")
    data class ShellPart(
        val command: String = "",
        val output: String = "",
        /** `null` until the process terminates. */
        val exitCode: Int? = null,
        /** True when [output] was cut at the memory limit at decode time. */
        val outputTruncated: Boolean = false,
    ) : MessagePart

    /** An agent step (a single LLM turn inside the session loop). */
    @Serializable
    @SerialName("step")
    data class StepPart(
        val stepId: String = "",
        val description: String = "",
        val state: StepState = StepState.STARTED,
    ) : MessagePart
}

/** Lifecycle of a [MessagePart.ToolCallPart]. */
@Serializable
enum class ToolStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR,
    ;

    companion object {
        /** Maps the server's free-form status strings; anything unknown is PENDING. */
        fun fromWire(value: String?): ToolStatus = when (value?.lowercase()) {
            "completed", "success", "done", "finished" -> COMPLETED
            "running", "busy", "in_progress", "in-progress" -> RUNNING
            "error", "failed", "failure" -> ERROR
            else -> PENDING
        }
    }
}

/** Lifecycle of a [MessagePart.StepPart]. */
@Serializable
enum class StepState {
    STARTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    ;

    companion object {
        fun fromWire(value: String?): StepState = when (value?.lowercase()) {
            "completed", "finished", "done", "end", "step-finish" -> COMPLETED
            "in_progress", "in-progress", "running", "active" -> IN_PROGRESS
            "error", "failed", "failure" -> FAILED
            else -> STARTED
        }
    }
}
