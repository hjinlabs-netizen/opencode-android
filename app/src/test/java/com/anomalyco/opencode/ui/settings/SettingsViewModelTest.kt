package com.anomalyco.opencode.ui.settings

import com.anomalyco.opencode.data.connection.ConnectionStateManager
import com.anomalyco.opencode.domain.model.ConnectionState
import com.anomalyco.opencode.domain.model.HealthInfo
import com.anomalyco.opencode.domain.model.ModelInfo
import com.anomalyco.opencode.domain.model.ProviderConfig
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.model.ThemeMode
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import com.anomalyco.opencode.domain.repository.ModelRepository
import com.anomalyco.opencode.domain.repository.SettingsRepository
import com.anomalyco.opencode.util.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeSettingsRepository : SettingsRepository {
    val themeFlow = MutableStateFlow(ThemeMode.SYSTEM)
    val saved = mutableListOf<ThemeMode>()
    override val themeMode: Flow<ThemeMode> = themeFlow
    override suspend fun setThemeMode(mode: ThemeMode) {
        saved += mode
        themeFlow.value = mode
    }
}

private class FakeConnection(
    private val server: ServerConfig? = ServerConfig("http://10.0.0.9:4096/", "abcdef123"),
) : ConnectionRepository {
    val configFlow = MutableStateFlow(server)
    override val config: Flow<ServerConfig?> = configFlow
    var clearCalls = 0
    var healthResult: Result<HealthInfo> = Result.success(HealthInfo(version = "0.9.1"))

    override suspend fun saveConfig(config: ServerConfig) {
        configFlow.value = config
    }

    override suspend fun clearConfig() {
        clearCalls++
        configFlow.value = null
    }

    override suspend fun checkHealth(config: ServerConfig) = healthResult
}

private class FakeModels : ModelRepository {
    var providersResult: Result<List<ProviderConfig>> = Result.success(emptyList())
    override suspend fun fetchProviders() = providersResult
    override suspend fun setActiveModel(providerId: String, modelId: String) = Result.success(Unit)
}

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Harness(
        val connection: FakeConnection = FakeConnection(),
        val settings: FakeSettingsRepository = FakeSettingsRepository(),
        val models: FakeModels = FakeModels(),
    ) {
        val manager = ConnectionStateManager(connection)
        val viewModel = SettingsViewModel(connection, manager, settings, models)
    }

    @Test
    fun `init surfaces normalized url and masked token`() = runTest {
        val h = Harness()
        advanceUntilIdle()

        val state = h.viewModel.uiState.value
        assertEquals("http://10.0.0.9:4096", state.serverUrl)
        assertTrue(state.hasToken)
        assertEquals("ab••••••23", state.tokenMasked)
        assertFalse(state.tokenMasked.contains("cdef1"))
    }

    @Test
    fun `maskToken hides short tokens entirely`() {
        assertEquals("", SettingsViewModel.maskToken(""))
        assertEquals("••••••", SettingsViewModel.maskToken("abcd"))
        assertEquals("ab••••••yz", SettingsViewModel.maskToken("abcdefyz"))
    }

    @Test
    fun `setThemeMode writes through the repository and reflects in state`() = runTest {
        val h = Harness()
        advanceUntilIdle()

        h.viewModel.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(listOf(ThemeMode.DARK), h.settings.saved)
        assertEquals(ThemeMode.DARK, h.viewModel.uiState.value.themeMode)
    }

    @Test
    fun `health probe records latency and server version`() = runTest {
        val h = Harness()
        advanceUntilIdle()

        h.viewModel.runHealthCheck()
        advanceUntilIdle()

        val state = h.viewModel.uiState.value
        assertFalse(state.isCheckingHealth)
        assertEquals("0.9.1", state.serverVersion)
        assertNotNull(state.latencyMs)
        assertTrue(state.connection is ConnectionState.Connected)
    }

    @Test
    fun `failed health probe surfaces the friendly error`() = runTest {
        val h = Harness()
        advanceUntilIdle()
        h.connection.healthResult = Result.failure(Exception("Sunucuya bağlanılamadı"))

        h.viewModel.runHealthCheck()
        advanceUntilIdle()

        val state = h.viewModel.uiState.value
        assertFalse(state.isCheckingHealth)
        assertEquals("Sunucuya bağlanılamadı", state.error)
        assertNull(state.latencyMs)
    }

    @Test
    fun `clearServer erases config and resets the connection state`() = runTest {
        val h = Harness()
        h.viewModel.runHealthCheck()
        advanceUntilIdle()
        assertTrue(h.viewModel.uiState.value.connection is ConnectionState.Connected)

        h.viewModel.clearServer()
        advanceUntilIdle()

        assertEquals(1, h.connection.clearCalls)
        assertEquals("", h.viewModel.uiState.value.serverUrl)
        assertTrue(h.viewModel.uiState.value.connection is ConnectionState.Disconnected)
        assertNull(h.viewModel.uiState.value.serverVersion)
    }

    @Test
    fun `active model diagnostic comes from the provider catalog`() = runTest {
        val h = Harness(
            models = FakeModels().apply {
                providersResult = Result.success(
                    listOf(
                        ProviderConfig(
                            providerId = "anthropic",
                            displayName = "Anthropic",
                            models = listOf(
                                ModelInfo("anthropic", "claude-x", "Claude X", isCurrent = true),
                            ),
                        ),
                    ),
                )
            },
        )
        advanceUntilIdle()

        assertEquals("anthropic/claude-x", h.viewModel.uiState.value.activeModel)
    }
}
