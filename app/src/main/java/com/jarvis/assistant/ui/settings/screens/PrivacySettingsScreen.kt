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
import com.jarvis.assistant.ui.settings.SettingsTextFieldRow
import com.jarvis.assistant.ui.settings.SettingsToggleRow

@Composable
internal fun PrivacySettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val signedIn   by vm.openAiSignedIn.collectAsStateWithLifecycle()
    val killSwitch by vm.toolExecutionDisabled.collectAsStateWithLifecycle()
    val autoStart  by vm.autoStartOnBoot.collectAsStateWithLifecycle()

    val ghEnabled   by vm.githubReportingEnabled.collectAsStateWithLifecycle()
    val ghToken     by vm.githubToken.collectAsStateWithLifecycle()
    val ghOwner     by vm.githubRepoOwner.collectAsStateWithLifecycle()
    val ghRepo      by vm.githubRepoName.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Privacy", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Local & encrypted",
            body  = "API keys and OAuth tokens are stored in Android's keystore-backed " +
                    "EncryptedSharedPreferences. Voice recordings are processed locally " +
                    "and discarded — only transcripts leave the device.",
        )

        SettingsGroup(
            title  = "Control",
            footer = "Pausing tool execution keeps conversation working but blocks every " +
                     "device action until the toggle is turned back on.",
        ) {
            SettingsToggleRow(
                title       = "Pause tool execution",
                description = "Disable every tool (calls, messages, alarms, camera, etc.) " +
                              "while still allowing conversation.",
                checked     = killSwitch,
                onCheckedChange = vm::setToolExecutionDisabled,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title       = "Start Jarvis on boot",
                description = "When off, Jarvis stays idle after device reboot until you " +
                              "open the app.",
                checked     = autoStart,
                onCheckedChange = vm::setAutoStartOnBoot,
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Stop Jarvis now",
                description = "Shut down the foreground service until you relaunch the app.",
                actionLabel = "Stop service",
                destructive = true,
                confirm     = true,
                confirmCopy = "Stop",
                onAction    = vm::stopJarvisService,
            )
        }

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

        SettingsGroup(
            title  = "GitHub issue reporting",
            footer = "When enabled, runtime errors are automatically filed as GitHub issues " +
                     "using your PAT. Only HIGH/FATAL severity events are submitted. " +
                     "The token is stored encrypted and never logged.",
        ) {
            SettingsToggleRow(
                title           = "Enable auto-reporting",
                description     = "Submit crash/error reports to your GitHub repo.",
                checked         = ghEnabled,
                onCheckedChange = vm::setGithubReportingEnabled,
            )
            if (ghEnabled) {
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Personal access token (PAT)",
                    description   = "Needs 'issues: write' scope on the target repo.",
                    value         = ghToken,
                    onValueChange = vm::setGithubToken,
                    placeholder   = "github_pat_…",
                    isSecret      = true,
                )
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Repo owner",
                    value         = ghOwner,
                    onValueChange = vm::setGithubRepoOwner,
                    placeholder   = "chrisgwynne",
                )
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Repo name",
                    value         = ghRepo,
                    onValueChange = vm::setGithubRepoName,
                    placeholder   = "Jarvis",
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
