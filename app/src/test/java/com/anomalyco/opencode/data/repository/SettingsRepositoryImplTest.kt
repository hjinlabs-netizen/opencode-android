package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.domain.model.ThemeMode
import com.anomalyco.opencode.util.InMemoryPreferenceStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM coverage for the theme preference repository against an in-memory store. */
class SettingsRepositoryImplTest {

    @Test
    fun `missing preference defaults to SYSTEM`() = runTest {
        val repo = SettingsRepositoryImpl(InMemoryPreferenceStore())
        assertEquals(ThemeMode.SYSTEM, repo.themeMode.first())
    }

    @Test
    fun `unknown persisted value falls back to SYSTEM`() = runTest {
        val store = InMemoryPreferenceStore().apply { putString("theme_mode", "hot-pink") }
        assertEquals(ThemeMode.SYSTEM, SettingsRepositoryImpl(store).themeMode.first())
    }

    @Test
    fun `setThemeMode persists the wire value and emits to observers`() = runTest {
        val store = InMemoryPreferenceStore()
        val repo = SettingsRepositoryImpl(store)

        repo.setThemeMode(ThemeMode.LIGHT)

        assertEquals("light", store.map["theme_mode"])
        assertEquals(ThemeMode.LIGHT, repo.themeMode.first())
    }

    @Test
    fun `theme survives process restart via a fresh repository`() = runTest {
        val store = InMemoryPreferenceStore()
        SettingsRepositoryImpl(store).setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, SettingsRepositoryImpl(store).themeMode.first())
    }
}
