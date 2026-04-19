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
        generateBrainContextEvent(snapshot)
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
