package com.jarvis.assistant.ui.settings.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.SettingsViewModel
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold

@Composable
internal fun PrivacySettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val signedIn by vm.openAiSignedIn.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Privacy", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Local & encrypted",
            body  = "API keys and OAuth tokens are stored in Android's keystore-backed " +
                    "EncryptedSharedPreferences. Voice recordings are processed locally " +
                    "and discarded — only transcripts leave the device.",
        )

        SettingsGroup(
            title  = "Accounts",
            footer = "Provider configuration lives in Advanced. This shortcut only lets " +
                     "you sign out.",
        ) {
            if (signedIn) {
                SettingsActionRow(
                    title       = "OpenAI account",
                    description = "You're signed in with OAuth. Sign out to revoke the access token on this device.",
                    actionLabel = "Sign out of OpenAI",
                    destructive = true,
                    confirm     = true,
                    confirmCopy = "Sign out",
                    onAction    = vm::signOutOpenAi,
                )
            } else {
                SettingsInfoCard(
                    title = "OpenAI account",
                    body  = "Not signed in. Sign in from Advanced → LLM provider.",
                )
            }
        }

        SettingsGroup(title = "Permissions") {
            SettingsActionRow(
                title       = "App permissions",
                description = "Review microphone, contacts, SMS, phone and Bluetooth access.",
                actionLabel = "Open app permissions",
                onAction    = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) { /* ignore */ }
                },
            )
        }
    }
}
