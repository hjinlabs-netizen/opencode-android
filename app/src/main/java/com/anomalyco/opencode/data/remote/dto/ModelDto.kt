package com.anomalyco.opencode.data.remote.dto

import com.anomalyco.opencode.domain.model.ModelInfo
import com.anomalyco.opencode.domain.model.ModelSelection
import com.anomalyco.opencode.domain.model.ProviderConfig
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Tolerant wire model for the `/provider` response:
 * `{"all":[{id,name,models:...}], "connected":[ids], "default":{providerId:modelId}}`.
 *
 * `models` is served either as a JSON array **or** — current OpenCode builds —
 * as a JSON object keyed by model id (`{"glm-5.2": {"id": ...}}`, sometimes
 * with the provider already in the key: `"subconscious/glm-5.2"`). Both
 * shapes decode; see [FlexibleModelListSerializer].
 * Unknown keys (cost tables, env, options) are dropped by the shared `Json`.
 */
@Serializable
data class ProviderListDto(
    val all: List<ProviderDto> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
) {
    /**
     * @param current the (provider, model) the server reports as active, used
     * to flag exactly one [ModelInfo.isCurrent] across the whole catalog.
     */
    fun toDomain(current: ModelSelection?): List<ProviderConfig> = all.map { provider ->
        val providerId = provider.id ?: provider.providerId.orEmpty()
        ProviderConfig(
            providerId = providerId,
            displayName = provider.name ?: providerId,
            // `connected` is an OPTIONAL server hint and several builds omit
            // it or list ids that never match the catalog. Treat a provider
            // as usable when the list is absent, mentions it (case-insensitive)
            // or — crucially — when it exposes models at all: advertised
            // models are by definition selectable, and gating them behind a
            // missing hint disabled the entire picker.
            isConnected = provider.connectedHint(connected),
            models = provider.models.map { model -> model.toInfo(providerId, current) },
        )
    }

    private fun ProviderDto.connectedHint(connected: List<String>): Boolean =
        connected.isEmpty() ||
            models.isNotEmpty() ||
            connected.any { it.equals((id ?: providerId).orEmpty(), ignoreCase = true) }
}

/**
 * Map-shaped catalogs can key entries with the qualified `provider/model`
 * id; strip the known provider prefix so [ModelInfo.modelId] is the bare
 * model id and `qualifiedId` never double-prefixes when POSTing `/config`.
 * `isCurrent` matches either the raw or the normalized id.
 */
private fun ModelDto.toInfo(providerId: String, current: ModelSelection?): ModelInfo {
    val rawId = (id ?: modelId).orEmpty()
    val modelId = rawId.removePrefix("$providerId/")
    return ModelInfo(
        providerId = providerId,
        modelId = modelId,
        displayName = name ?: title ?: modelId,
        description = description,
        contextLength = limit?.context,
        isCurrent = current != null &&
            current.providerId == providerId &&
            (current.modelId == modelId || current.modelId == rawId),
    )
}

@Serializable
data class ProviderDto(
    val id: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val name: String? = null,
    @Serializable(with = FlexibleModelListSerializer::class)
    val models: List<ModelDto> = emptyList(),
)

@Serializable
data class ModelDto(
    val id: String? = null,
    @SerialName("modelID") val modelId: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String = "",
    val limit: ModelLimitDto? = null,
)

@Serializable
data class ModelLimitDto(
    val context: Int? = null,
    val output: Int? = null,
)

/**
 * Normalizes both historical wire shapes of a provider's model collection:
 *  - `[{"id":"m1",...}, ...]` → decoded per element;
 *  - `{"m1": {...}, "provider/m2": {...}}` → decoded per *value*, with the
 *    object key as the fallback model id when the value omits one.
 * Non-object entries and individually broken models are skipped rather than
 * aborting the whole catalog (one bad model must not hide the rest).
 */
object FlexibleModelListSerializer : KSerializer<List<ModelDto>> {
    private val delegate = ListSerializer(ModelDto.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<ModelDto> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return when (val element: JsonElement = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> element.mapNotNull { item ->
                runCatching {
                    jsonDecoder.json.decodeFromJsonElement(ModelDto.serializer(), item)
                }.getOrNull()
            }
            is JsonObject -> element.mapNotNull { (key, value) ->
                runCatching {
                    val model = jsonDecoder.json
                        .decodeFromJsonElement(ModelDto.serializer(), value)
                    if ((model.id ?: model.modelId).isNullOrBlank()) model.copy(id = key) else model
                }.getOrNull()
            }
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<ModelDto>) {
        encoder.encodeSerializableValue(delegate, value)
    }
}

/** `GET /config` subset: the active model id, typically `"provider/model"`. */
@Serializable
data class ConfigDto(
    val model: String? = null,
    val providerID: String? = null,
    val modelID: String? = null,
) {
    fun toSelection(): ModelSelection? {
        val (providerId, modelId) = parseQualified(model)
        return when {
            providerId != null && modelId != null -> ModelSelection(providerId, modelId)
            providerID != null && modelID != null -> ModelSelection(providerID, modelID)
            else -> null
        }
    }
}

/** `POST /config` body patch; only [model] is ever sent by this client. */
@Serializable
data class ConfigPatchDto(
    val model: String,
)

/** Splits `provider/model`, returning nulls when the format is not matched. */
fun parseQualified(value: String?): Pair<String?, String?> {
    val trimmed = value?.trim().orEmpty()
    val slash = trimmed.indexOf('/')
    return if (slash <= 0 || slash == trimmed.lastIndex) {
        null to null
    } else {
        trimmed.substring(0, slash) to trimmed.substring(slash + 1)
    }
}
