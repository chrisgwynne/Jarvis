package com.jarvis.assistant.ui.settings.screens

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.todoist.TodoistClient
import com.jarvis.assistant.todoist.TodoistOfflineQueue
import com.jarvis.assistant.todoist.TodoistPriority
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsDropdownRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTextFieldRow
import com.jarvis.assistant.ui.settings.SettingsTheme
import com.jarvis.assistant.ui.settings.SettingsToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Todoist integration settings screen.
 *
 * Reads & writes via [JarvisApp.todoistSettings] (a singleton
 * [com.jarvis.assistant.todoist.TodoistSettingsRepository] mirroring the
 * pattern used for proactivity + feature flags).  The runtime's
 * [com.jarvis.assistant.todoist.TodoistReminderRouter] is wired against
 * the same repo so UI changes take effect on the next turn — no service
 * restart required.
 */
@Composable
internal fun TodoistSettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repo    = JarvisApp.todoistSettings
    val state   by repo.stateFlow.collectAsState()
    val scope   = rememberCoroutineScope()

    var testStatus by remember { mutableStateOf<String?>(null) }
    var queueDepth by remember { mutableStateOf(0) }

    // Refresh queue depth whenever the screen recomposes.
    remember(state) {
        scope.launch(Dispatchers.IO) {
            queueDepth = TodoistOfflineQueue(context).size()
        }
    }

    SettingsScaffold(title = "Todoist", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Reminders via Todoist",
            body  = "Turn this on and paste your API token to capture " +
                "reminders and tasks naturally — \"remind me to take bins " +
                "out tomorrow at 7\", \"todo call Mike\", etc.  Saved " +
                "offline and synced when you're back online.",
        )

        // ── Connection ──────────────────────────────────────────────────────
        SettingsGroup(
            title = "Connection",
            description = "Auth + reachability",
        ) {
            SettingsToggleRow(
                title = "Todoist enabled",
                description = "Master switch for the whole integration.",
                checked = state.enabled,
                onCheckedChange = { repo.setEnabled(it) },
            )
            SettingsRowDivider()
            SettingsTextFieldRow(
                title = "API token",
                description = "Personal token from todoist.com/app/settings/integrations/developer",
                value = state.apiToken,
                onValueChange = { repo.setApiToken(it) },
                placeholder = "0123…",
                isSecret = true,
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Test connection",
                description = testStatus ?: "Calls Todoist /projects to verify the token.",
                actionLabel = "Test",
                onAction    = {
                    testStatus = "Testing…"
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            TodoistClient(tokenProvider = { state.apiToken })
                                .testConnection()
                        }
                        // Status strings are sanitised so Gson / OkHttp /
                        // any Java exception class names never reach the
                        // UI.  The client's friendlyParseError() already
                        // produces a calm phrase for Malformed; we still
                        // run it through SpeechSanitizer.redact() as a
                        // belt-and-braces guard in case a future code
                        // path ever surfaces a raw stack hint here.
                        val sanitizer = com.jarvis.assistant.core.safety.SpeechSanitizer
                        testStatus = when (ok) {
                            is TodoistClient.Result.Ok           -> "Connected. ✓"
                            is TodoistClient.Result.AuthError    -> "Token rejected."
                            is TodoistClient.Result.RateLimited  -> "Rate limited — try again shortly."
                            is TodoistClient.Result.Offline      -> "Offline."
                            is TodoistClient.Result.ServerError  -> "Server error (HTTP ${ok.code})."
                            is TodoistClient.Result.Malformed    ->
                                sanitizer.redact(ok.message).take(80)
                        }
                    }
                },
            )
        }

        // ── Defaults ────────────────────────────────────────────────────────
        SettingsGroup(
            title = "Defaults",
            description = "Applied when the user doesn't specify them",
        ) {
            SettingsTextFieldRow(
                title = "Default project ID",
                description = "Empty = Inbox.  Use a project ID from Todoist (digits).",
                value = state.defaultProjectId,
                onValueChange = { repo.setDefaultProjectId(it) },
            )
            SettingsRowDivider()
            SettingsTextFieldRow(
                title = "Default labels",
                description = "Comma-separated.  Empty = none.",
                value = state.defaultLabels.joinToString(", "),
                onValueChange = { v ->
                    repo.setDefaultLabels(v.split(",").map { it.trim() })
                },
            )
            SettingsRowDivider()
            SettingsDropdownRow(
                title    = "Default priority",
                options  = TodoistPriority.entries,
                selected = state.defaultPriority,
                label    = { it.spoken },
                onSelected = { repo.setDefaultPriority(it) },
            )
            SettingsRowDivider()
            // Default reminder time — surfaced as text "HH:mm" for simplicity.
            val timeStr = "%02d:%02d".format(
                state.defaultReminderMinuteOfDay / 60,
                state.defaultReminderMinuteOfDay % 60,
            )
            Text(
                "Default reminder time: $timeStr",
                color = SettingsTheme.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ── Conversation behaviour ──────────────────────────────────────────
        SettingsGroup(
            title = "Conversation",
            description = "How the follow-up flow behaves",
        ) {
            SettingsToggleRow(
                title = "Ask for time when date is vague",
                description = "\"remind me tomorrow\" → \"what time?\"",
                checked = state.askForTimeWhenDateVague,
                onCheckedChange = { repo.setAskForTime(it) },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title = "Ask for label after creating reminder",
                checked = state.askForLabelAfterCreate,
                onCheckedChange = { repo.setAskForLabel(it) },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title = "Smart follow-up",
                description = "Use 60s memory for \"actually 9pm\" / \"put that in work\".",
                checked = state.smartFollowUpEnabled,
                onCheckedChange = { repo.setSmartFollowUp(it) },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title = "Voice confirmations",
                description = "Speak \"Done.\", \"Added to Todoist.\", etc.",
                checked = state.voiceConfirmationsEnabled,
                onCheckedChange = { repo.setVoiceConfirmations(it) },
            )
        }

        // ── Reminder behaviour ──────────────────────────────────────────────
        SettingsGroup(
            title = "Reminders",
            description = "Contextual + repeating",
        ) {
            SettingsToggleRow(
                title = "Contextual reminders",
                description = "\"when I get home\", \"next time I open Etsy\".",
                checked = state.contextualRemindersEnabled,
                onCheckedChange = { repo.setContextualReminders(it) },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title = "Repeating reminder nudges",
                description = "\"keep reminding me every 10 minutes\".",
                checked = state.repeatingReminderNudgesEnabled,
                onCheckedChange = { repo.setRepeatingNudges(it) },
            )
        }

        // ── Sync diagnostics ────────────────────────────────────────────────
        SettingsGroup(
            title = "Sync & diagnostics",
            description = "Offline queue, status, recovery",
        ) {
            SettingsToggleRow(
                title = "Offline sync",
                description = "Save tasks locally when Todoist is unreachable.",
                checked = state.offlineSyncEnabled,
                onCheckedChange = { repo.setOfflineSync(it) },
            )
            SettingsRowDivider()
            Text(
                "Pending offline operations: $queueDepth",
                color = SettingsTheme.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Drain offline queue now",
                description = "Replay queued operations against Todoist.",
                actionLabel = "Drain",
                onAction    = {
                    scope.launch {
                        val drained = withContext(Dispatchers.IO) {
                            val rt = com.jarvis.assistant.service.JarvisService.runtimeOrNull()
                            // Drain through the runtime when it's up; otherwise
                            // there's nothing to do — the queue replays
                            // automatically on the next reminder turn.
                            rt?.todoistDrainOfflineQueue() ?: 0
                        }
                        queueDepth = TodoistOfflineQueue(context).size()
                        Toast.makeText(
                            context,
                            if (drained > 0) "Drained $drained pending op(s)"
                            else "Nothing drained — Todoist may be offline",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Reset Todoist settings",
                description = "Restore the defaults from this screen.",
                actionLabel = "Reset",
                destructive = true,
                confirm     = true,
                onAction    = { repo.resetToDefaults() },
            )
        }
    }
}
