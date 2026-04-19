package com.jarvis.assistant.proactive

/**
 * ContextSnapshot — an immutable, point-in-time view of the device and
 * assistant state captured once per polling tick by [ProactiveEngine].
 *
 * All fields are plain types; no Android framework objects escape into the
 * scoring pipeline, making the model easy to construct in unit tests.
 *
 * @param currentTimeMillis             Wall-clock time at snapshot creation.
 * @param batteryLevel                  Current battery percentage (0–100).
 * @param isCharging                    True if the device is connected to a charger.
 * @param screenOn                      True if the display is currently interactive.
 * @param isJarvisSpeaking              True if TTS output is currently playing.
 * @param isJarvisListening             True if the speech recogniser is open.
 * @param lastUserInteractionTimeMillis Wall-clock ms of the most recent user
 *                                      voice interaction, or null if none recorded.
 * @param activeReminderCount           Number of PENDING reminders in the future.
 * @param nextReminderAtMillis          Epoch ms of the soonest pending reminder,
 *                                      or null if there are none.
 * @param missedCallsCount              Number of unacknowledged missed calls.
 * @param lastMissedCallAtMillis        Epoch ms of the most recent missed call,
 *                                      or null if [missedCallsCount] is zero.
 * @param lastMissedCallContactName     Display name of the caller, or null if
 *                                      unknown or [missedCallsCount] is zero.
 * @param currentLocationName           City-level location string (e.g. "London, UK"),
 *                                      or null if unavailable / permission denied.
 * @param networkAvailable              True if the device has a validated internet connection.
 */
data class ContextSnapshot(
    val currentTimeMillis: Long,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val screenOn: Boolean,
    val isJarvisSpeaking: Boolean,
    val isJarvisListening: Boolean,
    val lastUserInteractionTimeMillis: Long?,
    val activeReminderCount: Int,
    val nextReminderAtMillis: Long?,
    val missedCallsCount: Int,
    val lastMissedCallAtMillis: Long?,
    val lastMissedCallContactName: String?,
    val currentLocationName: String?,
    val networkAvailable: Boolean,
    /** Number of unread notifications from important (non-Jarvis) apps since last check. */
    val unreadNotificationCount: Int = 0,
    /** Title/text of the most recent unread notification, or null. */
    val lastNotificationText: String? = null,
    /** Package name of the app that sent the last notification, or null. */
    val lastNotificationApp: String? = null,
    /** Top brain prediction description for proactive context push, or null. */
    val topPredictionDescription: String? = null,
    /** Confidence score [0,1] of the top prediction. */
    val topPredictionScore: Float = 0f,
    /** Related knowledge context to surface alongside the prediction, or null. */
    val predictionKnowledgeContext: String? = null,
    /**
     * True when the device is in driving mode (car Bluetooth or dock
     * connected).  Used by the presence gate to suppress soft notifications
     * the user can't safely read.  Defaults to false so existing callers and
     * tests keep working without changes.
     */
    val isDriving: Boolean = false,
    /** Epoch ms of the next calendar meeting, or null if none in look-ahead. */
    val nextMeetingAtMillis: Long? = null,
    /** Sanitised title of the next meeting, or null. */
    val nextMeetingTitle: String? = null,
    /** Epoch ms when the next meeting ends (may be null even when start is set). */
    val nextMeetingEndMillis: Long? = null,
    /** Count of meetings on today's calendar (all-day and timed, after now). */
    val meetingsTodayCount: Int = 0,
    /**
     * Most recent unacknowledged location transition detected by
     * [com.jarvis.assistant.location.PlaceLearner], or null.
     */
    val lastLocationTransition: com.jarvis.assistant.location.LocationTransition? = null
)
