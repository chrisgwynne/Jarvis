package com.jarvis.assistant.ui.settings.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsScaffold

@Composable
internal fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    SettingsScaffold(title = "Notifications", onBack = onBack, onClose = onClose) {
        SettingsInfoCard(
            title = "Managed by Android",
            body  = "Jarvis uses Android's notification channels. Mute, prioritise or " +
                    "silence individual channels from the system settings screen.",
        )

        SettingsGroup(title = "System") {
            SettingsActionRow(
                title       = "Notification channels",
                description = "Open Android notification settings for Jarvis.",
                actionLabel = "Open system settings",
                onAction    = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        context.startActivity(
                            Intent(Settings.ACTION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
    }
}
