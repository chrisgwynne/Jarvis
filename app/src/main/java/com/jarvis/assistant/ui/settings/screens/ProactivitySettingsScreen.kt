package com.jarvis.assistant.ui.settings.screens

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.proactive.settings.InterruptionMode
import com.jarvis.assistant.proactive.settings.ProactiveEventsLogQuery
import com.jarvis.assistant.proactive.settings.ProactivitySensitivity
import com.jarvis.assistant.proactive.settings.ProactivitySettings
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsDropdownRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsSliderRow
import com.jarvis.assistant.ui.settings.SettingsTheme
import com.jarvis.assistant.ui.settings.SettingsToggleRow
import com.jarvis.assistant.ui.settings.SettingsValueRow
import com.jarvis.assistant.util.JarvisNotificationHelper
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Proactivity settings — the full set of user controls for when Jarvis
 * speaks up on its own.
 *
 * Backing store: [JarvisApp.proactivitySettings].  Every toggle / slider /
 * picker writes through the repository, which updates SharedPreferences AND
 * emits a new snapshot on its StateFlow; the runtime
 * [com.jarvis.assistant.proactive.settings.ProactivityGate] reads the same
 * snapshot on every dispatch so changes take effect immediately, no
 * service restart required.
 */
@Composable
internal fun ProactivitySettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repo    = JarvisApp.proactivitySettings
    val state   by repo.stateFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    SettingsScaffold(title = "Proactivity", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "When Jarvis speaks up",
            body  = "Master switch first.  Then pick categories, quiet hours, " +
                    "and how disruptive Jarvis is allowed to be.  Changes take " +
                    "effect immediately — no restart needed.",
        )

        // ── Master switch ────────────────────────────────────────────────────
        SettingsGroup(
            title = "Main control",
            description = "Master switch for every proactive surface",
        ) {
            SettingsToggleRow(
                title = "Proactivity enabled",
                description = "When off, Jarvis won't speak, notify, or suggest " +
                    "anything unprompted.  Learning continues silently.",
                checked = state.enabled,
                onCheckedChange = { repo.setEnabled(it) },
            )
        }

        // ── Quiet hours ──────────────────────────────────────────────────────
        SettingsGroup(
            title = "Quiet hours",
            description = "A nightly window where Jarvis stays silent",
        ) {
            SettingsToggleRow(
                title = "Quiet hours enabled",
                checked = state.quietHoursEnabled,
                onCheckedChange = { repo.setQuietHoursEnabled(it) },
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "Starts at",
                value       = formatMinute(state.quietStartMinute),
                description = "Tap to change",
                onClick     = {
                    showTimePicker(
                        context,
                        initial = state.quietStartMinute,
                        onPicked = { mins -> repo.setQuietStartMinute(mins) },
                    )
                },
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "Ends at",
                value       = formatMinute(state.quietEndMinute),
                description = if (state.quietEndMinute <= state.quietStartMinute)
                    "Wraps overnight (start ≥ end is OK)"
                else null,
                onClick     = {
                    showTimePicker(
                        context,
                        initial = state.quietEndMinute,
                        onPicked = { mins -> repo.setQuietEndMinute(mins) },
                    )
                },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title       = "Allow urgent interruptions",
                description = "Critical events (low battery, imminent meetings, " +
                    "urgent reminders) may still break through quiet hours.",
                checked = state.allowUrgentDuringQuietHours,
                onCheckedChange = { repo.setAllowUrgentDuringQuietHours(it) },
            )
        }

        // ── Categories ───────────────────────────────────────────────────────
        SettingsGroup(
            title = "Categories",
            description = "Pick the kinds of event Jarvis is allowed to surface",
        ) {
            CategoryToggle(
                title = "Suggestions",
                description = "Notification summaries, ambient context tips",
                checked = state.suggestionsEnabled,
                onChange = repo::setSuggestionsEnabled,
            )
            SettingsRowDivider()
            CategoryToggle(
                title = "Reminders",
                description = "Upcoming reminders that you've scheduled",
                checked = state.remindersEnabled,
                onChange = repo::setRemindersEnabled,
            )
            SettingsRowDivider()
            CategoryToggle(
                title = "Location / place alerts",
                description = "Arrived home, left home, arrived at a known place",
                checked = state.locationAlertsEnabled,
                onChange = repo::setLocationAlertsEnabled,
            )
            SettingsRowDivider()
            CategoryToggle(
                title = "Home Assistant alerts",
                description = "Motion / camera / doorbell events.  Off by default.",
                checked = state.homeAssistantAlertsEnabled,
                onChange = repo::setHomeAssistantAlertsEnabled,
            )
            SettingsRowDivider()
            CategoryToggle(
                title = "Calendar / event nudges",
                description = "Upcoming meetings, daily agenda, imminent events",
                checked = state.calendarNudgesEnabled,
                onChange = repo::setCalendarNudgesEnabled,
            )
            SettingsRowDivider()
            CategoryToggle(
                title = "Learning observations",
                description = "Behavioural insights from your patterns",
                checked = state.learningObservationsEnabled,
                onChange = repo::setLearningObservationsEnabled,
            )
            SettingsRowDivider()
            CategoryToggle(
                title = "Safety / security alerts",
                description = "Low battery, missed calls, unfamiliar networks",
                checked = state.safetySecurityAlertsEnabled,
                onChange = repo::setSafetySecurityAlertsEnabled,
            )
        }

        // ── Interruption mode ────────────────────────────────────────────────
        SettingsGroup(
            title = "Interruption style",
            description = "How disruptive Jarvis is allowed to be",
        ) {
            SettingsDropdownRow(
                title = "When something matters",
                options = InterruptionMode.entries,
                selected = state.interruptionMode,
                label = { it.displayLabel },
                onSelected = { repo.setInterruptionMode(it) },
            )
            SettingsRowDivider()
            Text(
                text = state.interruptionMode.description,
                color = SettingsTheme.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ── Sensitivity ──────────────────────────────────────────────────────
        SettingsGroup(
            title = "Sensitivity",
            description = "Fewer events ←——→ more events",
        ) {
            val current = state.sensitivity
            val sliderValue = when (current) {
                ProactivitySensitivity.LOW    -> 0f
                ProactivitySensitivity.MEDIUM -> 1f
                ProactivitySensitivity.HIGH   -> 2f
            }
            SettingsSliderRow(
                title = "Threshold",
                description = "Currently: ${current.displayLabel}",
                value = sliderValue,
                onValueChange = { v ->
                    val snapped = when {
                        v < 0.5f -> ProactivitySensitivity.LOW
                        v < 1.5f -> ProactivitySensitivity.MEDIUM
                        else     -> ProactivitySensitivity.HIGH
                    }
                    if (snapped != current) repo.setSensitivity(snapped)
                },
                valueRange = 0f..2f,
                steps = 1,
                valueLabel = { v ->
                    when {
                        v < 0.5f -> "Low"
                        v < 1.5f -> "Medium"
                        else     -> "High"
                    }
                },
            )
        }

        // ── Cooldown ─────────────────────────────────────────────────────────
        SettingsGroup(
            title = "Cooldowns",
            description = "Minimum gap between proactive messages",
        ) {
            SettingsSliderRow(
                title = "Global cooldown",
                description = "Minutes between any two proactive surfacings — " +
                    "currently ${state.globalCooldownMinutes} min",
                value = state.globalCooldownMinutes.toFloat(),
                onValueChange = { v -> repo.setGlobalCooldownMinutes(v.toInt()) },
                valueRange = 1f..120f,
                steps = 0,
                valueLabel = { "${it.toInt()} min" },
            )
        }

        // ── Scheduled reminders (Calendar / Todoist / local) ─────────────────
        ScheduledRemindersGroup()

        // ── Diagnostics / test ───────────────────────────────────────────────
        DiagnosticsGroup(
            state = state,
            onTestNotification = {
                JarvisNotificationHelper.postProactiveAlert(
                    context = context,
                    title   = "Jarvis — test notification",
                    body    = "If you see this, notification delivery is working.",
                )
                Toast.makeText(context, "Notification posted", Toast.LENGTH_SHORT).show()
            },
            onTestSpoken = {
                // Reach into the live runtime so the test actually invokes
                // the TTS engine.  Bypasses the Proactivity gate by design
                // — the whole point of this button is to verify the speech
                // path is alive even when normal proactive output would be
                // suppressed.  See JarvisRuntime.speakProactivityTest.
                val runtime = com.jarvis.assistant.service.JarvisService.runtimeOrNull()
                if (runtime == null) {
                    Toast.makeText(
                        context,
                        "Jarvis service isn't running — start it first.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    runtime.speakProactivityTest { failure ->
                        // The callback fires on whatever dispatcher
                        // ttsEngine.speak returns on; bounce to the main
                        // thread for the Toast.
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .post {
                                Toast.makeText(
                                    context,
                                    failure ?: "Spoken test sent.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                }
            },
            onTestNormalProactivity = {
                // Run the same test message through the live Proactivity
                // gate so the user can see exactly which gate (if any)
                // would suppress a real proactive event right now.
                val runtime = com.jarvis.assistant.service.JarvisService.runtimeOrNull()
                if (runtime == null) {
                    Toast.makeText(context,
                        "Jarvis service isn't running — start it first.",
                        Toast.LENGTH_LONG).show()
                } else {
                    runtime.dispatchProactivityGateTest { verdict ->
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .post {
                                Toast.makeText(context, verdict, Toast.LENGTH_LONG).show()
                            }
                    }
                }
            },
            onResetDefaults = { repo.resetToDefaults() },
        )
    }
}

/**
 * Scheduled reminders settings — per-source enables + 30m/10m offsets +
 * notify-fallback + background-speech.  Backed by
 * [JarvisApp.scheduledReminderSettings] so writes propagate to the
 * engine without a restart.
 */
@Composable
private fun ScheduledRemindersGroup() {
    val repo = JarvisApp.scheduledReminderSettings
    val state by repo.stateFlow.collectAsState()
    SettingsGroup(
        title = "Scheduled reminders",
        description = "Calendar / Todoist / local items get a heads-up " +
            "before they're due.  Each reminder goes through the Proactivity " +
            "gate above, so quiet hours / interruption mode / category " +
            "toggles all apply.",
    ) {
        SettingsToggleRow(
            title = "Calendar event reminders",
            description = "Speak 30 / 10 minutes before each scheduled event.",
            checked = state.calendarEnabled,
            onCheckedChange = repo::setCalendarEnabled,
        )
        SettingsRowDivider()
        SettingsToggleRow(
            title = "Todoist task reminders",
            description = "Speak 30 / 10 minutes before each due Todoist task.",
            checked = state.todoistEnabled,
            onCheckedChange = repo::setTodoistEnabled,
        )
        SettingsRowDivider()
        SettingsToggleRow(
            title = "Local reminder pre-warnings",
            description = "Speak 30 / 10 minutes before each Jarvis reminder.",
            checked = state.localEnabled,
            onCheckedChange = repo::setLocalEnabled,
        )
        SettingsRowDivider()
        SettingsToggleRow(
            title = "Speak 30 minutes before",
            description = "The earlier of the two pre-warnings.",
            checked = state.offset30mEnabled,
            onCheckedChange = repo::setOffset30mEnabled,
        )
        SettingsRowDivider()
        SettingsToggleRow(
            title = "Speak 10 minutes before",
            description = "The final pre-warning.",
            checked = state.offset10mEnabled,
            onCheckedChange = repo::setOffset10mEnabled,
        )
        SettingsRowDivider()
        SettingsToggleRow(
            title = "Notify if speech suppressed",
            description = "Post a notification when the gate would block the spoken reminder.",
            checked = state.notifyFallbackEnabled,
            onCheckedChange = repo::setNotifyFallbackEnabled,
        )
        SettingsRowDivider()
        SettingsToggleRow(
            title = "Allow reminders while idle",
            description = "Off (default): only speak when you've recently interacted with Jarvis. " +
                "On: speak even when no recent voice activity.",
            checked = state.backgroundSpeechEnabled,
            onCheckedChange = repo::setBackgroundSpeechEnabled,
        )
    }
}

@Composable
private fun CategoryToggle(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsToggleRow(
        title = title,
        description = description,
        checked = checked,
        onCheckedChange = onChange,
    )
}

/**
 * Diagnostics block: test buttons + last-10 proactive decisions read off
 * [JarvisApp]'s decision-trace store via the runtime accessor.  When
 * JarvisRuntime hasn't been initialised (e.g. service not started yet)
 * we surface a friendly "no traces yet" line rather than crashing.
 */
@Composable
private fun DiagnosticsGroup(
    state: ProactivitySettings,
    onTestNotification: () -> Unit,
    onTestSpoken: () -> Unit,
    onTestNormalProactivity: () -> Unit,
    onResetDefaults: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var recent by remember { mutableStateOf<List<ProactiveEventsLogQuery.Entry>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Lazy-load on first composition so the screen stays responsive even
    // when DB I/O is slow.  Re-run when the user taps "Refresh".
    LaunchedEffect(Unit) {
        loadRecent(
            onSuccess = { recent = it; loadError = null },
            onError   = { loadError = it },
        )
    }

    SettingsGroup(
        title = "Diagnostics",
        description = "Test the delivery path and see what's been surfaced",
    ) {
        SettingsActionRow(
            title       = "Test notification",
            description = "Posts a notification through the proactive channel.",
            actionLabel = "Send",
            onAction    = onTestNotification,
        )
        SettingsRowDivider()
        SettingsActionRow(
            title       = "Test spoken message now",
            description = "Speaks a sample line immediately, bypassing every " +
                "Proactivity gate so you can verify TTS is alive.",
            actionLabel = "Speak",
            onAction    = onTestSpoken,
        )
        SettingsRowDivider()
        SettingsActionRow(
            title       = "Test normal proactivity decision",
            description = "Runs the gate without speaking — tells you whether " +
                "a real proactive message would speak, notify, or be suppressed " +
                "right now, and why.",
            actionLabel = "Run",
            onAction    = onTestNormalProactivity,
        )
        SettingsRowDivider()
        SettingsActionRow(
            title       = "Reset every Proactivity setting",
            description = "Restores the defaults from this screen.",
            actionLabel = "Reset",
            destructive = true,
            confirm     = true,
            onAction    = onResetDefaults,
        )
    }

    SettingsGroup(
        title = "Last 10 proactive events",
        description = "Time, type, final score, decision",
    ) {
        SettingsActionRow(
            title       = "Refresh",
            description = "Re-read the recent decision trace.",
            actionLabel = "Refresh",
            onAction    = {
                scope.launch {
                    loadRecent(
                        onSuccess = { recent = it; loadError = null },
                        onError   = { loadError = it },
                    )
                }
            },
        )
        SettingsRowDivider()
        when {
            loadError != null -> Text(
                "Couldn't read decision trace: $loadError",
                color = SettingsTheme.Destructive,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
            recent.isEmpty() -> Text(
                "No proactive decisions recorded yet.",
                color = SettingsTheme.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
            else -> Column(modifier = Modifier.fillMaxWidth()) {
                recent.forEachIndexed { idx, entry ->
                    EventLogRow(entry)
                    if (idx != recent.lastIndex) SettingsRowDivider()
                }
            }
        }
    }
}

@Composable
private fun EventLogRow(entry: ProactiveEventsLogQuery.Entry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = entry.eventType ?: "(no event)",
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
            Text(
                text = entry.decisionLabel,
                fontSize = 12.sp,
                color = when (entry.decisionLabel) {
                    "spoke"    -> SettingsTheme.Success
                    "notified" -> SettingsTheme.Cyan
                    "suppressed", "silent" -> SettingsTheme.TextMuted
                    else -> SettingsTheme.TextMuted
                },
            )
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
            .format(Date(entry.createdAtMs))
        val scoreStr = entry.finalScore?.let { "score %.2f".format(it) } ?: "—"
        val reasonStr = entry.suppressionReason?.let { " · $it" } ?: ""
        Text(
            text = "$time · $scoreStr$reasonStr",
            color = SettingsTheme.TextMuted,
            fontSize = 11.sp,
        )
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Pop a system TimePickerDialog seeded with [initial] minutes-from-midnight. */
private fun showTimePicker(
    context: android.content.Context,
    initial: Int,
    onPicked: (Int) -> Unit,
) {
    val hour   = (initial / 60) % 24
    val minute = initial % 60
    TimePickerDialog(
        context,
        { _, h, m -> onPicked(h * 60 + m) },
        hour,
        minute,
        true,   // 24-hour view; the displayed value uses the device locale
    ).show()
}

private fun formatMinute(min: Int): String {
    val h = (min / 60) % 24
    val m = min % 60
    return "%02d:%02d".format(h, m)
}

/**
 * Fetch the last 10 proactive decisions off the DecisionTraceStore via the
 * running [com.jarvis.assistant.runtime.JarvisRuntime] (when available).
 * The UI never holds a JarvisRuntime reference directly — we read through
 * the service-bound singleton instead.
 */
private suspend fun loadRecent(
    onSuccess: (List<ProactiveEventsLogQuery.Entry>) -> Unit,
    onError:   (String) -> Unit,
) {
    runCatching {
        val rt = com.jarvis.assistant.service.JarvisService.runtimeOrNull()
            ?: return@runCatching emptyList<ProactiveEventsLogQuery.Entry>()
        rt.proactiveEventsLogQuery.recent(10)
    }
        .onSuccess(onSuccess)
        .onFailure { onError(it.message ?: it.javaClass.simpleName) }
}
