package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.settings.PreferenceStore
import com.anomalyco.opencode.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM coverage for the theme preference repository against an in-memory store. */
class SettingsRepositoryImplTest {

    private class InMemoryStore : PreferenceStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun `missing preference defaults to SYSTEM`() = runTest {
        val repo = SettingsRepositoryImpl(InMemoryStore())
        assertEquals(ThemeMode.SYSTEM, repo.themeMode.first())
    }

    @Test
    fun `unknown persisted value falls back to SYSTEM`() = runTest {
        val store = InMemoryStore().apply { putString("theme_mode", "hot-pink") }
        assertEquals(ThemeMode.SYSTEM, SettingsRepositoryImpl(store).themeMode.first())
    }

    @Test
    fun `setThemeMode persists the wire value and emits to observers`() = runTest {
        val store = InMemoryStore()
        val repo = SettingsRepositoryImpl(store)

        repo.setThemeMode(ThemeMode.LIGHT)

        assertEquals("light", store.map["theme_mode"])
        assertEquals(ThemeMode.LIGHT, repo.themeMode.first())
    }

    @Test
    fun `theme survives process restart via a fresh repository`() = runTest {
        val store = InMemoryStore()
        SettingsRepositoryImpl(store).setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, SettingsRepositoryImpl(store).themeMode.first())
    }
}
