package com.jarvis.assistant.proactive

/**
 * EventGenerator — inspects a [ContextSnapshot] and produces a list of
 * [ProactiveEvent] candidates for the current tick.
 *
 * Each private generator is responsible for exactly one [ProactiveEventType].
 * Generators return null when the underlying condition is not met so the
 * public [generate] function simply filters the nulls out.
 *
 * No dispatch or side-effects happen here; this class is a pure function of
 * the snapshot and the config.
 */
class EventGenerator(private val config: ProactiveConfig) {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generate all applicable events for the given [snapshot].
     *
     * Returns an empty list when no conditions are triggered.
     */
    fun generate(snapshot: ContextSnapshot): List<ProactiveEvent> = listOfNotNull(
        generateBatteryEvent(snapshot),
        generateReminderEvent(snapshot),
        generateMissedCallEvent(snapshot),
        generateNotificationEvent(snapshot),
        generateBrainContextEvent(snapshot),
        generateMeetingStartingSoonEvent(snapshot),
        generateUpcomingMeetingEvent(snapshot),
        generateDailyAgendaEvent(snapshot),
        generateLocationTransitionEvent(snapshot)
    )

    /**
     * Build a daily-brief grouping of all events, bucketed by urgency tier.
     *
     * This is intended for a "morning briefing" feature where all pending
     * signals are summarised at once rather than drip-fed during the day.
     *
     * @return A map from [DailyBriefBucket] to the events in that bucket.
     *         Buckets with no events are present with empty lists.
     */
    fun buildDailyBrief(snapshot: ContextSnapshot): Map<DailyBriefBucket, List<ProactiveEvent>> {
        val events = generate(snapshot)
        val result = mutableMapOf(
            DailyBriefBucket.NOW  to mutableListOf<ProactiveEvent>(),
            DailyBriefBucket.SOON to mutableListOf(),
            DailyBriefBucket.INFO to mutableListOf()
        )
        for (event in events) {
            val bucket = when {
                event.urgency >= 0.8f -> DailyBriefBucket.NOW
                event.urgency >= 0.5f -> DailyBriefBucket.SOON
                else                  -> DailyBriefBucket.INFO
            }
            result[bucket]!!.add(event)
        }
        return result
    }

    // ── Generators ────────────────────────────────────────────────────────────

    /**
     * Generate a LOW_BATTERY event if the battery is below the low threshold
     * and the device is not charging.
     *
     * Urgency is tiered:
     *   - <= [ProactiveConfig.batteryCritical] → 0.95
     *   - <= [ProactiveConfig.batteryVeryLow]  → 0.80
     *   - otherwise (low)                      → 0.55
     *
     * dedupeKey buckets the percentage into 5% bands so the same alert is not
     * repeated for every single percent drop.
     */
    private fun generateBatteryEvent(snapshot: ContextSnapshot): ProactiveEvent? {
        if (snapshot.batteryLevel > config.batteryLow || snapshot.isCharging) return null

        val battery = snapshot.batteryLevel
        val urgency = when {
            battery <= config.batteryCritical -> 0.95f
            battery <= config.batteryVeryLow  -> 0.80f
            else                              -> 0.55f
        }

        val spokenText = when {
            battery <= config.batteryCritical -> "Battery's nearly dead — $battery%."
            battery <= config.batteryVeryLow  -> "Battery's getting low — $battery%."
            else                              -> "Battery's at $battery%."
        }
        val bucket     = battery / 5 * 5   // round down to nearest 5

        return ProactiveEvent(
            type         = ProactiveEventType.LOW_BATTERY,
            title        = "Battery $battery%",
            spokenText   = spokenText,
            urgency      = urgency,
            relevance    = 0.80f,
            confidence   = 1.0f,
            annoyanceCost = 0.25f,
            dedupeKey    = "low_battery_$bucket",
            metadata     = mapOf("batteryLevel" to battery.toString())
        )
    }

    /**
     * Generate an UPCOMING_REMINDER event if the next pending reminder is
     * within [ProactiveConfig.reminderWindowMs] of the current time.
     *
     * Urgency and relevance scale with proximity:
     *   - within [ProactiveConfig.reminderUrgentMs]     → urgency=0.90, relevance=0.95
     *   - within [ProactiveConfig.reminderHighWindowMs] → urgency=0.70, relevance=0.80
     *   - otherwise (within full window)               → urgency=0.50, relevance=0.60
     *
     * dedupeKey buckets to the nearest minute to avoid generating a new key
     * every polling tick for the same reminder.
     */
    private fun generateReminderEvent(snapshot: ContextSnapshot): ProactiveEvent? {
        val nextMs = snapshot.nextReminderAtMillis ?: return null
        val diffMs = nextMs - snapshot.currentTimeMillis
        if (diffMs > config.reminderWindowMs || diffMs < 0) return null

        val urgency: Float
        val relevance: Float
        when {
            diffMs <= config.reminderUrgentMs     -> { urgency = 0.90f; relevance = 0.95f }
            diffMs <= config.reminderHighWindowMs -> { urgency = 0.70f; relevance = 0.80f }
            else                                  -> { urgency = 0.50f; relevance = 0.60f }
        }

        val minutesAway = (diffMs / 60_000L).coerceAtLeast(1L)
        val spokenText = when {
            minutesAway <= 1L -> "You've got something coming up any minute."
            minutesAway < 10L -> "You've got something in about $minutesAway minutes."
            else              -> "Reminder in about $minutesAway minutes."
        }
        val titleLabel = if (minutesAway <= 1L) "in a minute" else "in $minutesAway min"

        // Bucket to the nearest minute (truncate to whole minutes of epoch)
        val bucketKey = nextMs / 60_000L * 60_000L

        return ProactiveEvent(
            type          = ProactiveEventType.UPCOMING_REMINDER,
            title         = "Reminder $titleLabel",
            spokenText    = spokenText,
            urgency       = urgency,
            relevance     = relevance,
            confidence    = 1.0f,
            annoyanceCost = 0.20f,
            dedupeKey     = "upcoming_reminder_$bucketKey",
            metadata      = mapOf(
                "nextReminderAtMillis"  to nextMs.toString(),
                "activeReminderCount"  to snapshot.activeReminderCount.toString()
            )
        )
    }

    /**
     * Generate a MISSED_CALL event if there is at least one unacknowledged
     * missed call.
     *
     * Relevance decays with time:
     *   - < 5 minutes ago  → 0.90
     *   - < 30 minutes ago → 0.65
     *   - older            → 0.35
     *
     * dedupeKey is bucketed to the nearest second of the last call to prevent
     * repeated surfacing during the same polling window while still
     * distinguishing a new missed call from an old one.
     */
    private fun generateMissedCallEvent(snapshot: ContextSnapshot): ProactiveEvent? {
        if (snapshot.missedCallsCount <= 0) return null
        val lastCallAt = snapshot.lastMissedCallAtMillis ?: return null

        val ageMs = snapshot.currentTimeMillis - lastCallAt
        val relevance = when {
            ageMs <  5 * 60_000L  -> 0.90f
            ageMs < 30 * 60_000L  -> 0.65f
            else                  -> 0.35f
        }

        val caller = snapshot.lastMissedCallContactName?.takeIf { it.isNotBlank() }
        val count  = snapshot.missedCallsCount

        val spokenText = when {
            count == 1 && caller != null -> "$caller called."
            count == 1                   -> "You missed a call."
            caller != null               -> "$count missed calls — $caller tried you."
            else                         -> "$count missed calls."
        }
        val title = when {
            count == 1 && caller != null -> "Missed call — $caller"
            count == 1                   -> "Missed call"
            else                         -> "$count missed calls"
        }

        val bucketKey = lastCallAt / 1_000L * 1_000L

        return ProactiveEvent(
            type          = ProactiveEventType.MISSED_CALL,
            title         = title,
            spokenText    = spokenText,
            urgency       = 0.65f,
            relevance     = relevance,
            confidence    = 1.0f,
            annoyanceCost = 0.35f,
            dedupeKey     = "missed_call_$bucketKey",
            metadata      = buildMap {
                put("missedCallsCount", snapshot.missedCallsCount.toString())
                if (snapshot.lastMissedCallContactName != null) {
                    put("contactName", snapshot.lastMissedCallContactName)
                }
            }
        )
    }

    /**
     * Generate a BEHAVIORAL_LEARNING event when there is a high-confidence brain
     * prediction for the user's current context.
     *
     * Uses [ContextSnapshot.topPredictionDescription] which is pre-fetched by
     * [ProactiveEngine.buildSnapshot] from [BrainPredictionSource].
     */
    private fun generateBrainContextEvent(snapshot: ContextSnapshot): ProactiveEvent? {
        val description = snapshot.topPredictionDescription ?: return null
        if (snapshot.topPredictionScore < 0.60f) return null
        // Don't interrupt — only surface when Jarvis is idle
        if (snapshot.isJarvisSpeaking || snapshot.isJarvisListening) return null

        val knowledge = snapshot.predictionKnowledgeContext
        // Pattern-driven suggestions should sound like an observation, not a
        // system announcement.  Keep them a single short sentence — the user
        // gets the pattern from the phrasing alone ("You usually charge around
        // now") without needing "Based on your habits:" as a preamble.
        val spokenText = buildString {
            append(description.trimEnd('.', '!', '?'))
            append('.')
            if (!knowledge.isNullOrBlank()) append(" ${knowledge.take(120).trimEnd('.')}.")
        }

        return ProactiveEvent(
            type          = ProactiveEventType.BEHAVIORAL_LEARNING,
            title         = "Habit insight",
            spokenText    = spokenText,
            urgency       = (snapshot.topPredictionScore * 0.7f).coerceAtMost(0.65f),
            relevance     = snapshot.topPredictionScore,
            confidence    = snapshot.topPredictionScore,
            annoyanceCost = 0.40f,
            // Hash the full description so two predictions that share the
            // first 40 chars don't collide on the cooldown key.
            dedupeKey     = "brain_ctx_${description.hashCode().toUInt().toString(16)}",
            metadata      = buildMap {
                put("predictionScore", snapshot.topPredictionScore.toString())
                put("description", description)
            }
        )
    }

    /**
     * Generate an UNREAD_NOTIFICATION event when there are unread notifications
     * and the user has been idle (not interacting with Jarvis) for a while.
     *
     * Only fires when [ContextSnapshot.unreadNotificationCount] > 0 and
     * Jarvis is neither speaking nor listening.
     */
    private fun generateNotificationEvent(snapshot: ContextSnapshot): ProactiveEvent? {
        if (snapshot.unreadNotificationCount <= 0) return null
        if (snapshot.isJarvisSpeaking || snapshot.isJarvisListening) return null

        val count = snapshot.unreadNotificationCount
        val text  = snapshot.lastNotificationText
        val app   = snapshot.lastNotificationApp

        val appLabel   = app?.substringAfterLast('.')?.replaceFirstChar { it.titlecase() }
        val spokenText = when {
            count == 1 && text != null && appLabel != null -> "$appLabel: $text"
            count == 1 && appLabel != null                 -> "Something from $appLabel."
            count == 1 && text != null                     -> "New message — $text"
            count == 1                                     -> "You've got a new notification."
            appLabel != null                               -> "$count new from $appLabel."
            else                                           -> "$count new notifications."
        }
        val titleLabel = if (count == 1) "New notification" else "$count new notifications"

        return ProactiveEvent(
            type          = ProactiveEventType.UNREAD_NOTIFICATION,
            title         = titleLabel,
            spokenText    = spokenText,
            urgency       = 0.55f,
            relevance     = 0.70f,
            confidence    = 1.0f,
            annoyanceCost = 0.30f,
            dedupeKey     = "unread_notification_${snapshot.currentTimeMillis / 60_000L}",
            metadata      = buildMap {
                put("count", count.toString())
                if (app != null) put("app", app)
                if (text != null) put("text", text)
            }
        )
    }

    /**
     * Generate an UPCOMING_MEETING event when the next timed meeting starts
     * within (meetingUrgentMs, meetingWindowMs].  Imminent meetings
     * (≤ meetingUrgentMs) are handled by [generateMeetingStartingSoonEvent]
     * so this generator skips that range to avoid duplicate spoken output.
     */
    private fun generateUpcomingMeetingEvent(snapshot: ContextSnapshot): ProactiveEvent? {
        val startMs = snapshot.nextMeetingAtMillis ?: return null
        val diffMs = startMs - snapshot.currentTimeMillis
        if (diffMs <= config.meetingUrgentMs || diffMs > config.meetingWindowMs) return null

        val minutesAway = (diffMs / 60_000L).coerceAtLeast(1L)
        val urgency: Float
        val relevance: Float
        if (minutesAway <= 5L) {
            urgency = 0.80f; relevance = 0.85f
        } else {
            urgency = 0.55f; relevance = 0.70f
        }

        val title = snapshot.nextMeetingTitle?.takeIf { it.isNotBlank() }
        val spokenText = when {
            title != null && minutesAway <= 5L -> "$title in $minutesAway minutes."
            title != null                      -> "$title in $minutesAway minutes."
            minutesAway <= 5L                  -> "Meeting in $minutesAway minutes."
            else                               -> "A meeting in $minutesAway minutes."
        }
        val titleLabel = if (title != null) "$title in $minutesAway min" else "Meeting in $minutesAway min"
        val bucketKey = startMs / 60_000L * 60_000L

        return ProactiveEvent(
            type          = ProactiveEventType.UPCOMING_MEETING,
            title         = titleLabel,
            spokenText    = spokenText,
            urgency       = urgency,
            relevance     = relevance,
            confidence    = 1.0f,
            annoyanceCost = 0.25f,
            dedupeKey     = "upcoming_meeting_$bucketKey",
            metadata      = buildMap {
                put("nextMeetingAtMillis", startMs.toString())
                if (title != null) put("title", title)
            }
        )
    }

    /**
     * Generate a MEETING_STARTING_SOON event when the next meeting is
     * imminent (≤ meetingUrgentMs, and still within a 30s grace if it just
     * ticked past start).  Lands in ACTIVE tier and bypasses quiet/presence
     * gating via [DecisionEngine].
     */
    private fun generateMeetingStartingSoonEvent(snapshot: ContextSnapshot): ProactiveEvent? {
        val startMs = snapshot.nextMeetingAtMillis ?: return null
        val diffMs = startMs - snapshot.currentTimeMillis
        if (diffMs > config.meetingUrgentMs || diffMs < -30_000L) return null

        val title = snapshot.nextMeetingTitle?.takeIf { it.isNotBlank() }
        val spokenText = when {
            title != null && diffMs <= 60_000L -> "$title starting now."
            title != null                       -> "$title in a minute."
            diffMs <= 60_000L                   -> "Your meeting's starting."
            else                                -> "Meeting in a minute."
        }
        val titleLabel = if (title != null) "$title starting" else "Meeting starting"
        val bucketKey = startMs / 60_000L * 60_000L

        return ProactiveEvent(
            type          = ProactiveEventType.MEETING_STARTING_SOON,
            title         = titleLabel,
            spokenText    = spokenText,
            urgency       = 0.92f,
            relevance     = 0.95f,
            confidence    = 1.0f,
            annoyanceCost = 0.15f,
            dedupeKey     = "meeting_soon_$bucketKey",
            metadata      = buildMap {
                put("nextMeetingAtMillis", startMs.toString())
                if (title != null) put("title", title)
            }
        )
    }

    /**
     * Generate a location-transition event when [ContextSnapshot.lastLocationTransition]
     * carries a fresh unacknowledged transition.  The source clears the slot
     * after the proactive engine dispatches, so no repeat firing.
     *
     * Emits:
     *   - ARRIVED_HOME    — PASSIVE; requires prior absence ≥ arrivedHomeMinAwayMs
     *   - LEFT_HOME       — PASSIVE
     *   - ARRIVED_KNOWN_PLACE — silent (returns null) unless the caller has a
     *                           reason to know, because surfacing every known-place
     *                           arrival is chatty noise.
     */
    private fun generateLocationTransitionEvent(snapshot: ContextSnapshot): ProactiveEvent? {
        val t = snapshot.lastLocationTransition ?: return null
        val place = t.place
        val dateBucket = snapshot.currentTimeMillis / (60 * 60 * 1000L)

        return when (t.placeKind) {
            com.jarvis.assistant.location.PlaceKind.HOME -> when (t.kind) {
                com.jarvis.assistant.location.LocationTransition.Kind.ARRIVED -> {
                    val awayMs = snapshot.currentTimeMillis - place.lastSeenAt
                    if (awayMs < config.arrivedHomeMinAwayMs) return null
                    ProactiveEvent(
                        type          = ProactiveEventType.ARRIVED_HOME,
                        title         = "Arrived home",
                        spokenText    = "Welcome back.",
                        urgency       = 0.40f,
                        relevance     = 0.70f,
                        confidence    = 0.90f,
                        annoyanceCost = 0.30f,
                        dedupeKey     = "arrived_home_$dateBucket"
                    )
                }
                com.jarvis.assistant.location.LocationTransition.Kind.LEFT -> ProactiveEvent(
                    type          = ProactiveEventType.LEFT_HOME,
                    title         = "Heading out",
                    spokenText    = "Heading out.",
                    urgency       = 0.35f,
                    relevance     = 0.55f,
                    confidence    = 0.85f,
                    annoyanceCost = 0.35f,
                    dedupeKey     = "left_home_$dateBucket"
                )
            }
            com.jarvis.assistant.location.PlaceKind.KNOWN -> when (t.kind) {
                com.jarvis.assistant.location.LocationTransition.Kind.ARRIVED -> ProactiveEvent(
                    type          = ProactiveEventType.ARRIVED_KNOWN_PLACE,
                    title         = "Arrived somewhere familiar",
                    spokenText    = null,
                    urgency       = 0.20f,
                    relevance     = 0.25f,
                    confidence    = 0.70f,
                    annoyanceCost = 0.40f,
                    dedupeKey     = "arrived_known_${place.latitude}_${place.longitude}_$dateBucket"
                )
                com.jarvis.assistant.location.LocationTransition.Kind.LEFT -> null
            }
            com.jarvis.assistant.location.PlaceKind.UNKNOWN -> null
        }
    }

    /**
     * Generate a DAILY_AGENDA event once per day during the configured
     * morning window when at least one meeting remains today.  DedupeKey
     * includes the date so a subsequent app restart on the same day won't
     * re-fire once the cooldown store has recorded the surfacing.
     */
    private fun generateDailyAgendaEvent(snapshot: ContextSnapshot): ProactiveEvent? {
        if (snapshot.meetingsTodayCount <= 0) return null
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = snapshot.currentTimeMillis }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        if (hour != config.agendaHourStart) return null

        val yyyymmdd = "%04d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
        val firstTitle = snapshot.nextMeetingTitle?.takeIf { it.isNotBlank() }
        val count = snapshot.meetingsTodayCount
        val plural = if (count == 1) "one meeting" else "$count meetings"
        val spokenText = when {
            firstTitle != null -> "$plural today. First up, $firstTitle."
            else               -> "$plural today."
        }

        return ProactiveEvent(
            type          = ProactiveEventType.DAILY_AGENDA,
            title         = "Today's calendar",
            spokenText    = spokenText,
            urgency       = 0.45f,
            relevance     = 0.60f,
            confidence    = 1.0f,
            annoyanceCost = 0.25f,
            dedupeKey     = "daily_agenda_$yyyymmdd",
            metadata      = mapOf("meetingsTodayCount" to count.toString())
        )
    }
}

// ── DailyBriefBucket ─────────────────────────────────────────────────────────

/**
 * DailyBriefBucket — urgency tier used by [EventGenerator.buildDailyBrief].
 *
 * - [NOW]  — events with urgency >= 0.8; need attention immediately.
 * - [SOON] — events with urgency 0.5–0.8; worth addressing today.
 * - [INFO] — events with urgency < 0.5; informational only.
 */
enum class DailyBriefBucket {
    NOW,
    SOON,
    INFO
}
