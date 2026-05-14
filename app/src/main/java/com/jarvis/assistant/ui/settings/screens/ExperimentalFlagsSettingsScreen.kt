package com.jarvis.assistant.ui.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTheme
import com.jarvis.assistant.ui.settings.SettingsToggleRow
import com.jarvis.assistant.voice.VoiceFeatureFlags

/**
 * "Experimental Jarvis Features" settings screen.
 *
 * Surfaces every entry in [VoiceFeatureFlags.Flag] as a toggleable row
 * backed by [JarvisApp.featureFlagStore].  Toggles persist across app
 * restarts via SharedPreferences; defaults remain the source of truth
 * until the user explicitly overrides them.
 *
 * Subsystems that read flags via `VoiceFeatureFlags.isEnabled(...)` need
 * **no changes** — the store mirrors persisted overrides into
 * `VoiceFeatureFlags`' in-memory map at app startup.
 *
 * Behaviour-sensitive flags are grouped so the user can see what they're
 * actually toggling rather than a flat alphabetical wall.
 */
@Composable
internal fun ExperimentalFlagsSettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val store = JarvisApp.featureFlagStore
    // Recompose on every override change.
    val overrides by store.overridesFlow.collectAsState()

    SettingsScaffold(title = "Experimental Features", onBack = onBack, onClose = onClose) {
        SettingsInfoCard(
            body = "Some features are experimental. Toggle one at a time and " +
                "restart Jarvis if behaviour seems odd."
        )

        FlagSection(
            title       = "Voice",
            description = "Always-listening, attention, barge-in",
            store       = store,
            overrides   = overrides,
            flags       = VOICE_GROUP
        )

        FlagSection(
            title       = "Routing",
            description = "Local-first / OpenClaw decision boundary",
            store       = store,
            overrides   = overrides,
            flags       = ROUTING_GROUP
        )

        FlagSection(
            title       = "Context & Modes",
            description = "Ambient context, mode switching, executive",
            store       = store,
            overrides   = overrides,
            flags       = CONTEXT_GROUP
        )

        FlagSection(
            title       = "Speech (TTS / STT)",
            description = "Streaming TTS, remote Whisper",
            store       = store,
            overrides   = overrides,
            flags       = SPEECH_GROUP
        )

        FlagSection(
            title       = "Adaptive wake word",
            description = "Per-environment wake threshold tuning",
            store       = store,
            overrides   = overrides,
            flags       = WAKE_GROUP
        )

        FlagSection(
            title       = "Messaging",
            description = "WhatsApp / SMS behaviour",
            store       = store,
            overrides   = overrides,
            flags       = MESSAGING_GROUP
        )

        SettingsGroup(title = "Reset", description = "Restore defaults") {
            SettingsActionRow(
                title       = "Reset every flag to its default",
                description = "Clears all overrides — restart Jarvis after to apply.",
                actionLabel = "Reset",
                destructive = true,
                confirm     = true,
                onAction    = { store.clearAll() }
            )
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Sectioned flag groups
 * ──────────────────────────────────────────────────────────────────────── */

private val VOICE_GROUP = listOf(
    FlagRow(VoiceFeatureFlags.Flag.FAST_VOICE_PIPELINE_ENABLED,
        "Fast voice pipeline", "Chime-async, mic retry, fast VAD"),
    FlagRow(VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED,
        "Attention gate", "Ignore overheard human conversation in always-listening"),
    FlagRow(VoiceFeatureFlags.Flag.VOICE_BARGE_IN_ENABLED,
        "Barge-in", "Detect user speech mid-TTS and stop speaking"),
    FlagRow(VoiceFeatureFlags.Flag.VOICE_VOCABULARY_BIAS_ENABLED,
        "Vocabulary bias", "Score STT candidates against known words"),
    FlagRow(VoiceFeatureFlags.Flag.VOICE_ALIAS_LEARNING_ENABLED,
        "Alias learning", "Remember 'no I meant X' corrections"),
    FlagRow(VoiceFeatureFlags.Flag.VOICE_CONFIDENCE_CONFIRMATION_ENABLED,
        "Confidence confirmation", "Echo medium-confidence risky actions before executing"),
    FlagRow(VoiceFeatureFlags.Flag.VOICE_STREAMING_STT_ENABLED,
        "Streaming STT", "Partial transcripts drive early intent"),
    FlagRow(VoiceFeatureFlags.Flag.REMOTE_WHISPER_STT_ENABLED,
        "Remote Whisper STT", "Stream audio to your Tailscale Whisper endpoint"),
)

private val ROUTING_GROUP = listOf(
    FlagRow(VoiceFeatureFlags.Flag.LOCAL_FIRST_ROUTING_ENABLED,
        "Local-first routing", "Try device tools before OpenClaw"),
    FlagRow(VoiceFeatureFlags.Flag.VOICE_GRAMMAR_ROUTER_ENABLED,
        "Voice grammar router", "Explicit grammar pass before generic matchers"),
    FlagRow(VoiceFeatureFlags.Flag.COMMAND_GRAMMAR_ROUTER_ENABLED,
        "Command grammar router", "Spec alias of the voice grammar router"),
    FlagRow(VoiceFeatureFlags.Flag.OPENCLAW_AGENT_ROUTING_ENABLED,
        "OpenClaw agent routing", "Multi-agent split on the OpenClaw side"),
)

private val CONTEXT_GROUP = listOf(
    FlagRow(VoiceFeatureFlags.Flag.AMBIENT_CONTEXT_ENABLED,
        "Ambient context aggregator", "Single context snapshot every 5s"),
    FlagRow(VoiceFeatureFlags.Flag.JARVIS_MODES_ENABLED,
        "Jarvis modes", "Auto-switch NORMAL/DRIVING/NIGHT based on context"),
    FlagRow(VoiceFeatureFlags.Flag.PROACTIVE_ENGINE_ENABLED,
        "Proactive engine", "Suggestions, reminders, ambient awareness"),
    FlagRow(VoiceFeatureFlags.Flag.EXECUTIVE_CONTROLLER_ENABLED,
        "Executive controller", "Task / goal / busy-check gating for proactive speech"),
    FlagRow(VoiceFeatureFlags.Flag.MEMORY_GRAPH_ENABLED,
        "Memory graph", "Entity / relation / fact store (scaffold only)"),
    FlagRow(VoiceFeatureFlags.Flag.NOTIFICATION_INTELLIGENCE_ENABLED,
        "Notification intelligence", "Filter HA / motion / camera alerts"),
)

private val SPEECH_GROUP = listOf(
    FlagRow(VoiceFeatureFlags.Flag.STREAMING_TTS_ENABLED,
        "Streaming TTS", "Sentence-chunked TTS with provider chain"),
)

private val WAKE_GROUP = listOf(
    FlagRow(VoiceFeatureFlags.Flag.ADAPTIVE_WAKE_THRESHOLD_ENABLED,
        "Adaptive wake threshold", "Raise wake threshold when ambient is noisy"),
)

private val MESSAGING_GROUP = listOf(
    FlagRow(VoiceFeatureFlags.Flag.WHATSAPP_AUTO_SEND_ENABLED,
        "WhatsApp auto-send",
        "Tap Send automatically — needs Jarvis Accessibility permission"),
    FlagRow(VoiceFeatureFlags.Flag.MESSAGING_NOTIFICATION_ANNOUNCE_ENABLED,
        "Announce messaging notifications",
        "Speak inbound WhatsApp / Signal / Telegram / Messages notifications aloud"),
)

private data class FlagRow(
    val flag: VoiceFeatureFlags.Flag,
    val displayName: String,
    val description: String
)

@Composable
private fun FlagSection(
    title:        String,
    description:  String,
    store:        com.jarvis.assistant.voice.FeatureFlagStore,
    overrides:    Map<String, Boolean?>,
    flags:        List<FlagRow>
) {
    if (flags.isEmpty()) return
    SettingsGroup(title = title, description = description) {
        flags.forEachIndexed { idx, row ->
            val override = overrides[row.flag.name]
            val effective = override ?: row.flag.defaultEnabled
            val descSuffix = buildString {
                append(row.description)
                append(" — default: ")
                append(if (row.flag.defaultEnabled) "on" else "off")
                if (override != null) append(" · overridden")
            }
            SettingsToggleRow(
                title           = row.displayName,
                description     = descSuffix,
                checked         = effective,
                onCheckedChange = { newValue ->
                    if (newValue == row.flag.defaultEnabled) {
                        // Setting to default is the same as clearing the override.
                        store.clearOverride(row.flag)
                    } else {
                        store.setOverride(row.flag, newValue)
                    }
                }
            )
            if (idx != flags.lastIndex) SettingsRowDivider()
        }
    }
}
