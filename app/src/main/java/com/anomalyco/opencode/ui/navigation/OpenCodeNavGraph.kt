package com.anomalyco.opencode.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anomalyco.opencode.ui.connection.ConnectionScreen

/**
 * Single navigation graph. Phase 1 has one destination; sessions, chat and
 * settings destinations will be added here in later phases.
 */
object Routes {
    const val CONNECTION = "connection"
    // Phase 2+ placeholders:
    const val SESSIONS = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val SETTINGS = "settings"
}

@Composable
fun OpenCodeNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.CONNECTION,
    ) {
        composable(Routes.CONNECTION) {
            ConnectionScreen()
        }
        // composable(Routes.SESSIONS) { SessionListScreen(...) }  — Phase 2
        // composable(Routes.CHAT) { ChatScreen(...) }             — Phase 2
        // composable(Routes.SETTINGS) { SettingsScreen(...) }     — Phase 2
    }
}
