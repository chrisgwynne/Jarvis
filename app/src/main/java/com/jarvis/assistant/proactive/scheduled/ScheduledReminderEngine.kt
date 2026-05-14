package com.jarvis.assistant.proactive.scheduled

import android.util.Log
import com.jarvis.assistant.proactive.ProactiveEvent
import com.jarvis.assistant.proactive.ProactiveEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone

/**
 * ScheduledReminderEngine — converts upcoming Calendar / Todoist /
 * local-reminder items into [ProactiveEvent]s at fixed offsets before
 * each item's start time.
 *
 * The engine does **NOT** speak.  It hands the event to
 * [com.jarvis.assistant.proactive.ProactiveEngine] which runs its
 * normal scoring + Proactivity gate + dispatcher pipeline.  This means
 * scheduled reminders honour quiet hours, interruption mode, category
 * toggles, and global cooldown for free.
 *
 * Pipeline per tick:
 *   1. Read live [ScheduledReminderSettings] off the SettingsStore.
 *   2. For every enabled source, fetch upcoming items within
 *      [lookAheadMs] (default 24h).
 *   3. Diff against the live [ScheduledReminderInstanceStore] —
 *      cancelled / completed source items are pruned, time-shifted
 *      items get their old instances replaced.
 *   4. Create / update one [ScheduledReminderInstance] per (item,
 *      offset) pair.  Default offsets: 30m + 10m before.
 *   5. For every unfired, undismissed instance whose [scheduledAtMs]
 *      is within ±[fireToleranceMs] of `now`, emit a ProactiveEvent
 *      via [eventSink] and mark fired.
 *
 * The engine is intentionally pure-ish: no Android Context — all
 * Android-flavoured work (CalendarContract, Telephony) lives in the
 * source adapters.  This keeps the engine JVM-testable.
 *
 * @param eventSink          Callback invoked when a reminder is ready
 *                           to fire.  Production wires this to
 *                           [com.jarvis.assistant.proactive.ProactiveEngine.inject].
 * @param sources            Ordered list of item sources.  Empty is
 *                           valid — engine becomes a no-op.
 * @param settingsProvider   Lambda returning the current settings
 *                           snapshot.  Re-evaluated every tick.
 * @param store              Instance store.  Defaults to a fresh
 *                           in-memory one; tests inject pinned stores.
 * @param clock              Wall-clock injection (tests pin this).
 * @param refreshIntervalMs  Tick interval — 5 minutes default.
 * @param lookAheadMs        Item lookup horizon — 24h default.
 * @param fireToleranceMs    A reminder still fires up to this many ms
 *                           late.  Default 2 minutes.
 * @param sourceFetchTimeoutMs  Per-source fetch timeout, default 10s.
 * @param tz                 Time-zone provider for phrase rendering.
 */
class ScheduledReminderEngine(
    private val eventSink: (ProactiveEvent) -> Unit,
    private val sources: List<ScheduledReminderItemSource>,
    private val settingsProvider: () -> ScheduledReminderSettings,
    val store: ScheduledReminderInstanceStore = ScheduledReminderInstanceStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMs: Long = 5 * 60_000L,
    private val lookAheadMs: Long = 24 * 60 * 60_000L,
    private val fireToleranceMs: Long = 2 * 60_000L,
    private val sourceFetchTimeoutMs: Long = 10_000L,
    private val tz: () -> TimeZone = { TimeZone.getDefault() },
) {

    @Volatile private var loopJob: Job? = null

    /**
     * Start the periodic refresh loop on the given [scope].  Idempotent
     * — calling start twice is a no-op.  Use [stop] to cancel.
     */
    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) {
            Log.d(TAG, "start: already running — ignoring")
            return
        }
        loopJob = scope.launch(Dispatchers.Default) {
            Log.d(TAG, "[SCHEDULED_REMINDER_LOOP_START] interval=${refreshIntervalMs}ms")
            // Kick once on startup so the user doesn't wait a full interval.
            runCatching { refresh() }
                .onFailure { Log.w(TAG, "Initial refresh failed", it) }
            while (true) {
                // Tick at the short interval so fire-time checks stay
                // responsive — refresh from sources less often.
                delay(FIRE_TICK_MS)
                runCatching { tickFire() }
                    .onFailure { Log.w(TAG, "Tick fire failed", it) }
                // Full refresh every refreshIntervalMs.
                val now = clock()
                if (now - lastRefreshMs >= refreshIntervalMs) {
                    runCatching { refresh() }
                        .onFailure { Log.w(TAG, "Periodic refresh failed", it) }
                }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        Log.d(TAG, "[SCHEDULED_REMINDER_LOOP_STOP]")
    }

    @Volatile private var lastRefreshMs: Long = 0L

    /**
     * Re-fetch every enabled source and reconcile the instance store.
     * Public so callers (e.g. "task created" hooks) can poke a refresh
     * out-of-band.
     */
    suspend fun refresh() {
        val s = settingsProvider()
        val now = clock()
        Log.d(TAG, "[SCHEDULED_REMINDER_REFRESH_START] sources=${sources.size} " +
            "lookAheadMs=$lookAheadMs")

        for (src in sources) {
            if (!isSourceEnabled(src.source, s)) {
                // When a source is disabled, prune its instances so a
                // re-enable doesn't replay stale reminders.
                store.retainOnly(src.source, emptySet())
                continue
            }
            val items: List<ScheduledReminderItem> = try {
                withTimeoutOrNull(sourceFetchTimeoutMs) {
                    src.fetchUpcoming(now, lookAheadMs)
                } ?: run {
                    Log.w(TAG, "[SCHEDULED_REMINDER_REFRESH_FAILED] " +
                        "source=${src.source} reason=timeout")
                    continue
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[SCHEDULED_REMINDER_REFRESH_FAILED] " +
                    "source=${src.source} reason=${t.message}", t)
                continue
            }
            reconcile(src.source, items, s, now)
        }
        lastRefreshMs = now
        Log.d(TAG, "[SCHEDULED_REMINDER_REFRESH_SUCCESS] tracked=${store.snapshot().size}")
        // After reconciliation immediately check for anything that
        // crossed its scheduled-at boundary during the fetch latency.
        tickFire()
    }

    /**
     * Walk the instance store, fire anything whose scheduledAt has
     * passed (within tolerance) and isn't already fired/dismissed.
     */
    fun tickFire() {
        val now = clock()
        val s = settingsProvider()
        val enabledOffsets = s.offsetsMinutes.toSet()
        for (inst in store.snapshot()) {
            if (inst.fired || inst.dismissed) continue
            if (inst.offsetMinutes !in enabledOffsets) continue
            val delta = now - inst.scheduledAtMs
            // Not yet due
            if (delta < 0) continue
            // Suppress 30m offset entirely if the item itself has already
            // started — the 10m and "already happening" surfaces handle that.
            if (now >= inst.itemTimeMs) {
                Log.d(TAG, "[SCHEDULED_REMINDER_SKIPPED] key=${inst.dedupeKey} " +
                    "reason=item_started")
                store.markFired(inst.dedupeKey)
                continue
            }
            // If it's more than the tolerance late, log + skip rather than
            // dispatching a stale reminder.
            if (delta > fireToleranceMs) {
                Log.d(TAG, "[SCHEDULED_REMINDER_SKIPPED] key=${inst.dedupeKey} " +
                    "reason=late_by_${delta}ms")
                store.markFired(inst.dedupeKey)
                continue
            }
            fire(inst)
        }
    }

    private fun fire(inst: ScheduledReminderInstance) {
        val type = eventTypeFor(inst)
        val phrase = ScheduledReminderPhraseBuilder.build(
            itemTimeMs    = inst.itemTimeMs,
            offsetMinutes = inst.offsetMinutes,
            title         = inst.title,
            location      = inst.location,
            timeZone      = tz(),
        )
        val urgency   = if (inst.offsetMinutes <= 10) 0.85f else 0.70f
        val relevance = 0.90f
        val event = ProactiveEvent(
            type           = type,
            title          = inst.title,
            spokenText     = phrase,
            urgency        = urgency,
            relevance      = relevance,
            confidence     = 1.0f,
            annoyanceCost  = if (inst.offsetMinutes <= 10) 0.10f else 0.25f,
            dedupeKey      = inst.dedupeKey,
            metadata       = mapOf(
                "source"        to inst.source.name,
                "sourceId"      to inst.sourceId,
                "offsetMinutes" to inst.offsetMinutes.toString(),
                "itemTimeMs"    to inst.itemTimeMs.toString(),
            ),
        )
        Log.d(TAG, "[SCHEDULED_REMINDER_FIRED] key=${inst.dedupeKey} type=$type")
        try {
            eventSink(event)
        } catch (t: Throwable) {
            Log.w(TAG, "eventSink threw — instance still marked fired", t)
        }
        store.markFired(inst.dedupeKey)
    }

    private fun reconcile(
        source: ScheduledReminderSource,
        items: List<ScheduledReminderItem>,
        settings: ScheduledReminderSettings,
        now: Long,
    ) {
        val offsets = settings.offsetsMinutes
        if (offsets.isEmpty()) return
        val aliveIds = items.map { it.sourceId }.toSet()
        store.retainOnly(source, aliveIds)
        for (item in items) {
            for (off in offsets) {
                val scheduledAt = item.startMs - off * 60_000L
                // Skip offsets that already passed by more than tolerance.
                if (scheduledAt < now - fireToleranceMs) continue
                val inst = ScheduledReminderInstance(
                    source        = item.source,
                    sourceId      = item.sourceId,
                    title         = item.title,
                    scheduledAtMs = scheduledAt,
                    itemTimeMs    = item.startMs,
                    offsetMinutes = off,
                    fingerprint   = item.fingerprint,
                    location      = item.location,
                    createdAtMs   = now,
                )
                store.putIfChanged(inst)
            }
        }
    }

    private fun isSourceEnabled(
        s: ScheduledReminderSource,
        cfg: ScheduledReminderSettings,
    ): Boolean = when (s) {
        ScheduledReminderSource.CALENDAR -> cfg.calendarEnabled
        ScheduledReminderSource.TODOIST  -> cfg.todoistEnabled
        ScheduledReminderSource.LOCAL    -> cfg.localEnabled
    }

    /** Map (source, offset) → ProactiveEventType. */
    @androidx.annotation.VisibleForTesting
    internal fun eventTypeFor(inst: ScheduledReminderInstance): ProactiveEventType =
        when (inst.source to inst.offsetMinutes) {
            ScheduledReminderSource.CALENDAR to 30 -> ProactiveEventType.CALENDAR_EVENT_30M
            ScheduledReminderSource.CALENDAR to 10 -> ProactiveEventType.CALENDAR_EVENT_10M
            ScheduledReminderSource.TODOIST  to 30 -> ProactiveEventType.TODOIST_TASK_30M
            ScheduledReminderSource.TODOIST  to 10 -> ProactiveEventType.TODOIST_TASK_10M
            ScheduledReminderSource.LOCAL    to 30 -> ProactiveEventType.LOCAL_REMINDER_30M
            ScheduledReminderSource.LOCAL    to 10 -> ProactiveEventType.LOCAL_REMINDER_10M
            // Non-standard offsets fall back to the 30-minute lane.
            else -> when (inst.source) {
                ScheduledReminderSource.CALENDAR -> ProactiveEventType.CALENDAR_EVENT_30M
                ScheduledReminderSource.TODOIST  -> ProactiveEventType.TODOIST_TASK_30M
                ScheduledReminderSource.LOCAL    -> ProactiveEventType.LOCAL_REMINDER_30M
            }
        }

    // Test seam — process exactly one reconciliation + fire pass
    // without spawning the coroutine loop.
    @androidx.annotation.VisibleForTesting
    suspend fun runOnce() { refresh() }

    companion object {
        private const val TAG = "SchedReminderEngine"
        /** Fire-check interval — keeps reminder latency under a minute. */
        private const val FIRE_TICK_MS = 30_000L
    }
}
