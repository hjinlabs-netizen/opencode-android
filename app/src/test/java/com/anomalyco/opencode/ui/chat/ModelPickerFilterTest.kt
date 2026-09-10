package com.anomalyco.opencode.ui.chat

import com.anomalyco.opencode.domain.model.ModelInfo
import com.anomalyco.opencode.domain.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure filtering rules for the model picker search box. */
class ModelPickerFilterTest {

    private val catalog = listOf(
        ProviderConfig(
            providerId = "anthropic",
            displayName = "Anthropic",
            models = listOf(
                ModelInfo("anthropic", "claude-x", "Claude X"),
                ModelInfo("anthropic", "claude-y", "Claude Y"),
            ),
        ),
        ProviderConfig(
            providerId = "subconscious",
            displayName = "Subconscious",
            models = listOf(ModelInfo("subconscious", "glm-5.2", "GLM 5.2")),
        ),
        ProviderConfig(providerId = "emptyai", displayName = "EmptyAI", models = emptyList()),
    )

    @Test
    fun `blank or whitespace query returns the untouched catalog`() {
        assertEquals(catalog, filterProviders(catalog, ""))
        assertEquals(catalog, filterProviders(catalog, "   "))
    }

    @Test
    fun `model name match narrows the provider to matching models only`() {
        val result = filterProviders(catalog, "claude x")

        val anthropic = result.single()
        assertEquals("anthropic", anthropic.providerId)
        assertEquals(listOf("claude-x"), anthropic.models.map { it.modelId })
    }

    @Test
    fun `provider name match keeps the whole provider with all models`() {
        val result = filterProviders(catalog, "subcon")

        val provider = result.single()
        assertEquals("subconscious", provider.providerId)
        assertEquals(1, provider.models.size)
    }

    @Test
    fun `qualified provider-slash-model query matches the model`() {
        val result = filterProviders(catalog, "anthropic/claude-y")

        assertEquals(1, result.size)
        assertEquals(listOf("claude-y"), result.single().models.map { it.modelId })
    }

    @Test
    fun `providerId-only query keeps the provider header with its models`() {
        // "empty" hits providerId/displayName but the provider has no models;
        // the header stays so the user sees why the section is empty.
        val result = filterProviders(catalog, "emptyai")

        assertEquals(listOf("emptyai"), result.map { it.providerId })
        assertTrue(result.single().models.isEmpty())
    }

    @Test
    fun `search is case-insensitive across ids and names`() {
        assertEquals(1, filterProviders(catalog, "GLM").size)
        assertEquals(1, filterProviders(catalog, "GlM-5.2").size)
        val claude = filterProviders(catalog, "CLAUDE")
        assertEquals(1, claude.size)
        assertEquals(2, claude.single().models.size)
    }

    @Test
    fun `nonsense query matches nothing`() {
        assertEquals(emptyList<ProviderConfig>(), filterProviders(catalog, "zzz-nope"))
    }
}
