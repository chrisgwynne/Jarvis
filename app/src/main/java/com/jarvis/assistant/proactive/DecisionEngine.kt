package com.jarvis.assistant.proactive

import android.util.Log
import com.jarvis.assistant.context.Presence
import java.time.Instant
import java.time.ZoneId

/**
 * DecisionEngine — the final gatekeeper that converts a ranked list of
 * [EventScorer.ScoredEvent] candidates into a single [ProactiveAction].
 *
 * ## Decision steps
 *
 * 1. **Staleness filter** — events whose [ProactiveEvent.createdAtMillis] is
 *    older than [ProactiveConfig.eventStalenessMs] are discarded.
 *    Events with [InterruptLevel.NONE] are also discarded here.
 *
 * 2. **Empty guard** — if the filtered list is empty, return [ProactiveAction.NoAction].
 *
 * 3. **Global gap check** — if the last surfaced action (any type) was within
 *    [ProactiveConfig.minGlobalGapMs], return [ProactiveAction.NoAction].
 *    This prevents the engine from "chattering" across multiple event types
 *    in rapid succession.
 *
 * 4. **Top candidate selection** — pick the event with the highest
 *    [EventScorer.ScoredEvent.finalScore].
 *
 * 5. **Action mapping** —
 *    - [InterruptLevel.ACTIVE] → [ProactiveAction.SpeakAction] if the event has
 *      [ProactiveEvent.spokenText]; otherwise degrades to [ProactiveAction.PassiveAction].
 *    - [InterruptLevel.PASSIVE] → [ProactiveAction.PassiveAction].
 *    - [InterruptLevel.NONE] → [ProactiveAction.NoAction] (should not reach here
 *      after step 1, but handled defensively).
 */
class DecisionEngine(
    private val config: ProactiveConfig,
    private val cooldownStore: CooldownStore
) {

    companion object {
        private const val TAG = "DecisionEngine"
    }

    /**
     * Convert a ranked list of [candidates] into a single [ProactiveAction].
     *
     * [snapshot] is used for the staleness timestamp reference.
     *
     * @param candidates Must be pre-scored and pre-sorted; [EventScorer.scoreAll]
     *                   returns a correctly ordered list.
     * @param snapshot   The [ContextSnapshot] from the same polling tick.
     * @return A concrete [ProactiveAction]; never throws.
     */
    fun decide(
        candidates: List<EventScorer.ScoredEvent>,
        snapshot: ContextSnapshot
    ): ProactiveAction {
        // Step 1 — stale / NONE filter
        val valid = candidates.filter { scored ->
            val age = snapshot.currentTimeMillis - scored.event.createdAtMillis
            if (age > config.eventStalenessMs) {
                Log.v(TAG, "Discarding stale event ${scored.event.dedupeKey} (age=${age}ms)")
                return@filter false
            }
            if (scored.interruptLevel == InterruptLevel.NONE) {
                Log.v(
                    TAG,
                    "Discarding NONE-level event ${scored.event.dedupeKey} " +
                    "(finalScore=${"%.3f".format(scored.finalScore)})"
                )
                return@filter false
            }
            true
        }

        // Step 2 — empty guard
        if (valid.isEmpty()) {
            Log.d(TAG, "No actionable candidates this tick")
            ProactiveMetrics.increment(ProactiveMetrics.Counter.SUPPRESSED_EMPTY)
            return ProactiveAction.NoAction
        }

        // Step 3 — global gap check
        val msSinceGlobal = cooldownStore.msSinceLastGlobalSurface()
        if (msSinceGlobal < config.minGlobalGapMs) {
            Log.d(
                TAG,
                "Global gap not satisfied: ${msSinceGlobal}ms < ${config.minGlobalGapMs}ms — suppressing"
            )
            ProactiveMetrics.increment(ProactiveMetrics.Counter.SUPPRESSED_GLOBAL_GAP)
            return ProactiveAction.NoAction
        }

        // Step 3b — quiet hours: suppress everything except critical events.
        // Critical = low battery, an imminent reminder, or an imminent meeting.
        val top = valid.first()
        val critical = top.event.type == ProactiveEventType.LOW_BATTERY ||
            top.event.type == ProactiveEventType.MEETING_STARTING_SOON ||
            run {
                if (top.event.type != ProactiveEventType.UPCOMING_REMINDER) return@run false
                val next = snapshot.nextReminderAtMillis
                next != null && (next - snapshot.currentTimeMillis) <= config.reminderUrgentMs
            } ||
            run {
                if (top.event.type != ProactiveEventType.UPCOMING_MEETING) return@run false
                val next = snapshot.nextMeetingAtMillis
                next != null && (next - snapshot.currentTimeMillis) <= config.meetingUrgentMs
            }

        if (isInQuietHours(snapshot.currentTimeMillis) && !critical) {
            Log.d(
                TAG,
                "Quiet hours — suppressing ${top.event.type} / ${top.event.dedupeKey}"
            )
            ProactiveMetrics.increment(ProactiveMetrics.Counter.SUPPRESSED_QUIET_HOURS)
            return ProactiveAction.NoAction
        }

        // Step 3c — presence gate: soft suggestions (PASSIVE level) defer when
        // the user is mid-conversation or winding down.  Critical events still
        // go through even if the moment is active.
        if (top.interruptLevel == InterruptLevel.PASSIVE && !critical) {
            val presence = Presence.compute(
                nowMs             = snapshot.currentTimeMillis,
                lastInteractionMs = snapshot.lastUserInteractionTimeMillis,
                isJarvisSpeaking  = snapshot.isJarvisSpeaking,
                isJarvisListening = snapshot.isJarvisListening,
                isDriving         = snapshot.isDriving
            )
            if (!presence.allowsSoftSuggestions()) {
                Log.d(
                    TAG,
                    "Presence ${presence.activity}/${presence.timePhase} — " +
                    "deferring soft ${top.event.type}"
                )
                ProactiveMetrics.increment(ProactiveMetrics.Counter.SUPPRESSED_PRESENCE)
                return ProactiveAction.NoAction
            }
        }

        // Step 4 — top candidate already selected above
        Log.d(
            TAG,
            "Top candidate: ${top.event.type} / ${top.event.dedupeKey} " +
            "finalScore=${"%.3f".format(top.finalScore)} level=${top.interruptLevel}"
        )

        // Step 5 — action mapping
        return when (top.interruptLevel) {
            InterruptLevel.ACTIVE -> {
                val spokenText = top.event.spokenText
                if (spokenText != null) {
                    Log.d(TAG, "Emitting SpeakAction: \"$spokenText\"")
                    ProactiveAction.SpeakAction(
                        text       = spokenText,
                        dedupeKey  = top.event.dedupeKey,
                        sourceType = top.event.type
                    )
                } else {
                    // Degrade: ACTIVE event without spoken text → PassiveAction
                    Log.d(TAG, "ACTIVE event has no spokenText — degrading to PassiveAction")
                    ProactiveAction.PassiveAction(
                        title      = top.event.title,
                        body       = null,
                        dedupeKey  = top.event.dedupeKey,
                        sourceType = top.event.type
                    )
                }
            }

            InterruptLevel.PASSIVE -> {
                Log.d(TAG, "Emitting PassiveAction: \"${top.event.title}\"")
                ProactiveAction.PassiveAction(
                    title      = top.event.title,
                    body       = top.event.spokenText,
                    dedupeKey  = top.event.dedupeKey,
                    sourceType = top.event.type
                )
            }

            InterruptLevel.NONE -> {
                // Defensive: should have been filtered in step 1
                Log.w(TAG, "Top candidate has NONE level after filter — returning NoAction")
                ProactiveAction.NoAction
            }
        }
    }

    /**
     * True when [nowMs] falls inside the configured quiet-hours window.
     * Supports wrap-around (e.g. 22 → 7).  Returns false when either bound
     * is null (feature disabled).
     */
    private fun isInQuietHours(nowMs: Long): Boolean {
        val start = config.quietHoursStartHour ?: return false
        val end   = config.quietHoursEndHour   ?: return false
        if (start == end) return false
        val hour = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).hour
        return if (start < end) {
            hour in start until end
        } else {
            // Wrap past midnight: e.g. 22..23 or 0..6 for start=22 end=7
            hour >= start || hour < end
        }
    }
}
