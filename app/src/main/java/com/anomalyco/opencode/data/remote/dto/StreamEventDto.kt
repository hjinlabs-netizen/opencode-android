package com.anomalyco.opencode.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Envelope of every frame pushed on the OpenCode event stream:
 *
 * ```json
 * { "type": "session.next.text.delta", "properties": { "sessionID": "...", ... } }
 * ```
 *
 * [type] is matched by `StreamEventDecoder`; [properties] stays a raw
 * [JsonObject] because its schema differs per event type (decoding it
 * eagerly would couple every event kind to one DTO). Some servers omit
 * `properties` and flatten the payload into the envelope itself.
 */
@Serializable
data class EventEnvelopeDto(
    val type: String = "",
    val properties: JsonObject? = null,
)
