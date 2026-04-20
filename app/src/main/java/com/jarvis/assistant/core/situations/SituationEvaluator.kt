package com.jarvis.assistant.core.situations

import com.jarvis.assistant.context.ActivityMode
import com.jarvis.assistant.context.Presence
import com.jarvis.assistant.context.TimePhase
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.location.LocationTransition
import com.jarvis.assistant.location.PlaceKind
import com.jarvis.assistant.proactive.ContextSnapshot
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * SituationEvaluator — derives [Situation]s from the same inputs the
 * proactive engine already has.
 *
 * Each evaluator rule reads *only* the [Candidate]s a tick produced plus the
 * immutable [ContextSnapshot] and [Presence]. That keeps it pure and
 * trivial to unit-test — no I/O, no clock other than the one the caller
 * supplies.
 *
 * The evaluator adds no new signals. It reinterprets existing signals at a
 * higher level so downstream consumers (the [com.jarvis.assistant.core.decisions.Decision]
 * facade, [com.jarvis.assistant.prompt.PromptAssembler], traces, memory)
 * can key on "what's going on" rather than on individual triggers.
 *
 * Situations are intentionally short-lived; each rule supplies its own TTL.
 */
class SituationEvaluator {

    /**
     * Evaluate all situation rules and return those that currently hold.
     * The list is deduplicated + sorted downstream by [SituationRegistry].
     */
    fun evaluate(
        candidates: List<Candidate>,
        snapshot: ContextSnapshot,
        presence: Presence,
        nowMs: Long = snapshot.currentTimeMillis,
    ): List<Situation> {
        val out = mutableListOf<Situation>()

        lowBatteryBeforeTravel(candidates, snapshot, nowMs)?.let(out::add)
        leavingHomeSoon(candidates, snapshot, nowMs)?.let(out::add)
        arrivingHomeWithPendingTasks(snapshot, nowMs)?.let(out::add)
        likelyCommuteStart(snapshot, presence, nowMs)?.let(out::add)
        inMeetingAndUnavailable(snapshot, presence, nowMs)?.let(out::add)
        tiredLateNightInteraction(presence, nowMs)?.let(out::add)
        missedCallWhileNowFree(snapshot, presence, nowMs)?.let(out::add)
        headphonesInUrgentNotificationContext(candidates, snapshot, nowMs)?.let(out::add)
        possibleDeliveryWaiting(candidates, snapshot, nowMs)?.let(out::add)

        return out
    }

    // --- rules --------------------------------------------------------------

    /** Low battery AND a meeting / trip is in the near future. Reuses the
     *  composite trigger output when it's present, otherwise derives from
     *  raw snapshot fields so the situation can fire even if the trigger is
     *  disabled downstream. */
    private fun lowBatteryBeforeTravel(
        candidates: List<Candidate>,
        snapshot: ContextSnapshot,
        nowMs: Long,
    ): Situation? {
        val direct = candidates.firstOrNull {
            it.triggerId.equals("low_battery_before_travel", ignoreCase = true)
        }
        if (direct != null) {
            return Situation(
                type = SituationType.LOW_BATTERY_BEFORE_TRAVEL,
                confidence = direct.confidence.coerceIn(0f, 1f),
                urgency = direct.urgency.coerceIn(0f, 1f),
                createdAtMs = nowMs,
                expiresAtMs = nowMs + DEFAULT_TTL_MS,
                evidence = listOf(
                    "battery=${snapshot.batteryLevel}",
                    "nextMeeting=${snapshot.nextMeetingAtMillis}",
                ),
                sourceSignals = listOf(direct.triggerId, "snapshot.batteryLevel"),
                summary = "Battery low and travel or meeting coming up.",
            )
        }

        val batteryLow = !snapshot.isCharging && snapshot.batteryLevel <= 30
        val travelSoon = snapshot.nextMeetingAtMillis?.let { it - nowMs in 0..TWO_HOURS_MS } == true
        if (!batteryLow || !travelSoon) return null

        return Situation(
            type = SituationType.LOW_BATTERY_BEFORE_TRAVEL,
            confidence = 0.75f,
            urgency = 0.8f,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + DEFAULT_TTL_MS,
            evidence = listOf(
                "battery=${snapshot.batteryLevel}",
                "nextMeeting=${snapshot.nextMeetingAtMillis}",
            ),
            sourceSignals = listOf("snapshot.batteryLevel", "snapshot.nextMeetingAtMillis"),
            summary = "Battery low and travel or meeting coming up.",
        )
    }

    /** A LEFT_HOME event or an imminent meeting away from current location. */
    private fun leavingHomeSoon(
        candidates: List<Candidate>,
        snapshot: ContextSnapshot,
        nowMs: Long,
    ): Situation? {
        val leftHome = candidates.any { it.eventType == ProactiveEventType.LEFT_HOME }
        val meetingSoon = snapshot.nextMeetingAtMillis?.let {
            it - nowMs in 0..MEETING_SOON_MS
        } == true
        if (!leftHome && !meetingSoon) return null

        return Situation(
            type = SituationType.LEAVING_HOME_SOON,
            confidence = if (leftHome) 0.85f else 0.6f,
            urgency = 0.7f,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + DEFAULT_TTL_MS,
            evidence = buildList {
                if (leftHome) add("event=LEFT_HOME")
                if (meetingSoon) add("nextMeetingInMin=${(snapshot.nextMeetingAtMillis!! - nowMs) / 60_000L}")
            },
            sourceSignals = listOf("candidates.LEFT_HOME", "snapshot.nextMeetingAtMillis"),
            summary = "User is leaving or about to leave home.",
        )
    }

    /** User is at home (via ARRIVED_HOME event) and there are unresolved
     *  reminders / notifications waiting. */
    private fun arrivingHomeWithPendingTasks(
        snapshot: ContextSnapshot,
        nowMs: Long,
    ): Situation? {
        val lt = snapshot.lastLocationTransition
        val arrivedHome = lt != null &&
            lt.kind == LocationTransition.Kind.ARRIVED &&
            lt.placeKind == PlaceKind.HOME
        val pending = snapshot.activeReminderCount + snapshot.unreadNotificationCount
        if (!arrivedHome || pending == 0) return null

        return Situation(
            type = SituationType.ARRIVING_HOME_WITH_PENDING_TASKS,
            confidence = 0.75f,
            urgency = 0.5f,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + LONG_TTL_MS,
            evidence = listOf(
                "reminders=${snapshot.activeReminderCount}",
                "notifications=${snapshot.unreadNotificationCount}",
            ),
            sourceSignals = listOf(
                "snapshot.lastLocationTransition",
                "snapshot.activeReminderCount",
                "snapshot.unreadNotificationCount",
            ),
            summary = "Arrived home with pending reminders or notifications.",
        )
    }

    /** Morning + recent interaction + driving / meeting looming → likely
     *  the start of a commute. */
    private fun likelyCommuteStart(
        snapshot: ContextSnapshot,
        presence: Presence,
        nowMs: Long,
    ): Situation? {
        if (presence.timePhase != TimePhase.MORNING) return null
        val driving = snapshot.isDriving
        val meetingSoon = snapshot.nextMeetingAtMillis?.let { it - nowMs in 0..MEETING_SOON_MS } == true
        if (!driving && !meetingSoon) return null

        return Situation(
            type = SituationType.LIKELY_COMMUTE_START,
            confidence = if (driving) 0.8f else 0.55f,
            urgency = 0.5f,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + DEFAULT_TTL_MS,
            evidence = listOf("driving=$driving", "meetingSoon=$meetingSoon"),
            sourceSignals = listOf("presence.timePhase", "snapshot.isDriving", "snapshot.nextMeetingAtMillis"),
            summary = "Likely the start of a morning commute.",
        )
    }

    /** User is actively in a meeting window → shouldn't interrupt. */
    private fun inMeetingAndUnavailable(
        snapshot: ContextSnapshot,
        presence: Presence,
        nowMs: Long,
    ): Situation? {
        val start = snapshot.nextMeetingAtMillis ?: return null
        val end = snapshot.nextMeetingEndMillis ?: return null
        val active = nowMs in start..end
        if (!active) return null
        val unavailable = presence.activity != ActivityMode.ACTIVE
        if (!unavailable) return null

        return Situation(
            type = SituationType.IN_MEETING_AND_UNAVAILABLE,
            confidence = 0.9f,
            urgency = 0.3f,
            createdAtMs = nowMs,
            expiresAtMs = end,
            evidence = listOf("meeting=${snapshot.nextMeetingTitle}", "ends=$end"),
            sourceSignals = listOf("snapshot.nextMeetingAtMillis", "snapshot.nextMeetingEndMillis"),
            summary = "User is in a meeting and likely unavailable.",
        )
    }

    /** Late night + long idle interaction → user is probably tired; soften tone. */
    private fun tiredLateNightInteraction(presence: Presence, nowMs: Long): Situation? {
        val late = presence.timePhase == TimePhase.NIGHT
        val windingDown = presence.activity == ActivityMode.WINDING_DOWN
        if (!late && !windingDown) return null

        return Situation(
            type = SituationType.TIRED_LATE_NIGHT_INTERACTION,
            confidence = if (late && windingDown) 0.85f else 0.6f,
            urgency = 0.2f,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + LONG_TTL_MS,
            evidence = listOf(
                "phase=${presence.timePhase}",
                "activity=${presence.activity}",
                "idleMin=${presence.minutesSinceInteraction}",
            ),
            sourceSignals = listOf("presence.timePhase", "presence.activity"),
            summary = "Late night, user winding down — keep it soft.",
        )
    }

    /** A missed call is waiting AND the user is currently available (not
     *  driving, not in a meeting, not mid-active turn). */
    private fun missedCallWhileNowFree(
        snapshot: ContextSnapshot,
        presence: Presence,
        nowMs: Long,
    ): Situation? {
        if (snapshot.missedCallsCount <= 0) return null
        if (snapshot.isDriving) return null
        if (presence.activity == ActivityMode.ACTIVE) return null
        val inMeeting = snapshot.nextMeetingAtMillis != null &&
            snapshot.nextMeetingEndMillis != null &&
            nowMs in snapshot.nextMeetingAtMillis!!..snapshot.nextMeetingEndMillis!!
        if (inMeeting) return null

        return Situation(
            type = SituationType.MISSED_CALL_WHILE_NOW_FREE,
            confidence = 0.8f,
            urgency = 0.55f,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + LONG_TTL_MS,
            evidence = listOf(
                "missed=${snapshot.missedCallsCount}",
                "contact=${snapshot.lastMissedCallContactName ?: "unknown"}",
            ),
            sourceSignals = listOf("snapshot.missedCallsCount", "presence.activity"),
            summary = "Missed call waiting and the user seems free now.",
        )
    }

    /** An urgent notification arrived while the user is likely wearing
     *  headphones (current proxy: jarvis is speaking OR driving). */
    private fun headphonesInUrgentNotificationContext(
        candidates: List<Candidate>,
        snapshot: ContextSnapshot,
        nowMs: Long,
    ): Situation? {
        val urgentNotif = candidates.any {
            it.eventType == ProactiveEventType.UNREAD_NOTIFICATION && it.urgency >= 0.7f
        }
        if (!urgentNotif) return null
        val headphoneProxy = snapshot.isJarvisSpeaking || snapshot.isDriving
        if (!headphoneProxy) return null

        return Situation(
            type = SituationType.HEADPHONES_IN_URGENT_NOTIFICATION_CONTEXT,
            confidence = 0.6f,
            urgency = 0.75f,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + SHORT_TTL_MS,
            evidence = listOf(
                "notifApp=${snapshot.lastNotificationApp ?: "?"}",
                "driving=${snapshot.isDriving}",
            ),
            sourceSignals = listOf("candidates.UNREAD_NOTIFICATION", "snapshot.isDriving", "snapshot.isJarvisSpeaking"),
            summary = "Urgent notification while user likely has audio on.",
        )
    }

    /** A notification whose text hints at delivery-related language. Cheap
     *  keyword match; the situation only exists to make prompts smarter. */
    private fun possibleDeliveryWaiting(
        candidates: List<Candidate>,
        snapshot: ContextSnapshot,
        nowMs: Long,
    ): Situation? {
        val text = (snapshot.lastNotificationText ?: "").lowercase()
        if (text.isEmpty()) return null
        val hit = DELIVERY_KEYWORDS.any { text.contains(it) }
        if (!hit) return null

        val confidence = if (candidates.any { it.eventType == ProactiveEventType.UNREAD_NOTIFICATION }) 0.7f else 0.5f
        return Situation(
            type = SituationType.POSSIBLE_DELIVERY_WAITING,
            confidence = confidence,
            urgency = 0.3f,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + LONG_TTL_MS,
            evidence = listOf("notif=${text.take(80)}"),
            sourceSignals = listOf("snapshot.lastNotificationText"),
            summary = "A delivery may be on the way.",
        )
    }

    companion object {
        private const val DEFAULT_TTL_MS = 15 * 60_000L   // 15 min
        private const val SHORT_TTL_MS   =  5 * 60_000L   //  5 min
        private const val LONG_TTL_MS    = 60 * 60_000L   //  1 hour
        private const val TWO_HOURS_MS   = 2 * 60 * 60_000L
        private const val MEETING_SOON_MS = 30 * 60_000L  // 30 min

        private val DELIVERY_KEYWORDS = listOf(
            "out for delivery", "arriving today", "package", "parcel",
            "tracking", "delivered", "delivery", "courier",
        )
    }
}
