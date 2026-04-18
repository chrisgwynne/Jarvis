package com.jarvis.assistant.ui.settings.screens

import androidx.compose.runtime.Composable
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsScaffold

/**
 * Home Assistant placeholder.
 *
 * There is no Home Assistant client in the app yet — once the integration
 * lands (HTTP + long-lived token, entity discovery, service calls) the
 * following rows will be wired up in this screen:
 *
 *   - Server URL (e.g. http://homeassistant.local:8123)
 *   - Long-lived access token (secret field)
 *   - Test connection button
 *   - Entity exposure filter (include/exclude by domain, area, label)
 *   - Default area for ambiguous commands
 */
@Composable
internal fun HomeAssistantSettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    SettingsScaffold(title = "Home Assistant", onBack = onBack, onClose = onClose) {
        SettingsInfoCard(
            title = "Not yet connected",
            body  = "Jarvis doesn't ship with a Home Assistant client yet. " +
                    "Once it's wired up you'll set your server URL, long-lived " +
                    "access token, and which entities Jarvis can see here.",
        )

        SettingsInfoCard(
            title = "What it will unlock",
            body  = "Voice control over lights, switches, climate, covers and scripts — " +
                    "\"turn on the kitchen lights\", \"set the thermostat to 21\", " +
                    "\"run bedtime scene\".",
        )
    }
}
