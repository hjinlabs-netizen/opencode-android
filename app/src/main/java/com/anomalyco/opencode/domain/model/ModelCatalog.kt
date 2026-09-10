package com.anomalyco.opencode.domain.model

/**
 * Catalog of LLM providers/models served by `/provider` and mutated through
 * `POST /config`. [ProviderConfig] groups models under a provider; exactly
 * one [ModelInfo.isCurrent] is true across the whole catalog.
 */
data class ModelInfo(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val isCurrent: Boolean = false,
    val description: String = "",
    val contextLength: Int? = null,
) {
    val qualifiedId: String get() = "$providerId/$modelId"
}

data class ProviderConfig(
    val providerId: String,
    val displayName: String,
    val models: List<ModelInfo> = emptyList(),
    val isConnected: Boolean = true,
) {
    val currentModel: ModelInfo? get() = models.firstOrNull { it.isCurrent }
}

/** A (provider, model) pair used to switch the active agent model. */
data class ModelSelection(
    val providerId: String,
    val modelId: String,
) {
    val qualifiedId: String get() = "$providerId/$modelId"
}
