package com.jarvis.assistant.proactive.followup

import android.util.Log
import com.jarvis.assistant.core.proactive.ProactiveStrings
import com.jarvis.assistant.proactive.CooldownStore
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * ConversationalProactiveEngine — fires follow-up check-ins and gap check-ins.
 *
 * Runs a low-frequency polling loop (every [POLL_INTERVAL_MS]) that:
 *   1. Expires stale pending follow-ups
 *   2. Marks sent-but-ignored follow-ups as IGNORED
 *   3. Dispatches the next due EVENT or WELLBEING follow-up
 *   4. Falls back to a gap check-in when the user has been silent too long
 *
 * [onCheckIn] is called on [Dispatchers.Main] so callers can safely update
 * UI state and invoke TTS from within it.
 *
 * [cooldownStore] is optional. When supplied, the engine consults the
 * per-key ignore count for `gap_checkin` before firing — matching the same
 * adaptation that EventScorer applies to proactive events — so a user who
 * ignores several check-ins in a row stops getting them instead of seeing
 * the same prompt every 15 minutes.
 *
 * Call [start]/[stop] alongside the existing ProactiveEngine in JarvisRuntime.
 */
class ConversationalProactiveEngine(
    private val followUpRepo : FollowUpRepository,
    private val lastSeen     : LastSeenTracker,
    private val onCheckIn    : suspend (message: String) -> Unit,
    private val cooldownStore: CooldownStore? = null,
) {
    companion object {
        private const val TAG              = "ConvProactiveEngine"
        private const val POLL_INTERVAL_MS = 15 * 60 * 1_000L  // 15 minutes

        /** Cooldown key used to record ignore verdicts for gap check-ins. */
        private const val GAP_CHECKIN_KEY  = "gap_checkin"

        /** Past this many ignores, gap check-ins go silent entirely. */
        private const val GAP_IGNORE_CEILING = 3
    }

    private var job: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Tick error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Started (poll every ${POLL_INTERVAL_MS / 60_000}m)")
    }

    fun stop() {
        job?.cancel()
        job = null
        Log.d(TAG, "Stopped")
    }

    /** Tear down the engine's scope — call from JarvisRuntime.stop(). */
    fun release() {
        stop()
        scope.cancel()
    }

    private suspend fun tick() {
        // Never fire during quiet hours (10pm – 8am)
        if (isQuietHours()) return

        // Global gap guard — don't stack proactives
        if (!lastSeen.canSendProactive()) return

        // Housekeeping
        followUpRepo.expireStale()
        followUpRepo.markSentAsIgnored()

        // 1. Event / wellbeing follow-ups (highest priority)
        val due = followUpRepo.getDue()
        if (due.isNotEmpty()) {
            val chosen = due.first()
            Log.d(TAG, "Dispatching follow-up id=${chosen.id} '${chosen.promptTemplate}'")
            dispatch(chosen.promptTemplate)
            followUpRepo.markSent(chosen)
            return
        }

        // 2. Inactivity gap check-in (only when no other follow-up fired)
        if (lastSeen.isInactive()) {
            val ignoreCount = cooldownStore?.ignoreCount(GAP_CHECKIN_KEY) ?: 0
            if (ignoreCount >= GAP_IGNORE_CEILING) {
                Log.v(TAG, "Skipping gap check-in — ignored $ignoreCount times")
                return
            }
            val pool = ProactiveStrings.gapCheckIns
            if (pool.isEmpty()) return
            val msg = pool.random()
            Log.d(TAG, "Dispatching gap check-in (ignoreCount=$ignoreCount): '$msg'")
            dispatch(msg)
            cooldownStore?.markSurfaced(GAP_CHECKIN_KEY)
        }
    }

    private suspend fun dispatch(message: String) {
        withContext(Dispatchers.Main) {
            onCheckIn(message)
        }
        lastSeen.touchProactive()
    }

    private fun isQuietHours(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 8 || hour >= 22
    }
}
