package com.jarvis.assistant.proactive.settings

import android.util.Log
import com.jarvis.assistant.proactive.ProactiveAction
import com.jarvis.assistant.proactive.ProactiveEventType
import java.util.Calendar
import java.util.TimeZone

/**
 * ProactivityGate — runtime policy filter sitting between the
 * [com.jarvis.assistant.proactive.DecisionEngine] verdict and the
 * dispatcher.
 *
 * The engine still runs its full scoring + cooldown + presence pipeline.
 * After it picks a winner the gate applies the user-visible Proactivity
 * settings on top of that, producing one of:
 *
 *   - [Verdict.Suppress] — drop, log reason via `[PROACTIVITY_SUPPRESSED]`
 *   - [Verdict.Downgrade] — keep but force PassiveAction (notification)
 *   - [Verdict.Allow]    — let the original action proceed
 *
 * Gates evaluated in priority order:
 *   1. Master switch
 *   2. Category (per event type)
 *   3. Quiet hours (with urgent override)
 *   4. Global cooldown (user-configured minutes)
 *   5. Interruption mode (SILENT / NOTIFY_ONLY / SPEAK_WHEN_ACTIVE / SPEAK_ANYTIME)
 *
 * Pure with respect to time + a settings snapshot — every gate decision
 * is reproducible from `(settings, action, event, nowMs, lastInteractionMs)`,
 * which keeps unit tests trivial.
 */
class ProactivityGate(
    private val settingsProvider: () -> ProactivitySettings,
    /**
     * Milliseconds since the last globally-surfaced proactive action, or
     * [Long.MAX_VALUE] if none.  Production passes
     * `{ cooldownStore.msSinceLastGlobalSurface() }`; tests inject a fixed
     * value so the cooldown gate is deterministic without wall-clock games.
     */
    private val msSinceLastGlobalSurface: () -> Long = { Long.MAX_VALUE },
    /** Clock injection — defaults to wall-clock UTC + the device TZ. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Time-zone provider — defaults to device default.  Tests can pin. */
    private val timeZone: () -> TimeZone = { TimeZone.getDefault() },
) {

    sealed class Verdict {
        data class Allow(val intent: String = "allow") : Verdict()
        data class Suppress(val reason: String) : Verdict()
        data class Downgrade(val reason: String) : Verdict()
    }

    companion object { private const val TAG = "ProactivityGate" }

    /**
     * Evaluate [action] against the current settings.  Source event type
     * is read off the action itself (Speak/Passive both carry sourceType).
     * Pass [lastUserInteractionMs] so SPEAK_WHEN_ACTIVE can tell whether
     * the user is currently engaged (within [activeWindowMs]).  null = no
     * record → treated as inactive.
     */
    fun evaluate(
        action: ProactiveAction,
        lastUserInteractionMs: Long?,
        activeWindowMs: Long = 5 * 60_000L,
    ): Verdict {
        val s = settingsProvider()
        val type = sourceTypeOf(action)

        Log.d(TAG, "[PROACTIVITY_EVALUATE] action=${action::class.simpleName} " +
            "event=$type enabled=${s.enabled} mode=${s.interruptionMode}")

        // 1. Master switch.
        if (!s.enabled) return reject("master_disabled")

        // No-op actions skip the rest of the pipeline.
        if (action is ProactiveAction.NoAction) return Verdict.Allow("no_action_passthrough")

        // 2. Category enable.  Unknown types fall back to suggestionsEnabled
        //    (see ProactivitySettings.isCategoryEnabled).  null event (rare —
        //    legacy passive action without source) skips this gate.
        if (type != null && !s.isCategoryEnabled(type)) {
            return reject("category_disabled(${type.name})")
        }

        // 3. Quiet hours.  Allow urgent events through when configured.
        val nowMin = nowMinuteOfDay()
        val inQuietHours = s.isQuietHourMinute(nowMin)
        if (inQuietHours) {
            val isUrgent = type?.let { s.isUrgentEvent(it) } == true
            if (!s.allowUrgentDuringQuietHours || !isUrgent) {
                return reject("quiet_hours(now=${formatMin(nowMin)} " +
                    "window=${formatMin(s.quietStartMinute)}→${formatMin(s.quietEndMinute)})")
            }
        }

        // 4. Global cooldown.  CooldownStore tracks the last surfaced ms;
        //    we apply the user-configured minutes on top so the engine's
        //    internal 60 s minGlobalGap can be relaxed independently.
        val cooldownMs = s.globalCooldownMinutes * 60_000L
        if (cooldownMs > 0) {
            val sinceLast = msSinceLastGlobalSurface()
            if (sinceLast in 0 until cooldownMs) {
                return reject("global_cooldown(remaining_ms=${cooldownMs - sinceLast})")
            }
        }

        // 5. Interruption mode — only relevant for SpeakAction.  Passive
        //    actions are already notifications and are governed by the
        //    master + category + quiet-hours gates above.
        if (action is ProactiveAction.SpeakAction) {
            when (s.interruptionMode) {
                InterruptionMode.SILENT -> {
                    Log.d(TAG, "[PROACTIVITY_LOG_ONLY] mode=SILENT — no surface")
                    return Verdict.Suppress("silent_mode")
                }
                InterruptionMode.NOTIFY_ONLY -> {
                    Log.d(TAG, "[PROACTIVITY_NOTIFY] mode=NOTIFY_ONLY — downgrading speak→notification")
                    return Verdict.Downgrade("notify_only_mode")
                }
                InterruptionMode.SPEAK_WHEN_ACTIVE -> {
                    val active = lastUserInteractionMs != null &&
                        (clock() - lastUserInteractionMs) <= activeWindowMs
                    if (!active) {
                        Log.d(TAG, "[PROACTIVITY_NOTIFY] mode=SPEAK_WHEN_ACTIVE inactive — downgrading")
                        return Verdict.Downgrade("speak_when_active_inactive")
                    }
                }
                InterruptionMode.SPEAK_ANYTIME -> Unit  // permissive
            }
        }

        Log.d(TAG, "[PROACTIVITY_SPEAK] mode=${s.interruptionMode} event=${type}")
        return Verdict.Allow("passed_all_gates")
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun reject(reason: String): Verdict.Suppress {
        Log.d(TAG, "[PROACTIVITY_SUPPRESSED] reason=$reason")
        return Verdict.Suppress(reason)
    }

    private fun sourceTypeOf(action: ProactiveAction): ProactiveEventType? = when (action) {
        is ProactiveAction.SpeakAction   -> action.sourceType
        is ProactiveAction.PassiveAction -> action.sourceType
        is ProactiveAction.NoAction      -> null
    }

    private fun nowMinuteOfDay(): Int {
        val cal = Calendar.getInstance(timeZone())
        cal.timeInMillis = clock()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun formatMin(min: Int): String {
        val h = (min / 60) % 24
        val m = min % 60
        return "%02d:%02d".format(h, m)
    }
}
