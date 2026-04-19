package com.jarvis.assistant.proactive

// ── Data classes ─────────────────────────────────────────────────────────────

/**
 * NextReminderInfo — the soonest pending reminder returned by [ReminderContextSource].
 *
 * @param triggerAtMillis Epoch ms when the reminder will fire.
 * @param label           The text that will be spoken when the reminder fires.
 */
data class NextReminderInfo(
    val triggerAtMillis: Long,
    val label: String
)

/**
 * MissedCallInfo — aggregated missed-call state returned by [CallContextSource].
 *
 * @param count              Total number of unacknowledged missed calls.
 * @param lastCallAtMillis   Epoch ms of the most recent missed call.
 * @param contactName        Display name of the most recent caller, or null if unknown.
 */
data class MissedCallInfo(
    val count: Int,
    val lastCallAtMillis: Long,
    val contactName: String?
)

/**
 * JarvisSpeechState — snapshot of the assistant's voice pipeline state
 * returned by [SpeechStateSource].
 *
 * @param isSpeaking             True if TTS output is currently active.
 * @param isListening            True if the speech recogniser is open.
 * @param lastUserInteractionMs  Epoch ms of the last detected user voice interaction,
 *                               or null if none has been recorded.
 */
data class JarvisSpeechState(
    val isSpeaking: Boolean,
    val isListening: Boolean,
    val lastUserInteractionMs: Long?
)

// ── Interfaces ────────────────────────────────────────────────────────────────

/**
 * ReminderContextSource — provides reminder information to the proactive engine.
 *
 * Implementations bridge the gap between the engine (which is framework-agnostic)
 * and the [ReminderRepository] (which uses Room and coroutines).
 */
interface ReminderContextSource {
    /** Returns the soonest PENDING reminder in the future, or null if none exist. */
    suspend fun getNextPendingReminder(): NextReminderInfo?

    /** Returns the count of PENDING reminders with a trigger time in the future. */
    suspend fun getPendingReminderCount(): Int
}

/**
 * CallContextSource — provides missed-call information to the proactive engine.
 *
 * Implementations are expected to maintain their own internal state, updated
 * by the call subsystem (e.g. [AppCallContextSource.recordMissedCall]).
 */
interface CallContextSource {
    /**
     * Returns the current missed-call state, or null if there are no
     * unacknowledged missed calls.
     */
    fun getMissedCallInfo(): MissedCallInfo?
}

/**
 * BatteryContextSource — provides device hardware state to the proactive engine.
 *
 * All methods are synchronous because they delegate to fast system-service
 * reads with no I/O.
 */
interface BatteryContextSource {
    /** Battery charge level as a percentage (0–100). */
    fun getBatteryLevel(): Int

    /** True if the device is currently connected to a charger. */
    fun isCharging(): Boolean

    /** True if the screen is currently on and interactive. */
    fun isScreenOn(): Boolean

    /** True if the device has a validated internet connection. */
    fun isNetworkAvailable(): Boolean

    /**
     * City-level location string (e.g. "London, United Kingdom"), or null if
     * the permission is not granted or no location fix is available.
     */
    fun getLocationName(): String?
}

/**
 * SpeechStateSource — provides the current voice pipeline state to the engine.
 */
interface SpeechStateSource {
    /** Returns a point-in-time snapshot of the assistant's speech state. */
    fun getSpeechState(): JarvisSpeechState
}

/**
 * BrainPredictionSource — provides top behavioural predictions for the proactive engine.
 */
interface BrainPredictionSource {
    /**
     * Returns the highest-scoring current prediction as a human-readable description,
     * or null if no prediction clears the confidence threshold.
     */
    suspend fun getTopPrediction(): BrainPrediction?
}

data class BrainPrediction(
    val description: String,
    val score: Float,
    val eventType: String,
    /** Related knowledge context surfaced by KnowledgeQueryEngine, or null. */
    val knowledgeContext: String? = null
)

/**
 * CalendarMeeting — a minimal upcoming-meeting record returned by [CalendarContextSource].
 *
 * Title is expected to be sanitised by the source (no Zoom URLs, phone numbers, etc.)
 * before being returned; it may still be null if the source chose to redact it.
 */
data class CalendarMeeting(
    val startMs: Long,
    val endMs: Long,
    val title: String?,
    val isAllDay: Boolean
)

/**
 * CalendarContextSource — provides upcoming calendar data to the proactive engine.
 *
 * Implementations should cache short-term (~60s) to avoid hammering the
 * CalendarContract content provider on every polling tick, and must return
 * an empty list silently when READ_CALENDAR is not granted.
 */
interface CalendarContextSource {
    /**
     * Returns timed meetings starting within the next [lookAheadMs], ordered
     * by start time.  Returns an empty list when permission is denied or the
     * calendar provider is unavailable.
     */
    suspend fun getUpcomingMeetings(lookAheadMs: Long): List<CalendarMeeting>

    /** Count of meetings remaining today (after now), including all-day events. */
    suspend fun getMeetingsRemainingToday(): Int
}

/**
 * NotificationContextSource — provides recent unread notification data to
 * the proactive engine so Jarvis can proactively announce important alerts.
 */
interface NotificationContextSource {
    /** Returns the count of unread notifications since the last acknowledgement. */
    fun getUnreadCount(): Int

    /** Returns the text snippet of the most recent unread notification, or null. */
    fun getLastNotificationText(): String?

    /** Returns the package name of the app that sent the last notification, or null. */
    fun getLastNotificationApp(): String?

    /** Called by the engine after surfacing a notification event — resets unread count. */
    fun acknowledge()
}

/**
 * LocationContextSource — provides learned-place transition events to the
 * proactive engine.  A "pending" transition is one detected since the last
 * [acknowledge] call; the engine consumes and acks at dispatch time.
 */
interface LocationContextSource {
    /** The most recent unacknowledged transition, or null. */
    suspend fun getPendingTransition(): com.jarvis.assistant.location.LocationTransition?

    /** Clear the pending-transition slot after the engine has surfaced it. */
    fun acknowledge()
}

/**
 * ProactiveDispatcher — delivers a [ProactiveAction] to the user.
 *
 * Implementations decide what "delivering" means for each action type
 * (e.g. calling [TtsEngine.speak] for a [ProactiveAction.SpeakAction]).
 */
interface ProactiveDispatcher {
    /** Deliver [action] to the user in the appropriate modality. */
    suspend fun dispatch(action: ProactiveAction)
}
