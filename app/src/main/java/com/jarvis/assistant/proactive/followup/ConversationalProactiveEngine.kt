package com.jarvis.assistant.proactive.followup

import android.util.Log
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
 * Call [start]/[stop] alongside the existing ProactiveEngine in JarvisRuntime.
 */
class ConversationalProactiveEngine(
    private val followUpRepo : FollowUpRepository,
    private val lastSeen     : LastSeenTracker,
    private val onCheckIn    : suspend (message: String) -> Unit
) {
    companion object {
        private const val TAG              = "ConvProactiveEngine"
        private const val POLL_INTERVAL_MS = 15 * 60 * 1_000L  // 15 minutes

        private val GAP_CHECK_INS = listOf(
            "You good?",
            "How's things?",
            "Been quiet — everything alright?",
            "Still around?",
            "How's it going?"
        )
    }

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                try { tick() } catch (e: Exception) {
                    if (e is CancellationException) throw e
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
            val msg = GAP_CHECK_INS.random()
            Log.d(TAG, "Dispatching gap check-in: '$msg'")
            dispatch(msg)
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
