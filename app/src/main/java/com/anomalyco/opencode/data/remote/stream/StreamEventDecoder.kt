package com.anomalyco.opencode.data.remote.stream

import com.anomalyco.opencode.data.remote.dto.EventEnvelopeDto
import com.anomalyco.opencode.domain.model.PermissionRequest
import com.anomalyco.opencode.domain.model.QuestionRequest
import com.anomalyco.opencode.domain.model.StreamEvent
import com.anomalyco.opencode.domain.model.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure, allocation-cheap translation of raw SSE JSON frames into
 * [StreamEvent]s. No I/O and no coroutine dependency — this class is the
 * single place where the wire event vocabulary is known, which keeps the
 * transport and the repositories completely decoupled from schema drift.
 *
 * Frames that fail to parse or are missing a `type` return `null` (dropped);
 * known envelopes with unmodelled types decode to [StreamEvent.Unknown] so
 * newer servers never crash the client.
 */
@Singleton
class StreamEventDecoder @Inject constructor(
    private val json: Json,
) {

    fun decode(raw: String): StreamEvent? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val envelope = runCatching {
            json.decodeFromString(EventEnvelopeDto.serializer(), trimmed)
        }.getOrNull() ?: return null
        if (envelope.type.isBlank()) return null
        val properties = envelope.properties
            ?: runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return null
        return map(envelope.type, properties)
    }

    /** Decodes a batch frame (`[ {...}, {...} ]`) or a single envelope. */
    fun decodeAll(raw: String): List<StreamEvent> {
        val trimmed = raw.trim()
        val array = runCatching { json.parseToJsonElement(trimmed) as? JsonArray }
            .getOrNull()
        if (array != null) {
            return array.mapNotNull { element ->
                (element as? JsonObject)?.let { decode(it.toString()) }
            }
        }
        return listOfNotNull(decode(trimmed))
    }

    private fun map(type: String, p: JsonObject): StreamEvent = when (type) {
        "session.next.text.delta" ->
            StreamEvent.TextDelta(p.sessionId(), p.partId(), p.str("delta", "text"))

        "session.next.reasoning.delta" ->
            StreamEvent.ReasoningDelta(p.sessionId(), p.partId(), p.str("delta", "text"))

        "session.next.tool.called" ->
            StreamEvent.ToolCalled(p.sessionId(), p.callId(), p.toolName(), p.args())

        "session.next.tool.updated" ->
            StreamEvent.ToolUpdated(
                sessionId = p.sessionId(),
                callId = p.callId(),
                toolName = p.toolName().ifEmpty { null },
                status = ToolStatus.fromWire(p.str("status")),
                output = p.strOpt("output", "title"),
            )

        "session.next.tool.finished" ->
            StreamEvent.ToolFinished(
                sessionId = p.sessionId(),
                callId = p.callId(),
                status = ToolStatus.fromWire(p.str("status")),
                output = p.strOpt("output", "title"),
            )

        "session.next.step.started" ->
            StreamEvent.StepStarted(p.sessionId(), p.stepId(), p.str("description", "title"))

        "session.next.step.finished" ->
            StreamEvent.StepFinished(p.sessionId(), p.stepId(), p.str("description", "title"))

        "session.next.message.updated", "message.updated" ->
            StreamEvent.MessageUpdated(p.sessionId(), p.messageId())

        "session.idle", "session.next.idle" ->
            StreamEvent.SessionIdle(p.sessionId())

        "session.error", "session.next.error" ->
            StreamEvent.SessionError(
                sessionId = p.sessionId().ifEmpty { null },
                message = p.str("message", "error").ifEmpty { "Session error" },
            )

        "permission.asked", "session.next.permission.asked" ->
            StreamEvent.PermissionAsked(p.sessionId(), p.toPermissionRequest())

        "question.asked", "session.next.question.asked" ->
            StreamEvent.QuestionAsked(p.sessionId(), p.toQuestionRequest())

        else -> StreamEvent.Unknown(type)
    }
}

// ---- tolerant JsonObject field access ------------------------------------
// Many events nest payloads into a `part`/`info` sub-object; lookups cascade
// into it so every event shape maps to the same domain fields.

private fun JsonObject.str(vararg keys: String): String {
    keys.forEach { key ->
        this[key]?.jsonPrimitive?.contentOrNull?.let { return it }
    }
    val part = this["part"]?.jsonObject ?: this["info"]?.jsonObject
    if (part != null) return part.str(*keys)
    return ""
}

private fun JsonObject.strOpt(vararg keys: String): String? =
    str(*keys).ifEmpty { null }

private fun JsonObject.sessionId(): String =
    str("sessionID", "sessionId")
        .ifEmpty { this["session"]?.jsonObject?.str("id").orEmpty() }

private fun JsonObject.partId(): String =
    str("partID", "partId", "id", "messageID")

private fun JsonObject.callId(): String =
    str("callID", "callId", "id")

private fun JsonObject.toolName(): String =
    str("tool", "toolName", "name")

private fun JsonObject.stepId(): String =
    str("stepID", "stepId", "id", "messageID")

private fun JsonObject.messageId(): String =
    str("messageID", "messageId", "id")

/**
 * Tool arguments as raw JSON text: primitives pass through as content,
 * objects/arrays serialize verbatim (the domain keeps args as a String).
 */
private fun JsonObject.args(): String {
    val containers = listOf(
        this,
        this["part"]?.jsonObject,
        this["part"]?.jsonObject?.get("state")?.jsonObject,
    )
    for (container in containers) {
        container ?: continue
        for (key in listOf("args", "arguments", "input")) {
            val element: JsonElement = container[key] ?: continue
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.let { return it }
                is JsonObject, is JsonArray -> return element.toString()
            }
        }
    }
    return "{}"
}

// ---- interactive (permission / question) payload parsers --------------------

/**
 * `permission.asked` shapes vary: flat fields, or nested under `metadata`
 * with affected files in `patterns[]`. Everything is optional.
 */
private fun JsonObject.toPermissionRequest(): PermissionRequest {
    val metadata = this["metadata"]?.jsonObject
    val patterns = (this["patterns"] as? JsonArray)?.firstStringOrNull()
    return PermissionRequest(
        requestId = str("id", "requestID", "permissionID"),
        sessionId = sessionId(),
        type = str("permission", "operation", "kind"),
        description = str("description", "title", "message"),
        path = metadata?.direct("filepath")
            ?: metadata?.direct("filePath")
            ?: direct("path")
            ?: direct("filepath")
            ?: patterns,
        command = metadata?.direct("command") ?: direct("command"),
        diff = metadata?.direct("diff") ?: direct("diff"),
    )
}

/**
 * `question.asked` may carry a single flat question or a `questions[]` list;
 * the first question is surfaced (multi-question turns are rare and still
 * resolvable through the CLI). Options accept both plain strings and
 * `{label, description}` objects.
 */
private fun JsonObject.toQuestionRequest(): QuestionRequest {
    val first = (this["questions"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.firstOrNull()
    val source = first ?: this
    val options = source.stringList("options").ifEmpty { source.stringList("choices") }
    return QuestionRequest(
        questionId = str("id", "requestID", "questionID"),
        sessionId = sessionId(),
        text = source.str("question", "text", "header"),
        options = options,
        allowCustomAnswer = source["custom"]?.jsonPrimitive?.booleanOrNull
            ?: source["allowCustom"]?.jsonPrimitive?.booleanOrNull
            ?: true,
        multiple = source["multiple"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
}

private fun JsonObject.direct(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonArray.firstStringOrNull(): String? =
    (firstOrNull() as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> element.direct("label") ?: element.direct("value") ?: element.direct("text")
            else -> null
        }
    }
}
