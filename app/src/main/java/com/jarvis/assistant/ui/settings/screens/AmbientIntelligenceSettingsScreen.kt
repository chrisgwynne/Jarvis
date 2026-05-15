package com.jarvis.assistant.ui.settings.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ambient.AmbientSettings
import com.jarvis.assistant.ambient.RoutinePattern
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsSliderRow
import com.jarvis.assistant.ui.settings.SettingsTheme
import com.jarvis.assistant.ui.settings.SettingsToggleRow
import com.jarvis.assistant.ui.settings.SettingsValueRow
import kotlinx.coroutines.launch

/**
 * Ambient Intelligence settings — enables/disables the local ambient signal
 * pipeline, tunes confidence + nudge limits, and exposes diagnostics showing
 * learned routine patterns and recent ambient events.
 *
 * Simulation buttons inject test signals so the user can verify the pipeline
 * end-to-end before relying on it in daily use.
 */
@Composable
internal fun AmbientIntelligenceSettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repo    = JarvisApp.ambientSettings
    val emitter = JarvisApp.ambientEmitter
    val scope   = rememberCoroutineScope()
    val state   by repo.stateFlow.collectAsState()

    val patterns by produceState<List<RoutinePattern>>(initialValue = emptyList(), emitter) {
        value = emitter?.allPatterns() ?: emptyList()
    }
    var diagnosticsText by remember { mutableStateOf("") }

    SettingsScaffold(title = "Ambient Intelligence", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "How it works",
            body  = "Jarvis watches coarse-grained signals (location bucket, " +
                    "Bluetooth, app opens, Home Assistant states) and learns your " +
                    "daily patterns locally. No data leaves your phone. When a " +
                    "pattern reaches enough confidence, Jarvis surfaces one nudge " +
                    "per relevant moment — never a stream of alerts."
        )

        // ── Master toggle ────────────────────────────────────────────────────
        SettingsGroup {
            SettingsToggleRow(
                title       = "Ambient Intelligence",
                description = "Enable local signal observation and nudges",
                checked     = state.enabled,
                onCheckedChange = { repo.setEnabled(it) },
            )
        }

        // ── Category toggles ─────────────────────────────────────────────────
        if (state.enabled) {
            SettingsGroup {
                SettingsToggleRow(
                    title       = "Routine learning",
                    description = "Build time-of-day patterns from observed signals",
                    checked     = state.learningEnabled,
                    onCheckedChange = { repo.setLearningEnabled(it) },
                )
                SettingsRowDivider()
                SettingsToggleRow(
                    title       = "Location suggestions",
                    description = "Nudge when you're near a place on your task list",
                    checked     = state.locationSuggestionsEnabled,
                    onCheckedChange = { repo.setLocationSuggestionsEnabled(it) },
                )
                SettingsRowDivider()
                SettingsToggleRow(
                    title       = "Travel suggestions",
                    description = "Remind you to leave for calendar events in time",
                    checked     = state.travelSuggestionsEnabled,
                    onCheckedChange = { repo.setTravelSuggestionsEnabled(it) },
                )
                SettingsRowDivider()
                SettingsToggleRow(
                    title       = "App context nudges",
                    description = "Prompt about unread messages when you open Etsy or Shopify",
                    checked     = state.appContextSuggestionsEnabled,
                    onCheckedChange = { repo.setAppContextSuggestionsEnabled(it) },
                )
                SettingsRowDivider()
                SettingsToggleRow(
                    title       = "Home Assistant alerts",
                    description = "Warn when devices are running while you're away",
                    checked     = state.homeAssistantAlertsEnabled,
                    onCheckedChange = { repo.setHomeAssistantAlertsEnabled(it) },
                )
                SettingsRowDivider()
                SettingsToggleRow(
                    title       = "Customer-work nudges",
                    description = "Surface customer message reminders in work context",
                    checked     = state.customerWorkNudgesEnabled,
                    onCheckedChange = { repo.setCustomerWorkNudgesEnabled(it) },
                )
                SettingsRowDivider()
                SettingsToggleRow(
                    title       = "Learn from dismissals",
                    description = "Reduce confidence when you dismiss a nudge",
                    checked     = state.learnFromDismissalsEnabled,
                    onCheckedChange = { repo.setLearnFromDismissalsEnabled(it) },
                )
            }

            // ── Sensitivity ──────────────────────────────────────────────────
            SettingsGroup {
                SettingsSliderRow(
                    title       = "Minimum confidence to speak",
                    description = "Higher = fewer but more certain nudges " +
                                  "(${(state.minConfidenceToSpeak * 100).toInt()}%)",
                    value       = state.minConfidenceToSpeak,
                    valueRange  = 0.30f..0.95f,
                    steps       = 12,
                    onValueChange = { repo.setMinConfidenceToSpeak(it) },
                )
                SettingsRowDivider()
                SettingsSliderRow(
                    title       = "Max nudges per day",
                    description = "Hard cap across all ambient triggers (${state.maxNudgesPerDay})",
                    value       = state.maxNudgesPerDay.toFloat(),
                    valueRange  = 1f..30f,
                    steps       = 28,
                    onValueChange = { repo.setMaxNudgesPerDay(it.toInt()) },
                )
            }
        }

        // ── Diagnostics ──────────────────────────────────────────────────────
        SettingsGroup {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text       = "Learned patterns",
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                    color      = SettingsTheme.TextMuted,
                )
                Text(
                    text     = if (patterns.isEmpty()) "No patterns learned yet."
                               else patterns.joinToString("\n") { p ->
                                   val conf = (p.confidence * 100).toInt()
                                   "• ${p.description} — ${conf}% conf, " +
                                   "${p.seenCount} observations, ${p.dismissedCount} dismissed"
                               },
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    color    = SettingsTheme.TextPrimary,
                )
            }
        }

        if (diagnosticsText.isNotBlank()) {
            SettingsGroup {
                SettingsValueRow(title = "Last diagnostic", value = diagnosticsText)
            }
        }

        // ── Actions ──────────────────────────────────────────────────────────
        SettingsGroup {
            SettingsActionRow(
                title       = "Refresh pattern diagnostics",
                description = "Reload learned patterns from the database",
                actionLabel = "Refresh",
            ) {
                scope.launch {
                    val fresh = emitter?.allPatterns() ?: emptyList()
                    diagnosticsText = "Loaded ${fresh.size} patterns."
                    Toast.makeText(context, "Loaded ${fresh.size} patterns", Toast.LENGTH_SHORT).show()
                }
            }
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Reset learned routines",
                description = "Delete all pattern data and start fresh",
                actionLabel = "Reset",
            ) {
                scope.launch {
                    emitter?.resetLearnedRoutines()
                    diagnosticsText = "All patterns cleared."
                    Toast.makeText(context, "Routine patterns cleared", Toast.LENGTH_SHORT).show()
                }
            }
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Reset to defaults",
                description = "Restore all ambient settings to their original values",
                actionLabel = "Defaults",
            ) {
                repo.resetToDefaults()
                Toast.makeText(context, "Ambient settings reset to defaults", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
