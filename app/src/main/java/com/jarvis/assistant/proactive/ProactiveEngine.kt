package com.jarvis.assistant.proactive

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ProactiveEngine — the main coordinator for the proactive alert system.
 *
 * Runs a background polling loop that wakes every [ProactiveConfig.pollingIntervalMs],
 * builds a [ContextSnapshot], generates and scores [ProactiveEvent] candidates,
 * makes a final dispatch decision, and forwards any resulting [ProactiveAction]
 * to the [ProactiveDispatcher].
 *
 * ## Thread safety
 *
 * The engine is safe to start/stop from any thread.  The polling loop itself
 * runs on [Dispatchers.Default].  All adapter calls that may block (e.g. DB reads
 * via [ReminderContextSource]) are made on the same dispatcher.
 *
 * ## Exception handling
 *
 * Each tick is wrapped in try/catch so a transient error (network blip, DB
 * contention) never kills the polling loop.  Errors are logged at ERROR level
 * so they are visible in logcat without crashing the service.
 *
 * ## Integration
 *
 * Wire this engine from [JarvisRuntime] or [JarvisService]:
 *
 * ```kotlin
 * // In JarvisRuntime.start():
 * proactiveEngine.start()
 *
 * // In JarvisRuntime.stop():
 * proactiveEngine.stop()
 * ```
 *
 * @param config         Configuration constants.
 * @param reminderSource Provides reminder data (suspending — may query DB).
 * @param callSource     Provides missed-call state (non-suspending, in-memory).
 * @param batterySource  Provides device hardware state (non-suspending).
 * @param speechSource   Provides Jarvis voice pipeline state (non-suspending).
 * @param dispatcher     Delivers the chosen [ProactiveAction] to the user.
 */
class ProactiveEngine(
    private val config: ProactiveConfig,
    private val reminderSource: ReminderContextSource,
    private val callSource: CallContextSource,
    private val batterySource: BatteryContextSource,
    private val speechSource: SpeechStateSource,
    private val dispatcher: ProactiveDispatcher,
    private val notificationSource: NotificationContextSource? = null,
    private val brainPredictionSource: BrainPredictionSource? = null,
    /**
     * Supplies the current driving state on each tick.  Optional to keep
     * tests and legacy callers simple — defaults to "not driving" when not
     * wired up.  JarvisRuntime passes `drivingModeManager::isDriving`.
     */
    private val isDrivingProvider: () -> Boolean = { false }
) {

    companion object {
        private const val TAG = "ProactiveEngine"
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private val cooldownStore   = CooldownStore()
    private val eventGenerator  = EventGenerator(config)
    private val eventScorer     = EventScorer(config, cooldownStore)
    private val decisionEngine  = DecisionEngine(config, cooldownStore)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    /**
     * Last dispatched surfacing pending an ignore/accept verdict.  On each tick
     * we check whether the user has interacted since [dispatchedAt]; if
     * [ProactiveConfig.ignoreCheckDelayMs] has elapsed with no interaction the
     * action counts as ignored and future effective cooldowns for [dedupeKey]
     * stretch by [ProactiveConfig.ignoreEscalationFactor].
     */
    private data class PendingVerdict(val dedupeKey: String, val dispatchedAt: Long)
    @Volatile private var pendingVerdict: PendingVerdict? = null
    private val verdictLock = Mutex()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start the polling loop.
     *
     * Safe to call multiple times; a second call while the loop is running
     * is a no-op.
     */
    fun start() {
        if (loopJob?.isActive == true) {
            Log.d(TAG, "start() called but loop is already running — ignoring")
            return
        }
        Log.d(TAG, "Starting proactive polling (interval=${config.pollingIntervalMs}ms)")
        loopJob = scope.launch {
            while (isActive) {
                val t0 = System.currentTimeMillis()
                try {
                    tick()
                } catch (e: Exception) {
                    Log.e(TAG, "Unhandled exception in proactive tick — will retry next interval", e)
                }
                // Single-line, tag=Jarvis event so a battery-drain regression shows
                // up in `adb logcat -s Jarvis | grep event=proactive_tick`.
                val dur = System.currentTimeMillis() - t0
                Log.d(TAG, "event=proactive_tick duration_ms=$dur")
                delay(config.pollingIntervalMs)
            }
        }
    }

    /**
     * Stop the polling loop.
     *
     * Safe to call when the loop is not running.  After this call, no further
     * ticks will occur and no dispatcher calls will be made.
     */
    fun stop() {
        Log.d(TAG, "Stopping proactive polling")
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * Build a daily brief by running the event generator against the current
     * context and grouping results into [DailyBriefBucket] tiers.
     *
     * This method is independent of the polling loop and can be called at any
     * time (e.g. in response to a user command "give me a morning briefing").
     */
    suspend fun buildDailyBrief(): Map<DailyBriefBucket, List<ProactiveEvent>> {
        val snapshot = buildSnapshot()
        return eventGenerator.buildDailyBrief(snapshot)
    }

    // ── Private: tick ─────────────────────────────────────────────────────────

    /**
     * One iteration of the proactive polling loop:
     * build snapshot → generate events → score → decide → dispatch.
     */
    private suspend fun tick() {
        val snapshot  = buildSnapshot()

        // Resolve any pending ignore/accept verdict from a previous dispatch.
        resolvePendingVerdict(snapshot)

        val events    = eventGenerator.generate(snapshot)
        ProactiveMetrics.increment(ProactiveMetrics.Counter.EVENTS_GENERATED, events.size.toLong())

        if (events.isEmpty()) {
            Log.v(TAG, "tick: no events generated")
            return
        }

        val scored    = eventScorer.scoreAll(events, snapshot)
        val action    = decisionEngine.decide(scored, snapshot)

        if (action !is ProactiveAction.NoAction) {
            ProactiveMetrics.increment(ProactiveMetrics.Counter.ACTIONS_DISPATCHED)
            dispatch(action)
        }
    }

    /**
     * If there was a recent dispatch awaiting a verdict, decide whether the
     * user accepted (interacted after it) or ignored it (no interaction within
     * the configured window) and update the [CooldownStore] accordingly.
     */
    private suspend fun resolvePendingVerdict(snapshot: ContextSnapshot) = verdictLock.withLock {
        val verdict = pendingVerdict ?: return@withLock
        val lastUser = snapshot.lastUserInteractionTimeMillis
        if (lastUser != null && lastUser > verdict.dispatchedAt) {
            cooldownStore.markAccepted(verdict.dedupeKey)
            Log.d(TAG, "Accepted verdict for ${verdict.dedupeKey}")
            ProactiveMetrics.increment(ProactiveMetrics.Counter.VERDICT_ACCEPTED)
            pendingVerdict = null
            return@withLock
        }
        val age = snapshot.currentTimeMillis - verdict.dispatchedAt
        if (age >= config.ignoreCheckDelayMs) {
            cooldownStore.markIgnored(verdict.dedupeKey)
            Log.d(
                TAG,
                "Ignored verdict for ${verdict.dedupeKey} " +
                "(count=${cooldownStore.ignoreCount(verdict.dedupeKey)})"
            )
            ProactiveMetrics.increment(ProactiveMetrics.Counter.VERDICT_IGNORED)
            pendingVerdict = null
        }
    }

    /**
     * Build a [ContextSnapshot] by querying all source adapters.
     *
     * Suspending sources (DB reads) are awaited here.  All non-suspending
     * sources are called synchronously.
     */
    private suspend fun buildSnapshot(): ContextSnapshot {
        // Suspending calls
        val nextReminder  = reminderSource.getNextPendingReminder()
        val reminderCount = reminderSource.getPendingReminderCount()
        val topPrediction = brainPredictionSource?.getTopPrediction()

        // Non-suspending calls
        val speechState  = speechSource.getSpeechState()
        val missedCall   = callSource.getMissedCallInfo()

        return ContextSnapshot(
            currentTimeMillis             = System.currentTimeMillis(),
            batteryLevel                  = batterySource.getBatteryLevel(),
            isCharging                    = batterySource.isCharging(),
            screenOn                      = batterySource.isScreenOn(),
            isJarvisSpeaking              = speechState.isSpeaking,
            isJarvisListening             = speechState.isListening,
            lastUserInteractionTimeMillis = speechState.lastUserInteractionMs,
            activeReminderCount           = reminderCount,
            nextReminderAtMillis          = nextReminder?.triggerAtMillis,
            missedCallsCount              = missedCall?.count ?: 0,
            lastMissedCallAtMillis        = missedCall?.lastCallAtMillis,
            lastMissedCallContactName     = missedCall?.contactName,
            currentLocationName           = batterySource.getLocationName(),
            networkAvailable              = batterySource.isNetworkAvailable(),
            unreadNotificationCount       = notificationSource?.getUnreadCount() ?: 0,
            lastNotificationText          = notificationSource?.getLastNotificationText(),
            lastNotificationApp           = notificationSource?.getLastNotificationApp(),
            topPredictionDescription      = topPrediction?.description,
            topPredictionScore            = topPrediction?.score ?: 0f,
            predictionKnowledgeContext    = topPrediction?.knowledgeContext,
            isDriving                     = isDrivingProvider()
        )
    }

    /**
     * Record the cooldown timestamp then forward [action] to the [dispatcher].
     */
    private suspend fun dispatch(action: ProactiveAction) {
        // Mark the dedupeKey in the cooldown store so subsequent ticks respect
        // the per-type and global gap windows, and record a pending verdict so
        // the next tick can adapt if the user ignores this suggestion.
        val key = when (action) {
            is ProactiveAction.SpeakAction   -> action.dedupeKey
            is ProactiveAction.PassiveAction -> action.dedupeKey
            ProactiveAction.NoAction         -> return
        }
        // If a prior verdict is still unresolved at dispatch time, the user
        // didn't engage with it — otherwise the accept branch of
        // resolvePendingVerdict would have already cleared it.  Count it as
        // ignored before overwriting so adaptation isn't lost.
        verdictLock.withLock {
            pendingVerdict?.let { prior ->
                if (prior.dedupeKey != key) {
                    cooldownStore.markIgnored(prior.dedupeKey)
                    Log.d(TAG, "Displaced verdict ignored: ${prior.dedupeKey}")
                    ProactiveMetrics.increment(ProactiveMetrics.Counter.VERDICT_DISPLACED)
                }
            }
            cooldownStore.markSurfaced(key)
            pendingVerdict = PendingVerdict(key, System.currentTimeMillis())
        }

        try {
            dispatcher.dispatch(action)
            // Acknowledge notification events so the same batch isn't re-announced
            val isNotifAction = when (action) {
                is ProactiveAction.SpeakAction   -> action.sourceType == ProactiveEventType.UNREAD_NOTIFICATION
                is ProactiveAction.PassiveAction -> action.sourceType == ProactiveEventType.UNREAD_NOTIFICATION
                ProactiveAction.NoAction         -> false
            }
            if (isNotifAction) notificationSource?.acknowledge()
        } catch (e: Exception) {
            Log.e(TAG, "Dispatcher threw during dispatch — action dropped", e)
        }
    }
}
