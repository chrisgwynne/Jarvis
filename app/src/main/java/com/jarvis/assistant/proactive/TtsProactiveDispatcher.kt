package com.jarvis.assistant.proactive

import android.content.Context
import android.util.Log
import com.jarvis.assistant.audio.TtsEngine
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.util.JarvisNotificationHelper

/**
 * TtsProactiveDispatcher — the production [ProactiveDispatcher] implementation.
 *
 * ## Dispatch behaviour
 *
 * | Action type      | Voice enabled | Behaviour                                     |
 * |------------------|---------------|-----------------------------------------------|
 * | SpeakAction      | true          | Calls [TtsEngine.speak] with the event text   |
 * | SpeakAction      | false         | Posts a notification via [JarvisNotificationHelper] |
 * | PassiveAction    | —             | Posts a notification via [JarvisNotificationHelper] |
 * | NoAction         | —             | No-op (should not reach dispatch)             |
 *
 * @param context              Application or service context for posting notifications.
 * @param ttsEngine            TTS engine used for spoken delivery.
 * @param onPassiveAction      Optional hook invoked for every [ProactiveAction.PassiveAction]
 *                             (useful for testing or updating in-app UI state).
 * @param voiceResponseEnabled Lambda that returns the current voice-response setting; consulted
 *                             on every dispatch so runtime setting changes are respected.
 */
class TtsProactiveDispatcher(
    context: Context,
    private val ttsEngine: TtsEngine,
    private val onPassiveAction: (ProactiveAction.PassiveAction) -> Unit = {},
    private val voiceResponseEnabled: () -> Boolean = { true },
    /**
     * Tier-C executive gate.  When supplied, every [SpeakAction] is
     * funnelled through `decide()` first; verdicts of SILENT_NOTIFY / WAIT
     * / IGNORE / ASK_CONFIRMATION downgrade the action to a notification
     * (or drop it entirely) instead of speaking it.  Null → legacy behaviour.
     */
    private val executive: com.jarvis.assistant.executive.ExecutiveController? = null,
    /**
     * Tier-C mode provider.  Returns the current [com.jarvis.assistant.modes.JarvisMode]
     * (or NORMAL fallback when modes are disabled).  Used to enforce
     * per-mode `proactiveSpeechAllowed` and `proactiveUrgencyMin`.
     */
    private val modeProvider: () -> com.jarvis.assistant.modes.JarvisMode =
        { com.jarvis.assistant.modes.JarvisMode.NORMAL },
    /**
     * User-visible Proactivity policy gate (master switch, categories,
     * quiet hours, interruption mode, global cooldown).  When null the
     * dispatcher behaves as before — useful for tests and the secondary
     * brain dispatcher that doesn't need the user policy.
     */
    private val proactivityGate: com.jarvis.assistant.proactive.settings.ProactivityGate? = null,
    /**
     * Provider of the wall-clock ms of the most recent user voice
     * interaction.  Consulted only when [proactivityGate] is non-null and
     * the user's interruption mode is SPEAK_WHEN_ACTIVE.
     */
    private val lastUserInteractionMs: () -> Long? = { null },
) : ProactiveDispatcher {

    // Never store the raw service context — use applicationContext so a suspended dispatch
    // coroutine doesn't prevent JarvisService from being GC'd after onDestroy().
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "TtsProactiveDispatcher"
    }

    override suspend fun dispatch(incoming: ProactiveAction) {
        // ── Proactivity-settings gate (user policy) ───────────────────────────
        // Sits AHEAD of every other check so a master-disabled / quiet-hours
        // / category-disabled verdict short-circuits without any speech or
        // notification I/O.  When the verdict is Downgrade we rewrite a
        // SpeakAction into a PassiveAction in-place and continue through the
        // existing dispatch path so its notification semantics still apply.
        val action: ProactiveAction = proactivityGate?.let { gate ->
            when (val v = gate.evaluate(incoming, lastUserInteractionMs())) {
                is com.jarvis.assistant.proactive.settings.ProactivityGate.Verdict.Suppress -> {
                    Log.d(TAG, "[PROACTIVITY_SUPPRESSED] reason=${v.reason} " +
                        "action=${incoming::class.simpleName}")
                    return
                }
                is com.jarvis.assistant.proactive.settings.ProactivityGate.Verdict.Downgrade -> {
                    if (incoming is ProactiveAction.SpeakAction) {
                        Log.d(TAG, "[PROACTIVITY_NOTIFY] downgrade speak→passive " +
                            "reason=${v.reason}")
                        ProactiveAction.PassiveAction(
                            title      = "Jarvis",
                            body       = incoming.text,
                            dedupeKey  = incoming.dedupeKey,
                            sourceType = incoming.sourceType,
                        )
                    } else incoming
                }
                is com.jarvis.assistant.proactive.settings.ProactivityGate.Verdict.Allow -> incoming
            }
        } ?: incoming

        when (action) {
            is ProactiveAction.SpeakAction -> {
                Log.d(TAG, "SpeakAction: \"${action.text}\"")

                // ── Final safety net for HA alerts ────────────────────────────
                // Even if the upstream filters miss something, this dispatcher
                // refuses to speak any text that looks like a HA motion / camera
                // / doorbell alert.  The notification still lives in the system
                // shade; we just stay quiet.
                if (com.jarvis.assistant.core.events.input
                        .HomeAssistantNotificationClassifier
                        .isHomeAssistantAlert(packageName = "", title = null, text = action.text)
                ) {
                    Log.d(TAG, "[HA_ALERT_SPEECH_BLOCKED] refusing to speak \"${action.text}\"")
                    return
                }

                // ── Tier-C: mode-aware proactive speech gate ──────────────────
                val mode = modeProvider()
                if (!mode.proactiveSpeechAllowed) {
                    Log.d(TAG, "[PROACTIVE_MODE_SUPPRESSED] mode=$mode — downgrading to notification")
                    JarvisNotificationHelper.postProactiveAlert(
                        context = context,
                        title   = "Jarvis",
                        body    = action.text
                    )
                    return
                }

                // ── Tier-C: executive controller verdict (if wired) ───────────
                // The proactive engine has already scored the candidate against
                // urgency/relevance/confidence before reaching the dispatcher
                // (otherwise the SpeakAction wouldn't be here at all).  What
                // the executive adds is "is the user mid-task right now?" —
                // a coarse user-busy check that downgrades to notification
                // when there's an active task in flight.
                executive?.let { ec ->
                    val verdict = ec.decide(
                        com.jarvis.assistant.executive.ExecutiveController.AttentionEvent(
                            source     = "proactive",
                            // Map sourceType to a coarse urgency proxy — the
                            // proactive engine's own scoring is the source of
                            // truth; this only nudges the executive's busy
                            // check in the right direction.
                            urgency    = when (action.sourceType) {
                                ProactiveEventType.LOW_BATTERY,
                                ProactiveEventType.UPCOMING_REMINDER -> 0.85f
                                else                                  -> 0.55f
                            },
                            relevance  = 0.70f,
                            confidence = 1.0f,
                            payload    = mapOf("type" to action.sourceType.name)
                        )
                    )
                    when (verdict.verdict) {
                        com.jarvis.assistant.executive.ExecutiveController.PriorityVerdict.IGNORE -> {
                            Log.d(TAG, "[PROACTIVE_EXEC_IGNORED] reason=${verdict.reason}")
                            return
                        }
                        com.jarvis.assistant.executive.ExecutiveController.PriorityVerdict.SILENT_NOTIFY,
                        com.jarvis.assistant.executive.ExecutiveController.PriorityVerdict.WAIT,
                        com.jarvis.assistant.executive.ExecutiveController.PriorityVerdict.ASK_CONFIRMATION -> {
                            Log.d(TAG, "[PROACTIVE_EXEC_DOWNGRADED] verdict=${verdict.verdict} " +
                                "reason=${verdict.reason}")
                            JarvisNotificationHelper.postProactiveAlert(
                                context = context,
                                title   = "Jarvis",
                                body    = action.text
                            )
                            return
                        }
                        com.jarvis.assistant.executive.ExecutiveController.PriorityVerdict.SPEAK_NOW -> {
                            Log.d(TAG, "[PROACTIVE_EXEC_SPEAK_NOW] reason=${verdict.reason}")
                            // fall through to existing TTS path
                        }
                    }
                }

                if (voiceResponseEnabled()) {
                    // Suppress wake detection while speaking so Jarvis doesn't
                    // hear its own audio and re-trigger the pipeline.
                    JarvisService.suppressWake(context)
                    try {
                        ttsEngine.speak(action.text)
                    } finally {
                        JarvisService.restoreWake(context)
                    }
                } else if (action.sourceType != ProactiveEventType.UNREAD_NOTIFICATION) {
                    // Voice off — fall back to a notification so the message is not lost.
                    // Skip for phone notifications: the original app notification is already
                    // in the shade, posting a Jarvis copy would just duplicate it.
                    Log.d(TAG, "Voice disabled — posting notification for SpeakAction")
                    JarvisNotificationHelper.postProactiveAlert(
                        context = context,
                        title   = "Jarvis",
                        body    = action.text
                    )
                }
            }

            is ProactiveAction.PassiveAction -> {
                Log.d(TAG, "PassiveAction: title=\"${action.title}\" body=\"${action.body}\"")
                // Invoke the optional hook (e.g. update UI state, logging in tests)
                onPassiveAction(action)
                // Phone notifications already appear on the device — posting a Jarvis copy
                // just creates a duplicate in the notification shade.  Skip the re-post.
                if (action.sourceType != ProactiveEventType.UNREAD_NOTIFICATION) {
                    JarvisNotificationHelper.postProactiveAlert(
                        context = context,
                        title   = action.title,
                        body    = action.body ?: action.title
                    )
                }
            }

            is ProactiveAction.NoAction -> {
                // Nothing to do — should not normally reach the dispatcher
                Log.v(TAG, "NoAction received (${action.reason}) — ignoring")
            }
        }
    }
}
