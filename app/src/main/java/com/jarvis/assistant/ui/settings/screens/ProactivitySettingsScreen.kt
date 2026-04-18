package com.jarvis.assistant.ui.settings.screens

import androidx.compose.runtime.Composable
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsScaffold

/**
 * Placeholder for the Proactivity category — kept visible so the parent
 * Settings menu is complete, but currently has no wired-up options.
 *
 * When proactive-behaviour settings land (suggestion tone, quiet hours,
 * threshold sensitivity, etc.) add them here in [SettingsGroup]s.
 */
@Composable
internal fun ProactivitySettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    SettingsScaffold(title = "Proactivity", onBack = onBack, onClose = onClose) {
        SettingsInfoCard(
            title = "Coming soon",
            body  = "Controls for when Jarvis speaks up on its own — suggestions, " +
                    "reminders and quiet hours — will live here.",
        )
    }
}
