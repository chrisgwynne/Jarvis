package com.jarvis.assistant.ui.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.trust.AutonomyPreset
import com.jarvis.assistant.trust.TrustSignalEvaluator
import com.jarvis.assistant.trust.TrustContext
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsValueRow

@Composable
internal fun TrustDiagnosticsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val repo     = JarvisApp.autonomySettingsRepo
    val learned  = JarvisApp.learnedTrustStore
    val settings by repo.settingsFlow.collectAsState()

    // Evaluate a baseline trust score with no live context signals
    // (device-locked = false, voice = OWNER_ASSUMED, etc.) — this gives
    // a realistic floor for diagnostics without requiring a live session.
    val baseScore = TrustSignalEvaluator.evaluate(TrustContext())
    val learnedSnapshot = learned.snapshot()

    val thresholdLabel = when (settings.preset) {
        AutonomyPreset.JARVIS_STYLE -> "0.55 (Jarvis-style)"
        else                        -> "0.70 (Balanced / Conservative)"
    }

    SettingsScaffold(title = "Trust Diagnostics", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "About this screen",
            body  = "Shows a baseline trust evaluation and per-tool learned patterns. " +
                "Live trust scores vary per dispatch based on voice match, device lock " +
                "state, and session context. Check logcat for [AUTONOMY_EVALUATE] tags " +
                "for real-time decision logs.",
        )

        SettingsGroup(
            title = "Baseline trust (no live signals)",
            description = "Score computed with OWNER_ASSUMED, device unlocked, no extras",
        ) {
            SettingsValueRow(
                title = "Trust score",
                value = "%.2f".format(baseScore.value),
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Trust mode",
                value = baseScore.mode.name
                    .replace('_', ' ')
                    .lowercase()
                    .replaceFirstChar { it.uppercase() },
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Active signals",
                value = if (baseScore.activeSignals.isEmpty()) "none"
                    else baseScore.activeSignals.joinToString(", ") {
                        it.name.replace('_', ' ').lowercase()
                    },
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Auto-approve threshold",
                value = thresholdLabel,
            )
        }

        SettingsGroup(
            title = "Current autonomy settings",
            description = "Active preset and per-category overrides",
        ) {
            SettingsValueRow(
                title = "Preset",
                value = settings.preset.name
                    .replace('_', ' ')
                    .lowercase()
                    .replaceFirstChar { it.uppercase() },
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Confirm messages",
                value = if (settings.requireConfirmForMessages) "Always" else "Trust-based",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Confirm calls",
                value = if (settings.requireConfirmForCalls) "Always" else "Trust-based",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Lockscreen restrictions",
                value = if (settings.lockscreenRestrictions) "Enabled" else "Disabled",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Car mode autonomy",
                value = if (settings.carModeAutonomy) "Enabled" else "Disabled",
            )
        }

        if (learnedSnapshot.isNotEmpty()) {
            SettingsGroup(
                title = "Learned patterns",
                description = "Per-tool trust data accumulated from your usage",
            ) {
                learnedSnapshot.entries
                    .sortedByDescending { it.value.autoApprovals }
                    .forEachIndexed { index, (tool, stats) ->
                        if (index > 0) SettingsRowDivider()
                        val feedbackLabel = stats.userFeedback?.name
                            ?.replace('_', ' ')
                            ?.lowercase()
                            ?.replaceFirstChar { it.uppercase() }
                            ?: "none"
                        SettingsValueRow(
                            title = tool.replace('_', ' '),
                            value = "auto=${stats.autoApprovals}  " +
                                "confirmed=${stats.confirmedApprovals}  " +
                                "feedback=$feedbackLabel",
                        )
                    }
            }
        } else {
            SettingsInfoCard(
                title = "No learned patterns yet",
                body  = "Jarvis will build a trust profile as you use voice commands. " +
                    "After 5 consecutive auto-approvals for a tool, it will stop asking.",
            )
        }
    }
}
