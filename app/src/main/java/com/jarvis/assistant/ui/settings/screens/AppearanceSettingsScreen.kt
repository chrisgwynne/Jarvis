package com.jarvis.assistant.ui.settings.screens

import androidx.compose.runtime.Composable
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsValueRow

/**
 * Appearance placeholder — Jarvis currently ships with a single dark theme.
 * Hooks for multiple themes / accent colours plug in here.
 */
@Composable
internal fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    SettingsScaffold(title = "Appearance", onBack = onBack, onClose = onClose) {
        SettingsGroup(title = "Theme") {
            SettingsValueRow(
                title       = "Theme",
                description = "Jarvis currently ships with a single dark theme.",
                value       = "Dark",
                onClick     = null,
            )
        }

        SettingsInfoCard(
            title = "More coming soon",
            body  = "Light theme, custom accent colour and display density will live here.",
        )
    }
}
