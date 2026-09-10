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
import com.anomalyco.opencode.ui.files.DiffScreen
import com.anomalyco.opencode.ui.files.FileExplorerScreen
import com.anomalyco.opencode.ui.files.FileExplorerViewModel
import com.anomalyco.opencode.ui.session.SessionListScreen

/**
 * Single navigation graph: connection gate -> session list -> chat room,
 * with the Phase 3 file explorer and diff viewer hanging off the chat.
 */
object Routes {
    const val CONNECTION = "connection"
    const val SESSIONS = "sessions"
    const val CHAT_ARGUMENT = "sessionId"
    const val CHAT = "chat/{$CHAT_ARGUMENT}"
    const val FILES = "files"
    const val DIFF = "diff"
    const val SETTINGS = "settings" // Phase 4+

    /** Type-safe route builder for the chat destination. */
    fun chat(sessionId: String) = "chat/$sessionId"

    /** Opens the file explorer previewing [path] (empty = project root). */
    fun files(path: String = "") =
        if (path.isBlank()) FILES else "$FILES?${FileExplorerViewModel.ARG_PATH}=$path"
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
                onOpenFiles = { navController.navigate(Routes.FILES) },
                onOpenDiff = { navController.navigate(Routes.DIFF) },
            )
        }

        composable(
            route = "${Routes.FILES}?${FileExplorerViewModel.ARG_PATH}={${FileExplorerViewModel.ARG_PATH}}",
            arguments = listOf(
                navArgument(FileExplorerViewModel.ARG_PATH) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            FileExplorerScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DIFF) {
            DiffScreen(onBack = { navController.popBackStack() })
        }
    }
}
