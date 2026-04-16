package com.jarvis.assistant.proactive

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ProactiveEngineTest — pure JVM unit tests for the proactive scoring pipeline.
 *
 * No Android dependencies are used; all source adapters are replaced with
 * [FakeContext] stubs so tests run on the host JVM without an emulator.
 *
 * ## Structure
 * - [FakeContext] — a mutable helper that implements all four source interfaces.
 * - Helper [buildSnapshot] — assembles a [ContextSnapshot] from a [FakeContext].
 * - Helper [scoreAndDecide] — runs the full pipeline from snapshot to action.
 * - 12 test cases covering the requirements specified in the task.
 */
class ProactiveEngineTest {

    // ── Fake context helper ───────────────────────────────────────────────────

    /**
     * FakeContext — a single mutable object that implements all source adapter
     * interfaces so tests can set up any combination of device conditions without
     * needing multiple constructor arguments.
     */
    private data class FakeContext(
        val batteryLevel: Int                        = 100,
        val isCharging: Boolean                      = false,
        val screenOn: Boolean                        = true,
        val isSpeaking: Boolean                      = false,
        val isListening: Boolean                     = false,
        val lastUserInteractionMs: Long?             = null,
        val nextReminderAtMs: Long?                  = null,
        val pendingReminderCount: Int                = 0,
        val missedCallInfo: MissedCallInfo?          = null,
        val locationName: String?                    = null,
        val networkAvailable: Boolean                = true
    ) : BatteryContextSource, ReminderContextSource, CallContextSource, SpeechStateSource {

        override fun getBatteryLevel(): Int = batteryLevel
        override fun isCharging(): Boolean = isCharging
        override fun isScreenOn(): Boolean = screenOn
        override fun isNetworkAvailable(): Boolean = networkAvailable
        override fun getLocationName(): String? = locationName

        override suspend fun getNextPendingReminder(): NextReminderInfo? =
            nextReminderAtMs?.let { NextReminderInfo(triggerAtMillis = it, label = "Test Reminder") }

        override suspend fun getPendingReminderCount(): Int = pendingReminderCount

        override fun getMissedCallInfo(): MissedCallInfo? = missedCallInfo

        override fun getSpeechState(): JarvisSpeechState = JarvisSpeechState(
            isSpeaking            = isSpeaking,
            isListening           = isListening,
            lastUserInteractionMs = lastUserInteractionMs
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val config       = ProactiveConfig()
    private lateinit var cooldownStore: CooldownStore
    private lateinit var generator: EventGenerator
    private lateinit var scorer: EventScorer
    private lateinit var decisionEngine: DecisionEngine

    @Before
    fun setUp() {
        cooldownStore  = CooldownStore()
        generator      = EventGenerator(config)
        scorer         = EventScorer(config, cooldownStore)
        decisionEngine = DecisionEngine(config, cooldownStore)
    }

    private fun buildSnapshot(ctx: FakeContext): ContextSnapshot {
        val speechState = ctx.getSpeechState()
        val missedCall  = ctx.getMissedCallInfo()
        return ContextSnapshot(
            currentTimeMillis             = System.currentTimeMillis(),
            batteryLevel                  = ctx.getBatteryLevel(),
            isCharging                    = ctx.isCharging(),
            screenOn                      = ctx.isScreenOn(),
            isJarvisSpeaking              = speechState.isSpeaking,
            isJarvisListening             = speechState.isListening,
            lastUserInteractionTimeMillis = speechState.lastUserInteractionMs,
            activeReminderCount           = ctx.pendingReminderCount,
            nextReminderAtMillis          = ctx.nextReminderAtMs,
            missedCallsCount              = missedCall?.count ?: 0,
            lastMissedCallAtMillis        = missedCall?.lastCallAtMillis,
            lastMissedCallContactName     = missedCall?.contactName,
            currentLocationName           = ctx.locationName,
            networkAvailable              = ctx.networkAvailable
        )
    }

    private fun scoreAndDecide(ctx: FakeContext): ProactiveAction {
        val snapshot = buildSnapshot(ctx)
        val events   = generator.generate(snapshot)
        val scored   = scorer.scoreAll(events, snapshot)
        return decisionEngine.decide(scored, snapshot)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Test 1 — Critical battery (3%) with no charger should produce a SpeakAction.
     *
     * At battery=3 the urgency is 0.95, relevance=0.80, confidence=1.0, annoyanceCost=0.25.
     * raw = (0.95+0.80+1.0-0.25)/3 = 2.50/3 ≈ 0.833.
     * No penalties in a fresh context → finalScore ≈ 0.833 > activeThreshold(0.80).
     */
    @Test
    fun testLowBatteryGeneratesActiveWhenCritical() {
        val ctx = FakeContext(batteryLevel = 3, isCharging = false)
        val action = scoreAndDecide(ctx)
        assertTrue(
            "Expected SpeakAction for critical battery, got $action",
            action is ProactiveAction.SpeakAction
        )
        assertEquals(ProactiveEventType.LOW_BATTERY, (action as ProactiveAction.SpeakAction).sourceType)
    }

    /**
     * Test 2 — Critical battery but charging → generator returns null → NoAction.
     */
    @Test
    fun testLowBatteryGeneratesNoneWhenCharging() {
        val ctx = FakeContext(batteryLevel = 3, isCharging = true)
        val action = scoreAndDecide(ctx)
        assertEquals(
            "Expected NoAction when battery is low but charging",
            ProactiveAction.NoAction,
            action
        )
    }

    /**
     * Test 3 — Cooldown: surface a critical-battery alert once, then immediately
     * call again.  The second tick should return NoAction because the dedupeKey
     * is on cooldown (repetition penalty reduces score below passiveThreshold)
     * OR the global gap is not yet satisfied.
     */
    @Test
    fun testLowBatteryGeneratesNoneWhenCooldownActive() {
        val ctx = FakeContext(batteryLevel = 3, isCharging = false)
        val snapshot = buildSnapshot(ctx)
        val events = generator.generate(snapshot)

        // First pass: surface the event
        val firstScored = scorer.scoreAll(events, snapshot)
        val firstAction = decisionEngine.decide(firstScored, snapshot)
        assertTrue("First action should be SpeakAction", firstAction is ProactiveAction.SpeakAction)

        // Record the surfacing in the cooldown store (as ProactiveEngine would do)
        cooldownStore.markSurfaced((firstAction as ProactiveAction.SpeakAction).dedupeKey)

        // Second pass immediately after: global gap + cooldown penalty should suppress
        val secondAction = scoreAndDecide(ctx)
        assertEquals(
            "Second immediate tick should be NoAction due to global gap",
            ProactiveAction.NoAction,
            secondAction
        )
    }

    /**
     * Test 4 — Reminder 45 seconds away → urgency=0.90 → ACTIVE level → SpeakAction.
     *
     * raw ≈ (0.90+0.95+1.0-0.20)/3 ≈ 2.65/3 ≈ 0.883 > activeThreshold(0.80).
     */
    @Test
    fun testUpcomingReminderActiveWhenUrgent() = runTest {
        val soonMs = System.currentTimeMillis() + 45_000L  // 45 seconds
        val ctx = FakeContext(
            nextReminderAtMs     = soonMs,
            pendingReminderCount = 1
        )
        val action = scoreAndDecide(ctx)
        assertTrue(
            "Expected SpeakAction for reminder in 45s, got $action",
            action is ProactiveAction.SpeakAction
        )
        assertEquals(
            ProactiveEventType.UPCOMING_REMINDER,
            (action as ProactiveAction.SpeakAction).sourceType
        )
    }

    /**
     * Test 5 — Reminder 9 minutes away → urgency=0.50, relevance=0.60.
     * raw ≈ (0.50+0.60+1.0-0.20)/3 ≈ 1.90/3 ≈ 0.633 → PASSIVE (between 0.55 and 0.80).
     * Expected: PassiveAction.
     */
    @Test
    fun testUpcomingReminderPassiveAt10Min() = runTest {
        val nineMinutesMs = System.currentTimeMillis() + 9 * 60_000L
        val ctx = FakeContext(
            nextReminderAtMs     = nineMinutesMs,
            pendingReminderCount = 1
        )
        val action = scoreAndDecide(ctx)
        assertTrue(
            "Expected PassiveAction for reminder at ~9min, got $action",
            action is ProactiveAction.PassiveAction
        )
        assertEquals(
            ProactiveEventType.UPCOMING_REMINDER,
            (action as ProactiveAction.PassiveAction).sourceType
        )
    }

    /**
     * Test 6 — Reminder surfaced once; immediate re-tick should be NoAction.
     */
    @Test
    fun testUpcomingReminderCooldown() = runTest {
        val urgentMs = System.currentTimeMillis() + 45_000L
        val ctx = FakeContext(nextReminderAtMs = urgentMs, pendingReminderCount = 1)

        val firstAction = scoreAndDecide(ctx)
        assertTrue("First action should be SpeakAction", firstAction is ProactiveAction.SpeakAction)
        cooldownStore.markSurfaced((firstAction as ProactiveAction.SpeakAction).dedupeKey)

        val secondAction = scoreAndDecide(ctx)
        assertEquals(
            "Second tick should be suppressed by cooldown/global gap",
            ProactiveAction.NoAction,
            secondAction
        )
    }

    /**
     * Test 7 — Missed call 2 minutes ago → relevance=0.90 (< 5 min).
     * raw ≈ (0.65+0.90+1.0-0.35)/3 = 2.20/3 ≈ 0.733 → PASSIVE or above.
     * Check that finalScore > passiveThreshold (0.55).
     */
    @Test
    fun testMissedCallHighRelevanceWhenRecent() {
        val recentCallMs = System.currentTimeMillis() - 2 * 60_000L  // 2 min ago
        val ctx = FakeContext(
            missedCallInfo = MissedCallInfo(
                count            = 1,
                lastCallAtMillis = recentCallMs,
                contactName      = "Alice"
            )
        )
        val snapshot = buildSnapshot(ctx)
        val events   = generator.generate(snapshot)
        assertTrue("Should generate at least one event", events.isNotEmpty())
        val scored   = scorer.scoreAll(events, snapshot)
        val missedCallScore = scored.first { it.event.type == ProactiveEventType.MISSED_CALL }

        assertTrue(
            "Recent missed call finalScore (${missedCallScore.finalScore}) " +
            "should exceed passiveThreshold (${config.passiveThreshold})",
            missedCallScore.finalScore > config.passiveThreshold
        )
    }

    /**
     * Test 8 — Missed call 2 hours ago → relevance=0.35 (older than 30 min).
     * raw ≈ (0.65+0.35+1.0-0.35)/3 = 1.65/3 = 0.55.
     * With no other penalties finalScore ≈ 0.55 which equals passiveThreshold — we
     * verify it is strictly below ACTIVE (0.80) and the action is NOT a SpeakAction,
     * capturing the "stale relevance" intent.
     */
    @Test
    fun testMissedCallLowRelevanceWhenOld() {
        val oldCallMs = System.currentTimeMillis() - 2 * 60 * 60_000L  // 2 hours ago
        val ctx = FakeContext(
            missedCallInfo = MissedCallInfo(
                count            = 1,
                lastCallAtMillis = oldCallMs,
                contactName      = null
            )
        )
        val snapshot = buildSnapshot(ctx)
        val events   = generator.generate(snapshot)
        assertTrue("Should generate at least one event", events.isNotEmpty())
        val scored   = scorer.scoreAll(events, snapshot)
        val missedCallScore = scored.first { it.event.type == ProactiveEventType.MISSED_CALL }

        assertTrue(
            "Old missed call finalScore (${missedCallScore.finalScore}) " +
            "should be below activeThreshold (${config.activeThreshold})",
            missedCallScore.finalScore < config.activeThreshold
        )
    }

    /**
     * Test 9 — All-clear context (full battery, charging, no reminders, no missed calls).
     * Expect NoAction.
     */
    @Test
    fun testNoActionWhenNothingTriggered() {
        val ctx = FakeContext(
            batteryLevel     = 80,
            isCharging       = true,
            nextReminderAtMs = null,
            missedCallInfo   = null
        )
        val action = scoreAndDecide(ctx)
        assertEquals("Should be NoAction when all clear", ProactiveAction.NoAction, action)
    }

    /**
     * Test 10 — Global gap enforcement.
     *
     * Surface a high-urgency action, then immediately present another high-urgency
     * situation.  The second should be blocked by the global gap.
     */
    @Test
    fun testGlobalGapEnforcedBetweenActions() {
        // First tick: critical battery
        val ctx1 = FakeContext(batteryLevel = 3, isCharging = false)
        val first = scoreAndDecide(ctx1)
        assertTrue("First action should be SpeakAction", first is ProactiveAction.SpeakAction)
        cooldownStore.markSurfaced((first as ProactiveAction.SpeakAction).dedupeKey)

        // Second tick immediately: urgent reminder
        val urgentReminderMs = System.currentTimeMillis() + 30_000L
        val ctx2 = FakeContext(
            batteryLevel     = 3,
            isCharging       = false,
            nextReminderAtMs = urgentReminderMs,
            pendingReminderCount = 1
        )
        val second = scoreAndDecide(ctx2)

        // Global gap (60s) is not satisfied → NoAction
        assertEquals(
            "Second action should be NoAction due to global gap",
            ProactiveAction.NoAction,
            second
        )
    }

    /**
     * Test 11 — Recent interaction penalty.
     *
     * Battery is critical but the user spoke 10 seconds ago.  The
     * recentInteractionPenalty (0.25) is applied, reducing the finalScore.
     * The score should be lower than the unpenalised baseline.
     */
    @Test
    fun testRecentInteractionPenaltyApplied() {
        val tenSecondsAgo = System.currentTimeMillis() - 10_000L

        // Score with recent interaction
        val ctxWithInteraction = FakeContext(
            batteryLevel           = 3,
            isCharging             = false,
            lastUserInteractionMs  = tenSecondsAgo
        )
        val snapshotWith = buildSnapshot(ctxWithInteraction)
        val eventsA      = generator.generate(snapshotWith)
        val scoredWith   = scorer.scoreAll(eventsA, snapshotWith)
        val scoreWith    = scoredWith.first { it.event.type == ProactiveEventType.LOW_BATTERY }.finalScore

        // Reset cooldown store to isolate the penalty comparison
        cooldownStore.reset()

        // Score without recent interaction
        val ctxWithout = FakeContext(batteryLevel = 3, isCharging = false)
        val snapshotWithout = buildSnapshot(ctxWithout)
        val eventsB         = generator.generate(snapshotWithout)
        val scoredWithout   = scorer.scoreAll(eventsB, snapshotWithout)
        val scoreWithout    = scoredWithout.first { it.event.type == ProactiveEventType.LOW_BATTERY }.finalScore

        assertTrue(
            "Recent interaction should reduce score: withInteraction=$scoreWith, without=$scoreWithout",
            scoreWith < scoreWithout
        )
    }

    /**
     * Test 12 — buildDailyBrief groups events into correct buckets.
     *
     * Setup: critical battery (urgency 0.95 → NOW) + reminder in 9 min (urgency 0.50 → SOON).
     * Verify that NOW contains the battery event and SOON contains the reminder event.
     */
    @Test
    fun testBuildDailyBriefGroupsCorrectly() {
        val nineMinutesMs = System.currentTimeMillis() + 9 * 60_000L
        val ctx = FakeContext(
            batteryLevel         = 3,
            isCharging           = false,
            nextReminderAtMs     = nineMinutesMs,
            pendingReminderCount = 1
        )
        val snapshot = buildSnapshot(ctx)
        val brief    = generator.buildDailyBrief(snapshot)

        val nowBucket  = brief[DailyBriefBucket.NOW]  ?: emptyList()
        val soonBucket = brief[DailyBriefBucket.SOON] ?: emptyList()
        val infoBucket = brief[DailyBriefBucket.INFO] ?: emptyList()

        assertTrue(
            "NOW bucket should contain the LOW_BATTERY event",
            nowBucket.any { it.type == ProactiveEventType.LOW_BATTERY }
        )
        assertTrue(
            "SOON bucket should contain the UPCOMING_REMINDER event",
            soonBucket.any { it.type == ProactiveEventType.UPCOMING_REMINDER }
        )
        assertTrue(
            "INFO bucket should be empty for this scenario",
            infoBucket.isEmpty()
        )

        // Verify DailyBriefBucket.NOW events all have urgency >= 0.8
        for (event in nowBucket) {
            assertTrue(
                "NOW bucket event ${event.type} should have urgency >= 0.8, got ${event.urgency}",
                event.urgency >= 0.8f
            )
        }

        // Verify DailyBriefBucket.SOON events all have urgency in [0.5, 0.8)
        for (event in soonBucket) {
            assertTrue(
                "SOON bucket event ${event.type} should have urgency in [0.5, 0.8), got ${event.urgency}",
                event.urgency >= 0.5f && event.urgency < 0.8f
            )
        }
    }
}
