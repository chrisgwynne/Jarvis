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
 * ProactiveDispatcher — delivers a [ProactiveAction] to the user.
 *
 * Implementations decide what "delivering" means for each action type
 * (e.g. calling [TtsEngine.speak] for a [ProactiveAction.SpeakAction]).
 */
interface ProactiveDispatcher {
    /** Deliver [action] to the user in the appropriate modality. */
    suspend fun dispatch(action: ProactiveAction)
}
