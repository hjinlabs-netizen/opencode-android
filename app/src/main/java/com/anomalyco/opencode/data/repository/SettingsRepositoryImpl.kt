package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.settings.PreferenceStore
import com.anomalyco.opencode.domain.model.ThemeMode
import com.anomalyco.opencode.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [SettingsRepository]: synchronous read-through of the
 * [PreferenceStore] (plain SharedPreferences — theme choice is not a secret),
 * mirrored into a [MutableStateFlow] so the UI observes every change.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val store: PreferenceStore,
) : SettingsRepository {

    private val _themeMode = MutableStateFlow(readTheme())
    override val themeMode: Flow<ThemeMode> = _themeMode.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.putString(KEY_THEME, mode.wireValue)
        _themeMode.value = mode
    }

    private fun readTheme(): ThemeMode =
        ThemeMode.fromWire(store.getString(KEY_THEME))

    private companion object {
        const val KEY_THEME = "theme_mode"
    }
}
