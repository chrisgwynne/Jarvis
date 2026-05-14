package com.jarvis.assistant.proactive.scheduled

import android.util.Log
import com.jarvis.assistant.proactive.ProactiveAction
import com.jarvis.assistant.proactive.ProactiveDispatcher
import com.jarvis.assistant.proactive.ProactiveEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ScheduledReminderDispatchBridge — translates a [ProactiveEvent] from
 * [ScheduledReminderEngine] into a [ProactiveAction] and hands it to
 * the shared [ProactiveDispatcher].
 *
 * The dispatcher already runs the [com.jarvis.assistant.proactive
 * .settings.ProactivityGate] (master switch / categories / quiet hours
 * / interruption mode / cooldown) so going through it means scheduled
 * reminders honour every existing user policy *for free*.
 *
 * What this bridge adds on top of the gate:
 *   • When background-speech is disabled and the user hasn't recently
 *     interacted with Jarvis, downgrades SpeakAction → PassiveAction so
 *     reminders surface as silent notifications rather than echoing
 *     into an empty room.
 *   • Honours the per-engine `notifyFallback` flag — when disabled,
 *     suppressed speech is dropped entirely rather than notified.
 *
 * The bridge does **not** mutate the [ScheduledReminderInstanceStore];
 * the engine already marked the instance fired before invoking the
 * sink.  This makes the bridge a pure conversion + dispatch step.
 */
class ScheduledReminderDispatchBridge(
    private val dispatcher: ProactiveDispatcher,
    private val settingsProvider: () -> ScheduledReminderSettings,
    /**
     * Wall-clock ms of the most recent user voice interaction with
     * Jarvis, or null when never.  Used together with
     * [backgroundSpeechWindowMs] to decide whether speech is allowed.
     */
    private val lastInteractionMs: () -> Long? = { null },
    private val backgroundSpeechWindowMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Hook invoked after every dispatch attempt with the (event,
     * verdict) pair so the diagnostics screen / unit tests can observe
     * exactly what happened.  Verdict is one of: "spoke", "notified",
     * "dropped:<reason>".  Default no-op.
     */
    private val onDispatch: (ProactiveEvent, String) -> Unit = { _, _ -> },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Entry point — pass a freshly-fired event from
     * [ScheduledReminderEngine].  Fire-and-forget: returns immediately,
     * dispatch runs on a supervisor scope.
     */
    fun handle(event: ProactiveEvent) {
        scope.launch {
            try {
                val action = toAction(event)
                Log.d(TAG, "[SCHED_BRIDGE_DISPATCH] event=${event.type} " +
                    "action=${action::class.simpleName} dedupeKey=${event.dedupeKey}")
                dispatcher.dispatch(action)
                onDispatch(event, when (action) {
                    is ProactiveAction.SpeakAction   -> "spoke"
                    is ProactiveAction.PassiveAction -> "notified"
                    is ProactiveAction.NoAction      -> "dropped:no_action"
                })
            } catch (t: Throwable) {
                Log.w(TAG, "Dispatch failed for ${event.dedupeKey}", t)
                onDispatch(event, "dropped:exception:${t.message ?: "unknown"}")
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun toAction(event: ProactiveEvent): ProactiveAction {
        val s = settingsProvider()
        val text = event.spokenText
            ?: return ProactiveAction.NoAction()
        // Decide speech vs notify.  Background-speech off → user must
        // have interacted within [backgroundSpeechWindowMs] for us to
        // speak, otherwise downgrade to notification (if allowed).
        val active = isUserActive()
        val mayBackgroundSpeak = s.backgroundSpeechEnabled || active
        return if (mayBackgroundSpeak) {
            ProactiveAction.SpeakAction(
                text       = text,
                dedupeKey  = event.dedupeKey,
                sourceType = event.type,
            )
        } else if (s.notifyFallbackEnabled) {
            ProactiveAction.PassiveAction(
                title      = event.title,
                body       = text,
                dedupeKey  = event.dedupeKey,
                sourceType = event.type,
            )
        } else {
            Log.d(TAG, "[SCHED_BRIDGE_DROPPED] reason=background_speech_off_and_no_notify " +
                "dedupeKey=${event.dedupeKey}")
            ProactiveAction.NoAction()
        }
    }

    private fun isUserActive(): Boolean {
        val last = lastInteractionMs() ?: return false
        return (clock() - last) <= backgroundSpeechWindowMs
    }

    companion object { private const val TAG = "SchedReminderBridge" }
}
