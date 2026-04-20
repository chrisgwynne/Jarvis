package com.jarvis.assistant.core.outcomes

import android.util.Log
import com.jarvis.assistant.core.decisions.ActionLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * OutcomeMemoryFeedback — long-lived subscriber that turns repeated
 * negative outcomes into ledger-level suppression.
 *
 * Listens on [OutcomeRecorder.outcomes]. Whenever a USER_CORRECTED outcome
 * arrives for an [Outcome.actionClass], it checks how many similar
 * corrections have landed in the trailing window. If the count crosses
 * [thresholdForSuppression], the class gets suppressed via
 * [ActionLedger.suppressClass], which [MemoryPolicy] / [EventScorer] already
 * respect.
 *
 * This is the feedback arrow closing the learning loop: signals → situation
 * → goal → plan → action → **outcome → memory**.
 */
class OutcomeMemoryFeedback(
    private val recorder: OutcomeRecorder,
    private val ledger: ActionLedger,
    private val thresholdForSuppression: Int = 3,
    private val windowMs: Long = 14L * 24 * 60 * 60 * 1000, // 14 days
) {

    private var job: Job? = null

    /** Start listening. Idempotent — calling start twice reuses the first subscription. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            recorder.outcomes
                .filter { it.type == OutcomeType.USER_CORRECTED && it.actionClass != null }
                .collect { outcome ->
                    val cls = outcome.actionClass ?: return@collect
                    if (ledger.isClassSuppressed(cls)) return@collect
                    val recent = recorder.countRecent(cls, OutcomeType.USER_CORRECTED, windowMs)
                    if (recent >= thresholdForSuppression) {
                        Log.i(
                            TAG,
                            "Auto-suppressing class=$cls after $recent recent USER_CORRECTED outcomes",
                        )
                        ledger.suppressClass(cls)
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "OutcomeMemoryFeedback"
    }
}
