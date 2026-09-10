package com.anomalyco.opencode.data.remote.dto

import com.anomalyco.opencode.domain.model.ModelInfo
import com.anomalyco.opencode.domain.model.ModelSelection
import com.anomalyco.opencode.domain.model.ProviderConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tolerant wire model for the `/provider` response:
 * `{"all":[{id,name,models:[...]}], "connected":[ids], "default":{providerId:modelId}}`.
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
            isConnected = providerId in connected || connected.isEmpty(),
            models = provider.models.map { model ->
                val modelId = model.id ?: model.modelId.orEmpty()
                ModelInfo(
                    providerId = providerId,
                    modelId = modelId,
                    displayName = model.name ?: model.title ?: modelId,
                    description = model.description,
                    contextLength = model.limit?.context,
                    isCurrent = current != null &&
                        current.providerId == providerId && current.modelId == modelId,
                )
            },
        )
    }
}

@Serializable
data class ProviderDto(
    val id: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val name: String? = null,
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
