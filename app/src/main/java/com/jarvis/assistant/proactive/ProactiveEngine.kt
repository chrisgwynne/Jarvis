package com.jarvis.assistant.proactive

import android.util.Log
import com.jarvis.assistant.proactive.db.ProactiveCooldownDao
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
    private val calendarSource: CalendarContextSource? = null,
    private val locationSource: LocationContextSource? = null,
    /**
     * Supplies the current driving state on each tick.  Optional to keep
     * tests and legacy callers simple — defaults to "not driving" when not
     * wired up.  JarvisRuntime passes `drivingModeManager::isDriving`.
     */
    private val isDrivingProvider: () -> Boolean = { false },
    /**
     * When non-null, cooldowns and ignore counts persist across process
     * restarts.  JarvisRuntime supplies the real DAO; tests leave this null
     * for a pure in-memory store.
     */
    private val cooldownDao: ProactiveCooldownDao? = null,
    /**
     * Optional shared cooldown store. When provided the engine uses it for
     * scoring + decision gating; the ledger passed alongside must wrap the
     * same instance. When null, a fresh store is constructed from [cooldownDao].
     */
    sharedCooldownStore: CooldownStore? = null,
    /**
     * Optional shared action ledger. When provided the engine uses it for
     * markSurfaced / markIgnored / markAccepted so reactive tool calls and
     * proactive dispatches share one cooldown view. When null, a fresh
     * ledger is created around [cooldownStore] with no cross-path sharing.
     */
    actionLedger: com.jarvis.assistant.core.decisions.ActionLedger? = null,
    /**
     * Optional trace store. When provided every tick appends one row
     * describing candidates, gate outcome, and dispatched action. Fire-
     * and-forget: never blocks the tick.
     */
    private val traceStore: com.jarvis.assistant.core.telemetry.DecisionTraceStore? = null,
    /**
     * Optional recent-event buffer. When supplied, each tick hands its
     * snapshot to the trigger engine so composite triggers can reason
     * about cross-stream history (e.g. "SSID changed 30s ago").
     */
    private val recentEventBuffer: com.jarvis.assistant.core.events.RecentEventBuffer? = null,
    /**
     * Optional known-SSID store so [UnfamiliarSsidTrigger] can be
     * registered in the trigger engine. Null = trigger skipped.
     */
    knownSsidStore: com.jarvis.assistant.core.learning.KnownSsidStore? = null,
    /** Optional routine-pattern detector whose proposals are surfaced via RoutineProposalTrigger. */
    routineSynthesizer: com.jarvis.assistant.core.routines.RoutineSynthesizer? = null,
) {

    companion object {
        private const val TAG = "ProactiveEngine"
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private val cooldownStore   = sharedCooldownStore ?: CooldownStore(dao = cooldownDao)
    val ledger: com.jarvis.assistant.core.decisions.ActionLedger =
        actionLedger ?: com.jarvis.assistant.core.decisions.ActionLedger(cooldownStore)
    private val eventGenerator  = EventGenerator(
        config = config,
        triggerEngine = com.jarvis.assistant.core.decisions.triggers.DefaultTriggers.engine(config, knownSsidStore, routineSynthesizer),
    )
    private val eventScorer     = EventScorer(config, cooldownStore, ledger)
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
    private data class PendingVerdict(
        val dedupeKey: String,
        val dispatchedAt: Long,
        val actionClass: String?,
    )

    /**
     * Candidate held for later delivery after the presence gate suppressed
     * it but nothing else would have. Re-tried on subsequent ticks; dropped
     * after [presenceDeferralTtlMs] so stale queued items never surface.
     */
    private data class DeferredCandidate(
        val event: ProactiveEvent,
        val queuedAt: Long,
    )
    private val deferredQueue = ArrayDeque<DeferredCandidate>()
    private val deferredLock = Any()
    private val presenceDeferralTtlMs: Long = 10 * 60 * 1000L

    /**
     * Last snapshot built by [tick], exposed so [com.jarvis.assistant.core
     * .context.AgentContextProvider] and other consumers can read the
     * most recent proactive view without rebuilding it themselves.
     * Null until the first tick completes.
     */
    @Volatile var lastSnapshot: ContextSnapshot? = null
        private set
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
        lastSnapshot = snapshot

        // Resolve any pending ignore/accept verdict from a previous dispatch.
        resolvePendingVerdict(snapshot)

        val recent = recentEventBuffer?.snapshot(maxAgeMs = 5 * 60_000L) ?: emptyList()
        val generated = eventGenerator.generate(snapshot, recent)
        val deferred = drainDeferred(snapshot.currentTimeMillis)
        val events = if (deferred.isEmpty()) generated else generated + deferred
        ProactiveMetrics.increment(ProactiveMetrics.Counter.EVENTS_GENERATED, events.size.toLong())

        if (events.isEmpty()) {
            Log.v(TAG, "tick: no events generated")
            traceStore?.record(outcome = "no_events")
            return
        }

        val scored    = eventScorer.scoreAll(events, snapshot)
        val action    = decisionEngine.decide(scored, snapshot)
        decisionEngine.lastDeferredByPresence?.let { enqueueDeferred(it.event) }

        val outcome = when (action) {
            is ProactiveAction.SpeakAction -> "speak"
            is ProactiveAction.PassiveAction -> "passive"
            is ProactiveAction.NoAction -> "suppressed:${action.reason.name.lowercase()}"
        }
        val dispatchedKey = when (action) {
            is ProactiveAction.SpeakAction -> action.dedupeKey
            is ProactiveAction.PassiveAction -> action.dedupeKey
            is ProactiveAction.NoAction -> null
        }
        traceStore?.record(
            outcome = outcome,
            dispatchedDedupeKey = dispatchedKey,
            snapshotJson = snapshotSummary(snapshot),
            candidatesJson = events.joinToString(prefix = "[", postfix = "]") { candidateSummary(it) },
        )

        if (action !is ProactiveAction.NoAction) {
            ProactiveMetrics.increment(ProactiveMetrics.Counter.ACTIONS_DISPATCHED)
            dispatch(action)
        } else {
            Log.v(TAG, "Suppressed: ${action.reason}")
        }
    }

    /**
     * Pull every deferred candidate whose queued age is still within TTL.
     * Drops expired entries. Called at the top of each tick so the fresh
     * [EventGenerator] output is merged with re-attempted deferrals.
     */
    private fun drainDeferred(now: Long): List<ProactiveEvent> = synchronized(deferredLock) {
        val cutoff = now - presenceDeferralTtlMs
        while (deferredQueue.isNotEmpty() && deferredQueue.peekFirst().queuedAt < cutoff) {
            deferredQueue.removeFirst()
        }
        if (deferredQueue.isEmpty()) emptyList() else deferredQueue.map { it.event }.also { deferredQueue.clear() }
    }

    private fun enqueueDeferred(event: ProactiveEvent) = synchronized(deferredLock) {
        if (deferredQueue.any { it.event.dedupeKey == event.dedupeKey }) return@synchronized
        deferredQueue.addLast(DeferredCandidate(event, System.currentTimeMillis()))
        while (deferredQueue.size > 8) deferredQueue.removeFirst()
    }

    private fun snapshotSummary(s: ContextSnapshot): String = buildString {
        append('{')
        append("\"battery\":").append(s.batteryLevel).append(',')
        append("\"charging\":").append(s.isCharging).append(',')
        append("\"driving\":").append(s.isDriving).append(',')
        append("\"speaking\":").append(s.isJarvisSpeaking).append(',')
        append("\"listening\":").append(s.isJarvisListening).append(',')
        append("\"unread\":").append(s.unreadNotificationCount).append(',')
        append("\"missed\":").append(s.missedCallsCount).append(',')
        append("\"meetings_today\":").append(s.meetingsTodayCount)
        append('}')
    }

    private fun candidateSummary(e: ProactiveEvent): String = buildString {
        append("{\"type\":\"").append(e.type.name).append("\",")
        append("\"urgency\":").append(e.urgency).append(',')
        append("\"relevance\":").append(e.relevance).append(',')
        append("\"dedupe\":\"").append(e.dedupeKey).append("\"}")
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
            ledger.recordVerdict(verdict.dedupeKey, accepted = true, actionClass = verdict.actionClass)
            Log.d(TAG, "Accepted verdict for ${verdict.dedupeKey}")
            ProactiveMetrics.increment(ProactiveMetrics.Counter.VERDICT_ACCEPTED)
            pendingVerdict = null
            return@withLock
        }
        val age = snapshot.currentTimeMillis - verdict.dispatchedAt
        if (age >= config.ignoreCheckDelayMs) {
            ledger.recordVerdict(verdict.dedupeKey, accepted = false, actionClass = verdict.actionClass)
            Log.d(
                TAG,
                "Ignored verdict for ${verdict.dedupeKey} " +
                "(count=${ledger.ignoreCount(verdict.dedupeKey)})"
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
        val upcomingMeetings = calendarSource?.getUpcomingMeetings(config.meetingWindowMs).orEmpty()
        val meetingsToday    = calendarSource?.getMeetingsRemainingToday() ?: 0
        val nextMeeting      = upcomingMeetings.firstOrNull()
        val pendingTransition = locationSource?.getPendingTransition()

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
            isDriving                     = isDrivingProvider(),
            nextMeetingAtMillis           = nextMeeting?.startMs,
            nextMeetingTitle              = nextMeeting?.title,
            nextMeetingEndMillis          = nextMeeting?.endMs,
            meetingsTodayCount            = meetingsToday,
            lastLocationTransition        = pendingTransition
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
            is ProactiveAction.NoAction      -> return
        }
        // If a prior verdict is still unresolved at dispatch time, the user
        // didn't engage with it — otherwise the accept branch of
        // resolvePendingVerdict would have already cleared it.  Count it as
        // ignored before overwriting so adaptation isn't lost.
        val actionClass = when (action) {
            is ProactiveAction.SpeakAction -> action.sourceType.actionClassKey()
            is ProactiveAction.PassiveAction -> action.sourceType.actionClassKey()
            is ProactiveAction.NoAction -> null
        }
        verdictLock.withLock {
            pendingVerdict?.let { prior ->
                if (prior.dedupeKey != key) {
                    ledger.recordVerdict(prior.dedupeKey, accepted = false, actionClass = prior.actionClass)
                    Log.d(TAG, "Displaced verdict ignored: ${prior.dedupeKey}")
                    ProactiveMetrics.increment(ProactiveMetrics.Counter.VERDICT_DISPLACED)
                }
            }
            ledger.recordProactiveDispatch(key, actionClass)
            pendingVerdict = PendingVerdict(key, System.currentTimeMillis(), actionClass)
        }

        try {
            dispatcher.dispatch(action)
            // Acknowledge notification events so the same batch isn't re-announced
            val sourceType = when (action) {
                is ProactiveAction.SpeakAction   -> action.sourceType
                is ProactiveAction.PassiveAction -> action.sourceType
                is ProactiveAction.NoAction      -> null
            }
            if (sourceType == ProactiveEventType.UNREAD_NOTIFICATION) notificationSource?.acknowledge()
            if (sourceType == ProactiveEventType.ARRIVED_HOME ||
                sourceType == ProactiveEventType.LEFT_HOME ||
                sourceType == ProactiveEventType.ARRIVED_KNOWN_PLACE) {
                locationSource?.acknowledge()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dispatcher threw during dispatch — action dropped", e)
        }
    }
}
