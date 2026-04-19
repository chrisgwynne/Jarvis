package com.jarvis.assistant.context

import java.time.Instant
import java.time.ZoneId

/**
 * Presence — Jarvis's rolling sense of "what's happening right now".
 *
 * A thin value type derived from the same signals that already feed the
 * proactive engine (wall clock, last user interaction, speech state, driving
 * mode).  It is recomputed on demand — there is no long-lived presence
 * object — which keeps it cheap and free of its own state machine.
 *
 * Two consumers care about this:
 *
 *   1. [com.jarvis.assistant.proactive.DecisionEngine] uses [activity] and
 *      [timePhase] to decide whether to suppress soft suggestions, e.g. when
 *      the user is mid-conversation or winding down for the night.
 *
 *   2. [com.jarvis.assistant.context.ContextEngine.toPromptFragment] injects
 *      a short presence line into the system prompt so the LLM keeps
 *      continuity across turns ("evening", "late night", "active exchange").
 */
data class Presence(
    val timePhase: TimePhase,
    val activity : ActivityMode,
    /** Minutes since the user last spoke; [Long.MAX_VALUE] when unknown. */
    val minutesSinceInteraction: Long
) {

    /**
     * True when a light, skippable suggestion is acceptable right now.
     *
     * - ACTIVE  — user just spoke, don't pile on.
     * - DRIVING — only critical events (handled upstream by InterruptLevel).
     * - WINDING_DOWN (evening + no recent interaction) — be quieter.
     * - NIGHT   — stay silent unless caller has already decided the event is critical.
     */
    fun allowsSoftSuggestions(): Boolean = when {
        activity == ActivityMode.ACTIVE        -> false
        activity == ActivityMode.DRIVING       -> false
        activity == ActivityMode.WINDING_DOWN  -> false
        timePhase == TimePhase.NIGHT           -> false
        else                                   -> true
    }

    /** One-liner for the system prompt (or null when there's nothing useful to add). */
    fun toPromptFragment(): String = buildString {
        append("Current moment: ")
        append(
            when (timePhase) {
                TimePhase.MORNING      -> "morning"
                TimePhase.DAY          -> "afternoon"
                TimePhase.EVENING      -> "evening"
                TimePhase.NIGHT        -> "late night"
            }
        )
        when (activity) {
            ActivityMode.ACTIVE        -> append(", mid-conversation")
            ActivityMode.DRIVING       -> append(", user is driving")
            ActivityMode.WINDING_DOWN  -> append(", user winding down")
            ActivityMode.IDLE          -> Unit
        }
        append('.')
    }

    companion object {
        /**
         * Compute presence from primitive signals.
         *
         * @param nowMs                    Wall-clock time.
         * @param lastInteractionMs        Epoch ms of the last user voice turn; null if never.
         * @param isJarvisSpeaking         TTS currently playing.
         * @param isJarvisListening        Recognizer currently open.
         * @param isDriving                Car audio / dock connected.
         * @param zone                     Timezone to interpret [nowMs] in.
         */
        /**
         * Boundary hours for [TimePhase]. MORNING starts at [morningStart],
         * DAY at [dayStart], EVENING at [eveningStart], NIGHT at [nightStart].
         * Values are clock hours 0–23. Callers can override to retune for
         * shift workers or non-standard schedules.
         */
        data class PhaseBoundaries(
            val morningStart: Int = 5,
            val dayStart    : Int = 12,
            val eveningStart: Int = 17,
            val nightStart  : Int = 21
        )

        val DEFAULT_BOUNDARIES = PhaseBoundaries()

        fun compute(
            nowMs              : Long,
            lastInteractionMs  : Long?,
            isJarvisSpeaking   : Boolean,
            isJarvisListening  : Boolean,
            isDriving          : Boolean,
            zone               : ZoneId = ZoneId.systemDefault(),
            boundaries         : PhaseBoundaries = DEFAULT_BOUNDARIES
        ): Presence {
            val hour = Instant.ofEpochMilli(nowMs).atZone(zone).hour
            val phase = with(boundaries) {
                when {
                    hour >= nightStart || hour < morningStart -> TimePhase.NIGHT
                    hour < dayStart                           -> TimePhase.MORNING
                    hour < eveningStart                       -> TimePhase.DAY
                    else                                      -> TimePhase.EVENING
                }
            }
            val minutesIdle = lastInteractionMs
                ?.let { (nowMs - it).coerceAtLeast(0L) / 60_000L }
                ?: Long.MAX_VALUE

            val activity = when {
                isDriving                                               -> ActivityMode.DRIVING
                isJarvisSpeaking || isJarvisListening                   -> ActivityMode.ACTIVE
                lastInteractionMs != null && minutesIdle < 1L           -> ActivityMode.ACTIVE
                phase == TimePhase.EVENING && minutesIdle >= 15L        -> ActivityMode.WINDING_DOWN
                phase == TimePhase.NIGHT                                -> ActivityMode.WINDING_DOWN
                else                                                    -> ActivityMode.IDLE
            }
            return Presence(phase, activity, minutesIdle)
        }

    }
}

enum class TimePhase { MORNING, DAY, EVENING, NIGHT }

enum class ActivityMode { IDLE, ACTIVE, DRIVING, WINDING_DOWN }
