package com.anomalyco.opencode.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anomalyco.opencode.ui.chat.ChatScreen
import com.anomalyco.opencode.ui.chat.ChatViewModel
import com.anomalyco.opencode.ui.connection.ConnectionScreen
import com.anomalyco.opencode.ui.files.DiffScreen
import com.anomalyco.opencode.ui.files.FileExplorerScreen
import com.anomalyco.opencode.ui.files.FileExplorerViewModel
import com.anomalyco.opencode.ui.session.FolderPickerScreen
import com.anomalyco.opencode.ui.session.SessionListScreen
import com.anomalyco.opencode.ui.session.SessionListViewModel
import com.anomalyco.opencode.ui.settings.SettingsScreen

/**
 * Single navigation graph: connection gate -> session list -> chat room,
 * with the Phase 3 file explorer / diff viewer and Phase 4 settings
 * hanging off the chat and session list respectively.
 */
object Routes {
    const val CONNECTION = "connection"
    const val SESSIONS = "sessions"
    const val CHAT_ARGUMENT = "sessionId"
    const val CHAT = "chat/{$CHAT_ARGUMENT}"
    const val FILES = "files"
    const val DIFF = "diff"
    const val SETTINGS = "settings"

    /** Server-side working-folder browser (W.3); always opens at the project root. */
    const val DIRECTORY_PICKER = "directoryPicker"

    /** Type-safe route builder for the chat destination. */
    fun chat(sessionId: String) = "chat/$sessionId"

    /** Opens the file explorer previewing [path] (empty = project root). */
    fun files(path: String = "") =
        if (path.isBlank()) {
            FILES
        } else {
            "$FILES?${FileExplorerViewModel.ARG_PATH}=" +
                android.net.Uri.encode(path)
        }
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
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onOpenDirectoryPicker = {
                    navController.navigate(Routes.DIRECTORY_PICKER)
                },
            )
        }

        composable(Routes.DIRECTORY_PICKER) {
            FolderPickerScreen(
                onBack = { navController.popBackStack() },
                // Hand the picked directory to the session-list entry we
                // return to, via its SavedStateHandle (PICKED_FILE_KEY
                // architecture).
                onPick = { directory ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(SessionListViewModel.PICKED_DIRECTORY_KEY, directory)
                    navController.popBackStack()
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
                onOpenFiles = { directory ->
                    navController.navigate(Routes.files(directory))
                },
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
            FileExplorerScreen(
                onBack = { navController.popBackStack() },
                // "Sohbete Ekle": hand the path to the chat entry we return to
                // via its SavedStateHandle, then pop back to it.
                onAddToChat = { path ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(ChatViewModel.PICKED_FILE_KEY, path)
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.DIFF) {
            DiffScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onManageConnection = {
                    navController.navigate(Routes.CONNECTION) {
                        popUpTo(Routes.SETTINGS) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
