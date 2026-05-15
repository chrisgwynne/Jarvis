package com.jarvis.assistant.ui.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.SettingsViewModel
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsToggleRow
import com.jarvis.assistant.ui.settings.SettingsValueRow

@Composable
internal fun AppControlSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val appControlEnabled       by vm.appControlEnabled.collectAsStateWithLifecycle()
    val accessibilityControl    by vm.allowAccessibilityAppControl.collectAsStateWithLifecycle()
    val allowAppClose           by vm.allowAppClose.collectAsStateWithLifecycle()

    SettingsScaffold(title = "App Control", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Voice app control",
            body  = "Jarvis can open, close, and navigate between apps by voice. " +
                    "Closing uses the Android HOME action — apps are not force-killed, " +
                    "just sent to the background.",
        )

        SettingsGroup(
            title  = "App control",
            footer = "Disabling this master switch suppresses 'close Spotify', " +
                     "'go home', 'go back', and Maps navigation follow-up commands.",
        ) {
            SettingsToggleRow(
                title           = "App control enabled",
                description     = "Allow voice commands to open, close, and navigate apps.",
                checked         = appControlEnabled,
                onCheckedChange = vm::setAppControlEnabled,
            )
        }

        SettingsGroup(
            title  = "Accessibility",
            footer = "The Accessibility Service enables precise BACK and HOME global " +
                     "actions. Without it, Jarvis falls back to a raw HOME intent which " +
                     "achieves the same result but with less control.",
        ) {
            SettingsToggleRow(
                title           = "Use Accessibility Service for app control",
                description     = "Allow 'go back' and 'close app' to use the Accessibility " +
                                  "Service global actions when available.",
                checked         = accessibilityControl,
                onCheckedChange = vm::setAllowAccessibilityAppControl,
            )
        }

        SettingsGroup(
            title  = "Closing apps",
            footer = "When enabled, Jarvis responds to 'close [app]', 'close this', " +
                     "'exit that', 'go home', and 'switch back to Jarvis'.",
        ) {
            SettingsToggleRow(
                title           = "Allow app close commands",
                description     = "Let Jarvis send HOME intent or use Accessibility to " +
                                  "leave a named or current app.",
                checked         = allowAppClose,
                onCheckedChange = vm::setAllowAppClose,
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "Maps navigation follow-up",
                value       = if (appControlEnabled) "Enabled" else "Disabled",
                description = "'Go', 'start it', 'switch to walking' after a route — " +
                              "controlled by the App Control master switch above.",
            )
        }
    }
}
