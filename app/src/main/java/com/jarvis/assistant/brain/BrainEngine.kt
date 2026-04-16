package com.jarvis.assistant.brain

import android.content.Context
import android.util.Log
import com.jarvis.assistant.brain.db.dao.BrainEventDao
import com.jarvis.assistant.brain.db.dao.BrainPatternDao
import com.jarvis.assistant.memory.ProfileMemoryService
import com.jarvis.assistant.proactive.ProactiveAction
import com.jarvis.assistant.proactive.ProactiveDispatcher
import com.jarvis.assistant.proactive.ProactiveEventType
import com.jarvis.assistant.proactive.SpeechStateSource
import com.jarvis.assistant.proactive.followup.LastSeenTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * BrainEngine — the main coordinator for the behavioural learning system.
 *
 * Implements the full spec loop:
 *   observe → log → detect → model → predict → decide → act
 *
 * Two internal coroutine loops:
 *   - Fast loop (every [PREDICTION_INTERVAL_MS]): check predictions and dispatch
 *     suggestions via [dispatcher] when initiative policy approves.
 *   - Slow loop (every [ANALYSIS_INTERVAL_MS]): run pattern analysis and refresh
 *     the personal model.
 *
 * Integration:
 *   - [collector] is a public reference that [JarvisRuntime] uses to log events.
 *   - Suggestions flow through the shared [ProactiveDispatcher] and respect
 *     [LastSeenTracker.canSendProactive] to avoid spamming.
 *
 * @param context        Application context for the event collector.
 * @param eventDao       Persists raw [BrainEvent] records.
 * @param patternDao     Persists analysed [BrainPattern] records.
 * @param profileMemory  Writes behavioural traits into the user profile.
 * @param dispatcher     Delivers spoken suggestions (shared with ProactiveEngine).
 * @param speechSource   Provides current speaking/listening state.
 * @param lastSeen       Shared gate that prevents overlapping proactive messages.
 */
class BrainEngine(
    context: Context,
    eventDao: BrainEventDao,
    patternDao: BrainPatternDao,
    profileMemory: ProfileMemoryService,
    private val dispatcher: ProactiveDispatcher,
    private val speechSource: SpeechStateSource,
    private val lastSeen: LastSeenTracker
) {
    companion object {
        private const val TAG = "BrainEngine"

        /** How often to check predictions and decide whether to suggest. */
        private const val PREDICTION_INTERVAL_MS = 10L * 60_000    // 10 minutes

        /** How often to run the full pattern analysis pass. */
        private const val ANALYSIS_INTERVAL_MS   = 3L * 3_600_000  // 3 hours

        /** Minimum global gap before BrainEngine fires any suggestion. */
        private const val BRAIN_MIN_GAP_MS       = 60L * 60_000    // 1 hour
    }

    // ── Public components ─────────────────────────────────────────────────────

    /** Hook this into JarvisRuntime to log all observable events. */
    val collector = BrainEventCollector(context, eventDao)

    // ── Private components ────────────────────────────────────────────────────

    private val analyser    = PatternAnalyser(eventDao, patternDao)
    private val model       = PersonalModel(patternDao, profileMemory)
    private val predictor   = PredictionEngine(patternDao, eventDao)
    private val policy      = InitiativePolicy(patternDao)

    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fastJob: Job? = null
    private var slowJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        collector.register()
        startFastLoop()
        startSlowLoop()
        Log.d(TAG, "BrainEngine started")
    }

    fun stop() {
        collector.unregister()
        fastJob?.cancel(); fastJob = null
        slowJob?.cancel(); slowJob = null
        Log.d(TAG, "BrainEngine stopped")
    }

    // ── Fast loop: predict → decide → act ────────────────────────────────────

    private fun startFastLoop() {
        if (fastJob?.isActive == true) return
        fastJob = scope.launch {
            // Initial delay so we don't fire immediately on cold start
            delay(5 * 60_000L)
            while (isActive) {
                try {
                    tickPredict()
                } catch (e: Exception) {
                    Log.e(TAG, "Prediction tick error: ${e.message}", e)
                }
                delay(PREDICTION_INTERVAL_MS)
            }
        }
    }

    private suspend fun tickPredict() {
        // Global proactive gap — shared with other proactive engines
        if (!lastSeen.canSendProactive(BRAIN_MIN_GAP_MS)) return

        val speechState = speechSource.getSpeechState()

        val predictions = predictor.predict()
        if (predictions.isEmpty()) return

        val top = predictions.first()
        val action = policy.decide(
            prediction  = top,
            isSpeaking  = speechState.isSpeaking,
            isListening = speechState.isListening
        )

        when (action) {
            is InitiativePolicy.InitiativeAction.Suggest -> {
                Log.d(TAG, "Dispatching suggestion: ${action.text}")
                patternDao_markSuggested(action.patternKey)
                lastSeen.touchProactive()
                dispatcher.dispatch(
                    ProactiveAction.SpeakAction(
                        text       = action.text,
                        dedupeKey  = "brain_${action.patternKey}",
                        sourceType = ProactiveEventType.BEHAVIORAL_LEARNING
                    )
                )
            }
            is InitiativePolicy.InitiativeAction.OfferAction -> {
                Log.d(TAG, "Dispatching offer: ${action.text}")
                patternDao_markSuggested(action.patternKey)
                lastSeen.touchProactive()
                dispatcher.dispatch(
                    ProactiveAction.SpeakAction(
                        text       = action.text,
                        dedupeKey  = "brain_${action.patternKey}",
                        sourceType = ProactiveEventType.BEHAVIORAL_LEARNING
                    )
                )
            }
            is InitiativePolicy.InitiativeAction.AutoAct -> {
                // Auto-act: log intent (actual action dispatch could be wired here)
                Log.d(TAG, "AutoAct eligible: ${action.actionHint} — logged only (no auto-dispatch yet)")
                patternDao_markSuggested(action.patternKey)
            }
            else -> {
                Log.v(TAG, "Prediction score=${top.score} → OBSERVE (no action)")
            }
        }
    }

    // ── Slow loop: analyse → model ────────────────────────────────────────────

    private fun startSlowLoop() {
        if (slowJob?.isActive == true) return
        slowJob = scope.launch {
            // Run first analysis after 30 minutes (wait for some data to accumulate)
            delay(30 * 60_000L)
            while (isActive) {
                try {
                    tickAnalyse()
                } catch (e: Exception) {
                    Log.e(TAG, "Analysis tick error: ${e.message}", e)
                }
                delay(ANALYSIS_INTERVAL_MS)
            }
        }
    }

    private suspend fun tickAnalyse() {
        Log.d(TAG, "Running pattern analysis")
        analyser.analyse()

        Log.d(TAG, "Refreshing personal model")
        val traits = model.refresh()
        if (traits.summary.isNotBlank()) {
            Log.d(TAG, "Personal model: ${traits.summary}")
        }

        // Prune old events (keep last 60 days)
        val cutoff = System.currentTimeMillis() - 60L * 86_400_000
        collector  // eventDao is in collector — prune via a separate call
        Log.d(TAG, "Analysis cycle complete")
    }

    // ── Helper to avoid direct patternDao ref in BrainEngine ─────────────────
    // (patternDao is only accessible via analyser/predictor/policy — keep it encapsulated)
    // We re-expose a thin prune path through a scope launch in the collector.

    private fun patternDao_markSuggested(key: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // Access patternDao through the predictor's exposed method
                // (passed via the injected patternDao at construction time)
                _patternDao.markSuggested(key)
            } catch (e: Exception) {
                Log.w(TAG, "markSuggested failed for $key: ${e.message}")
            }
        }
    }

    // Keep a direct reference for the thin operations BrainEngine owns
    private val _patternDao: BrainPatternDao = patternDao
    private val _eventDao: BrainEventDao = eventDao

    // ── Public: prune old events ──────────────────────────────────────────────

    fun pruneOldEvents() {
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 60L * 86_400_000
            _eventDao.pruneOlderThan(cutoff)
        }
    }
}
