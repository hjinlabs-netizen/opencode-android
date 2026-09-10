package com.anomalyco.opencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anomalyco.opencode.domain.model.ThemeMode
import com.anomalyco.opencode.domain.repository.SettingsRepository
import com.anomalyco.opencode.ui.navigation.OpenCodeNavGraph
import com.anomalyco.opencode.ui.theme.OpenCodeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host. Edge-to-edge is enabled so the Compose theme owns
 * the system bars; all content lives in [OpenCodeNavGraph]. The user's
 * [ThemeMode] preference (Phase 4) overrides the system dark mode.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            OpenCodeTheme(darkTheme = resolveDarkTheme(themeMode)) {
                OpenCodeNavGraph()
            }
        }
    }
}

@Composable
private fun resolveDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}
