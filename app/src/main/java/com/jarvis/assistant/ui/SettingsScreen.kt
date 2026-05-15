package com.jarvis.assistant.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jarvis.assistant.ui.settings.SETTINGS_ROOT_ROUTE
import com.jarvis.assistant.ui.settings.SettingsCategory
import com.jarvis.assistant.ui.settings.SettingsRootScreen
import com.jarvis.assistant.ui.settings.screens.ActionsAppsSettingsScreen
import com.jarvis.assistant.ui.settings.screens.AdvancedSettingsScreen
import com.jarvis.assistant.ui.settings.screens.AppearanceSettingsScreen
import com.jarvis.assistant.ui.settings.screens.ConversationSettingsScreen
import com.jarvis.assistant.ui.settings.screens.FaqSettingsScreen
import com.jarvis.assistant.ui.settings.screens.HomeAssistantSettingsScreen
import com.jarvis.assistant.ui.settings.screens.MemorySettingsScreen
import com.jarvis.assistant.ui.settings.screens.NotificationsSettingsScreen
import com.jarvis.assistant.ui.settings.screens.PrivacySettingsScreen
import com.jarvis.assistant.ui.settings.screens.PersonalitySettingsScreen
import com.jarvis.assistant.ui.settings.screens.ProactivitySettingsScreen
import com.jarvis.assistant.ui.settings.screens.VoiceSettingsScreen
import com.jarvis.assistant.ui.settings.screens.WearablesSettingsScreen

/**
 * Settings entry point.
 *
 * Hosts a nested NavController so the settings flow (root → category) is
 * self-contained:
 *  - Back from a sub-screen pops inside the settings NavHost and returns to
 *    the root category list.
 *  - Close (X) from any screen calls [onBack], which exits the whole
 *    settings flow by popping the outer app-level NavController.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val settingsNav = rememberNavController()
    val popToRoot: () -> Unit = { settingsNav.popBackStack() }

    NavHost(
        navController = settingsNav,
        startDestination = SETTINGS_ROOT_ROUTE,
    ) {
        composable(SETTINGS_ROOT_ROUTE) {
            SettingsRootScreen(
                onOpenCategory = { category -> settingsNav.navigate(category.route) },
                onClose        = onBack,
            )
        }

        composable(SettingsCategory.Conversation.route) {
            ConversationSettingsScreen(vm = vm, onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.Voice.route) {
            VoiceSettingsScreen(vm = vm, onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.Memory.route) {
            MemorySettingsScreen(vm = vm, onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.Proactivity.route) {
            ProactivitySettingsScreen(onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.Personality.route) {
            PersonalitySettingsScreen(onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.Wearables.route) {
            WearablesSettingsScreen(onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.Calendar.route) {
            com.jarvis.assistant.ui.settings.screens.CalendarSettingsScreen(
                onBack = popToRoot, onClose = onBack
            )
        }
        composable(SettingsCategory.Todoist.route) {
            com.jarvis.assistant.ui.settings.screens.TodoistSettingsScreen(
                onBack = popToRoot, onClose = onBack
            )
        }
        composable(SettingsCategory.Messaging.route) {
            com.jarvis.assistant.ui.settings.screens.MessagingDiagnosticsScreen(
                onBack = popToRoot, onClose = onBack
            )
        }
        composable(SettingsCategory.LocalDiagnostics.route) {
            com.jarvis.assistant.ui.settings.screens.LocalDiagnosticsScreen(
                onBack = popToRoot, onClose = onBack
            )
        }
        composable(SettingsCategory.ActionsApps.route) {
            ActionsAppsSettingsScreen(vm = vm, onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.HomeAssistant.route) {
            HomeAssistantSettingsScreen(vm = vm, onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.Notifications.route) {
            NotificationsSettingsScreen(onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.Privacy.route) {
            PrivacySettingsScreen(vm = vm, onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.Appearance.route) {
            AppearanceSettingsScreen(vm = vm, onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.Advanced.route) {
            AdvancedSettingsScreen(vm = vm, onBack = popToRoot, onClose = onBack)
        }
        composable(SettingsCategory.ExperimentalFlags.route) {
            com.jarvis.assistant.ui.settings.screens.ExperimentalFlagsSettingsScreen(
                onBack = popToRoot, onClose = onBack
            )
        }
        composable(SettingsCategory.Faq.route) {
            FaqSettingsScreen(onBack = popToRoot, onClose = onBack)
        }
    }
}
