package com.jarvis.assistant.ui.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.personality.JokeFrequency
import com.jarvis.assistant.personality.SarcasmLevel
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsDropdownRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsToggleRow

/**
 * Personality settings — exposes the user-tunable knobs that flow
 * through [com.jarvis.assistant.personality.PersonalityPromptSelector]
 * and [com.jarvis.assistant.personality.template.LocalResponseTemplateEngine].
 *
 * The *content* of Jarvis's voice lives in markdown files under
 * `app/src/main/assets/personality/` (loaded by
 * [com.jarvis.assistant.personality.PersonalityProfileLoader]).  This
 * screen is for the *policy* — when humour fires, how often, and
 * whether it applies to a given surface.
 */
@Composable
internal fun PersonalitySettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val repo  = JarvisApp.personalitySettings
    val state by repo.stateFlow.collectAsState()

    SettingsScaffold(title = "Personality", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "How Jarvis sounds",
            body  = "The content lives in `assets/personality/`.  These " +
                    "switches decide when humour fires, how often, and " +
                    "which surfaces it applies to.  Changes take effect " +
                    "immediately — no restart needed.",
        )

        SettingsGroup(title = "Main control", description = "Master switch") {
            SettingsToggleRow(
                title           = "Personality enabled",
                description     = "Master switch.  Off = strict assistant tone everywhere.",
                checked         = state.enabled,
                onCheckedChange = repo::setEnabled,
            )
        }

        SettingsGroup(
            title = "Tone",
            description = "Sarcasm level + joke frequency",
        ) {
            SettingsDropdownRow(
                title       = "Sarcasm level",
                description = "How dry Jarvis gets to be by default.",
                options     = SarcasmLevel.values().toList(),
                selected    = state.sarcasm,
                label       = { it.displayLabel },
                onSelected  = repo::setSarcasm,
            )
            SettingsRowDivider()
            SettingsDropdownRow(
                title       = "Joke frequency",
                description = "How often a one-liner sneaks into a confirmation.",
                options     = JokeFrequency.values().toList(),
                selected    = state.jokeFrequency,
                label       = { it.displayLabel },
                onSelected  = repo::setJokeFrequency,
            )
        }

        SettingsGroup(title = "Behaviour", description = "Pushback and roasting") {
            SettingsToggleRow(
                title           = "Pushback enabled",
                description     = "Ask clarifying questions for ambiguous commands.",
                checked         = state.pushbackEnabled,
                onCheckedChange = repo::setPushbackEnabled,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Friendly roasting",
                description     = "Allow mild, affectionate jabs (\"future you owes me one\").",
                checked         = state.friendlyRoastingEnabled,
                onCheckedChange = repo::setFriendlyRoastingEnabled,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Serious-mode auto-detect",
                description     = "Detect emergency / medical / distress / safety language and " +
                    "suppress humour automatically.",
                checked         = state.seriousModeAutoDetectEnabled,
                onCheckedChange = repo::setSeriousModeAutoDetect,
            )
        }

        SettingsGroup(
            title = "Where personality applies",
            description = "Per-surface enables",
        ) {
            SettingsToggleRow(
                title           = "Apply to proactive reminders",
                description     = "Use the proactivity-style voice for 30/10-minute reminders.",
                checked         = state.applyToProactiveReminders,
                onCheckedChange = repo::setApplyToProactiveReminders,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Apply to local confirmations",
                description     = "Pick witty replies from the local template engine when allowed.",
                checked         = state.applyToLocalConfirmations,
                onCheckedChange = repo::setApplyToLocalConfirmations,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Apply to LLM answers",
                description     = "Inject the LLM_CHAT personality block in the system prompt.",
                checked         = state.applyToLlmAnswers,
                onCheckedChange = repo::setApplyToLlmAnswers,
            )
        }

        SettingsGroup(title = "Reset", description = "Restore defaults") {
            SettingsActionRow(
                title       = "Reset personality settings",
                description = "Restores the defaults from this screen.",
                actionLabel = "Reset",
                destructive = true,
                confirm     = true,
                onAction    = repo::resetToDefaults,
            )
        }
    }
}
