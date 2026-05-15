package com.jarvis.assistant.ui.settings.screens

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.trust.AutonomyPreset
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsDropdownRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsToggleRow

@Composable
internal fun TrustAutonomySettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repo    = JarvisApp.autonomySettingsRepo
    val state   by repo.settingsFlow.collectAsState()
    val learned = JarvisApp.learnedTrustStore

    SettingsScaffold(title = "Trust & Autonomy", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "How this works",
            body  = "Jarvis evaluates trust signals (voice, session, location context) " +
                "to decide whether to act immediately or ask first. " +
                "The preset controls the baseline; per-category overrides let you fine-tune.",
        )

        SettingsGroup(
            title = "Autonomy preset",
            description = "Overall decision-making stance",
        ) {
            SettingsDropdownRow(
                title       = "Preset",
                description = "Conservative always asks for medium-risk actions. " +
                    "Balanced auto-approves when trust is high. " +
                    "Jarvis-style minimises interruptions.",
                options     = AutonomyPreset.entries,
                selected    = state.preset,
                label       = { preset ->
                    preset.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                },
                onSelected  = { repo.setPreset(it) },
            )
        }

        SettingsGroup(
            title = "Action confirmations",
            description = "Always ask before these action types, regardless of trust score",
        ) {
            SettingsToggleRow(
                title       = "Confirm before sending messages",
                description = "SMS, WhatsApp and email sends",
                checked     = state.requireConfirmForMessages,
                onCheckedChange = { repo.setRequireConfirmForMessages(it) },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title       = "Confirm before calls",
                description = "Outgoing phone calls",
                checked     = state.requireConfirmForCalls,
                onCheckedChange = { repo.setRequireConfirmForCalls(it) },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title       = "Confirm before sharing media",
                description = "Photos and visual follow-ups",
                checked     = state.requireConfirmForMediaShare,
                onCheckedChange = { repo.setRequireConfirmForMediaShare(it) },
            )
        }

        SettingsGroup(
            title = "Context-sensitive trust",
            description = "Signals that relax or restrict autonomy",
        ) {
            SettingsToggleRow(
                title       = "Lockscreen restrictions",
                description = "Block sensitive reads and sends when the screen is locked",
                checked     = state.lockscreenRestrictions,
                onCheckedChange = { repo.setLockscreenRestrictions(it) },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title       = "Car mode — maximise autonomy",
                description = "Auto-approve low and medium-risk actions while driving",
                checked     = state.carModeAutonomy,
                onCheckedChange = { repo.setCarModeAutonomy(it) },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title       = "Headphones as private context",
                description = "Treat headphones as a signal that you're in private — relaxes some confirmations",
                checked     = state.headphonesPrivateMode,
                onCheckedChange = { repo.setHeadphonesPrivateMode(it) },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title       = "Home Wi-Fi as trusted context",
                description = "Relax confirmations when connected to your home network",
                checked     = state.homeTrustedMode,
                onCheckedChange = { repo.setHomeTrustedMode(it) },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title       = "Voice identity trust",
                description = "Use voice match as an additional trust signal",
                checked     = state.voiceTrustEnabled,
                onCheckedChange = { repo.setVoiceTrustEnabled(it) },
            )
        }

        SettingsGroup(
            title = "Learned preferences",
            description = "Patterns Jarvis has picked up from your confirmations",
        ) {
            SettingsActionRow(
                title       = "Reset learned decisions",
                description = "Clear all \"stop asking me that\" and auto-approval streaks",
                actionLabel = "Reset",
                destructive = true,
                confirm     = true,
                onAction    = {
                    learned.clearAll()
                    Toast.makeText(context, "Learned trust data cleared", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
