package com.anomalyco.opencode.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anomalyco.opencode.ui.chat.ChatScreen
import com.anomalyco.opencode.ui.connection.ConnectionScreen
import com.anomalyco.opencode.ui.session.SessionListScreen

/**
 * Single navigation graph: connection gate -> session list -> chat room.
 */
object Routes {
    const val CONNECTION = "connection"
    const val SESSIONS = "sessions"
    const val CHAT_ARGUMENT = "sessionId"
    const val CHAT = "chat/{$CHAT_ARGUMENT}"
    const val SETTINGS = "settings" // Phase 3+

    /** Type-safe route builder for the chat destination. */
    fun chat(sessionId: String) = "chat/$sessionId"
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
            ConnectionScreen(
                onOpenSessions = {
                    navController.navigate(Routes.SESSIONS) {
                        // Connection stays reachable via app restart / drawer later.
                        popUpTo(Routes.CONNECTION)
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Routes.SESSIONS) {
            SessionListScreen(
                onOpenChat = { sessionId ->
                    navController.navigate(Routes.chat(sessionId))
                },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument(Routes.CHAT_ARGUMENT) { type = NavType.StringType },
            ),
        ) {
            ChatScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
