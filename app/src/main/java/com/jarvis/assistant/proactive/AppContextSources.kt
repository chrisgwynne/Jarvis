package com.jarvis.assistant.proactive

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.jarvis.assistant.context.ContextEngine
import com.jarvis.assistant.core.state.JarvisState
import com.jarvis.assistant.core.state.JarvisStateMachine
import com.jarvis.assistant.reminders.ReminderRepository

// ── AppBatterySource ──────────────────────────────────────────────────────────

/**
 * AppBatterySource — [BatteryContextSource] backed by Android system services
 * and the existing [ContextEngine].
 *
 * All reads are cheap synchronous calls; no I/O or coroutines required.
 *
 * @param context       Application context used to access system services.
 * @param contextEngine Existing [ContextEngine] instance; used for network and
 *                      future location integration.
 */
class AppBatterySource(
    private val context: Context,
    private val contextEngine: ContextEngine
) : BatteryContextSource {

    companion object {
        private const val TAG = "AppBatterySource"
    }

    override fun getBatteryLevel(): Int {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (level < 0) Log.w(TAG, "BatteryManager returned negative capacity ($level)")
        return level.coerceAtLeast(0)
    }

    override fun isCharging(): Boolean {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun isScreenOn(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isInteractive
    }

    override fun isNetworkAvailable(): Boolean = contextEngine.isOnline()

    /**
     * Returns the cached city-level location string from [ContextEngine.build].
     *
     * NOTE: [ContextEngine.cachedLocation] is private, so we trigger a full
     * [ContextEngine.build] call which has its own 5-minute TTL cache internally.
     * When the `cachedLocation` field is made `internal` or a dedicated
     * `getLocationName(): String?` accessor is added to [ContextEngine], wire
     * it here instead to avoid the minor overhead of building a full
     * [DeviceContext] on every snapshot.
     */
    override fun getLocationName(): String? = try {
        contextEngine.build().location
    } catch (e: Exception) {
        Log.w(TAG, "Could not retrieve location from ContextEngine", e)
        null
    }
}

// ── AppReminderSource ─────────────────────────────────────────────────────────

/**
 * AppReminderSource — [ReminderContextSource] backed by [ReminderRepository].
 *
 * Filters results to items whose trigger time is strictly in the future so
 * that reminders that just fired are not reported as "upcoming".
 *
 * @param repository The application-wide [ReminderRepository].
 */
class AppReminderSource(
    private val repository: ReminderRepository
) : ReminderContextSource {

    override suspend fun getNextPendingReminder(): NextReminderInfo? {
        val now = System.currentTimeMillis()
        return repository.getPending()
            .filter { it.triggerAtMs > now }
            .minByOrNull { it.triggerAtMs }
            ?.let { item ->
                NextReminderInfo(
                    triggerAtMillis = item.triggerAtMs,
                    label           = item.label
                )
            }
    }

    override suspend fun getPendingReminderCount(): Int {
        val now = System.currentTimeMillis()
        return repository.getPending().count { it.triggerAtMs > now }
    }
}

// ── AppCallContextSource ──────────────────────────────────────────────────────

/**
 * AppCallContextSource — [CallContextSource] backed by in-memory state updated
 * by the call subsystem.
 *
 * This class acts as a bridge between the telephony layer and the proactive
 * engine.  The existing [CallCoordinator] (or a future missed-call monitor)
 * should call [recordMissedCall] whenever a call goes unanswered, and
 * [clearMissedCalls] after the user has been informed.
 *
 * Thread safety: all state is guarded by [@Volatile] and the simple
 * compare-and-assign pattern; a full mutex is not required because writes are
 * rare and the worst-case race is a missed notification, not a crash.
 */
class AppCallContextSource : CallContextSource {

    companion object {
        private const val TAG = "AppCallContextSource"
    }

    @Volatile private var currentInfo: MissedCallInfo? = null

    /**
     * Record a new missed call.
     *
     * If there are already unacknowledged missed calls, the count is
     * incremented and the most recent call details replace the previous ones.
     *
     * @param callAtMs    Epoch ms when the call was detected as missed.
     * @param contactName Display name of the caller, or null if unknown.
     */
    fun recordMissedCall(callAtMs: Long, contactName: String?) {
        val existing = currentInfo
        currentInfo = MissedCallInfo(
            count            = (existing?.count ?: 0) + 1,
            lastCallAtMillis = callAtMs,
            contactName      = contactName
        )
        Log.d(TAG, "Recorded missed call: count=${currentInfo?.count}, contact=$contactName")
    }

    override fun getMissedCallInfo(): MissedCallInfo? = currentInfo

    /**
     * Clear all missed-call state.
     *
     * Call this once the user has been notified (e.g. after [ProactiveDispatcher]
     * delivers a [ProactiveAction.SpeakAction] or [ProactiveAction.PassiveAction]
     * for a MISSED_CALL event).
     */
    fun clearMissedCalls() {
        Log.d(TAG, "Clearing missed calls (was count=${currentInfo?.count})")
        currentInfo = null
    }
}

// ── AppSpeechStateSource ──────────────────────────────────────────────────────

/**
 * AppSpeechStateSource — [SpeechStateSource] backed by [JarvisStateMachine].
 *
 * Reads [JarvisStateMachine.current] to determine whether Jarvis is speaking
 * or listening, and maintains its own timestamp for the last user voice
 * interaction so the proactive engine can apply the recency penalty.
 *
 * @param machine The [JarvisStateMachine] instance from [JarvisRuntime].
 */
class AppSpeechStateSource(
    private val machine: JarvisStateMachine
) : SpeechStateSource {

    companion object {
        private const val TAG = "AppSpeechStateSource"
    }

    @Volatile private var lastInteractionMs: Long? = null

    /**
     * Record that the user just interacted via voice.
     *
     * Call this from [JarvisRuntime] immediately after a non-blank transcript
     * is captured so the proactive engine can apply the recency penalty.
     */
    fun recordUserInteraction() {
        lastInteractionMs = System.currentTimeMillis()
        Log.v(TAG, "User interaction recorded at ${lastInteractionMs}ms")
    }

    override fun getSpeechState(): JarvisSpeechState {
        val current = machine.current
        return JarvisSpeechState(
            isSpeaking           = current is JarvisState.Speaking,
            isListening          = current is JarvisState.Listening ||
                                   current is JarvisState.Interrupted ||
                                   current is JarvisState.WaitingCallCommand,
            lastUserInteractionMs = lastInteractionMs
        )
    }
}
