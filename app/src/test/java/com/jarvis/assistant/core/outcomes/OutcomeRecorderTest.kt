package com.jarvis.assistant.core.outcomes

import com.jarvis.assistant.core.decisions.ActionLedger
import com.jarvis.assistant.proactive.CooldownStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeRecorderTest {

    private val nowMs = 1_714_000_000_000L

    @Test
    fun `ignored outcome marks ledger ignored and persists row`() = runBlocking {
        val dao = FakeOutcomeDao()
        val ledger = ActionLedger(CooldownStore(), nowMs = { nowMs })
        val recorder = OutcomeRecorder(dao, ledger) { nowMs }

        recorder.resolveProactiveVerdict(
            dedupeKey = "low_battery:once",
            actionClass = "BATTERY",
            accepted = false,
        )

        assertEquals(1, dao.rows.size)
        assertEquals(OutcomeType.IGNORED.name, dao.rows.first().type)
        assertTrue(ledger.ignoreCount("low_battery:once") > 0)
    }

    @Test
    fun `accepted outcome marks ledger accepted`() = runBlocking {
        val dao = FakeOutcomeDao()
        val ledger = ActionLedger(CooldownStore(), nowMs = { nowMs })
        val recorder = OutcomeRecorder(dao, ledger) { nowMs }

        recorder.resolveProactiveVerdict(
            dedupeKey = "meeting:10",
            actionClass = "CALENDAR",
            accepted = true,
        )

        // After 4 accepts, acceptRate should tip above 0.5 prior.
        repeat(3) {
            recorder.resolveProactiveVerdict(
                dedupeKey = "meeting:${it + 11}",
                actionClass = "CALENDAR",
                accepted = true,
            )
        }
        assertTrue(ledger.acceptRate("CALENDAR") > 0.9f)
    }

    @Test
    fun `plan outcomes are recorded with plan id and goal id`() = runBlocking {
        val dao = FakeOutcomeDao()
        val ledger = ActionLedger(CooldownStore(), nowMs = { nowMs })
        val recorder = OutcomeRecorder(dao, ledger) { nowMs }

        recorder.recordPlanOutcome(planId = "p1", goalId = 42L, completed = true)

        val row = dao.rows.last()
        assertEquals(OutcomeType.PLAN_COMPLETED.name, row.type)
        assertEquals("p1", row.planId)
        assertEquals(42L, row.goalId)
    }

    @Test
    fun `countOfType filters by class and type`() = runBlocking {
        val dao = FakeOutcomeDao()
        val ledger = ActionLedger(CooldownStore(), nowMs = { nowMs })
        val recorder = OutcomeRecorder(dao, ledger) { nowMs }

        recorder.recordUserCorrection(actionClass = "NOTIFICATION", dedupeKey = "n1", detail = "undo")
        recorder.recordUserCorrection(actionClass = "NOTIFICATION", dedupeKey = "n2", detail = "no")
        recorder.recordUserCorrection(actionClass = "CALL", dedupeKey = "c1", detail = "no")

        assertEquals(2, recorder.countRecent("NOTIFICATION", OutcomeType.USER_CORRECTED))
        assertEquals(1, recorder.countRecent("CALL", OutcomeType.USER_CORRECTED))
    }

    private class FakeOutcomeDao : OutcomeDao {
        val rows = mutableListOf<OutcomeEntity>()
        private var nextId = 1L

        override suspend fun insert(entity: OutcomeEntity): Long {
            val e = entity.copy(id = nextId++)
            rows += e
            return e.id
        }

        override suspend fun recent(sinceMs: Long, limit: Int): List<OutcomeEntity> =
            rows.filter { it.occurredAtMs >= sinceMs }.takeLast(limit)

        override suspend fun recentForClass(actionClass: String, sinceMs: Long): List<OutcomeEntity> =
            rows.filter { it.actionClass == actionClass && it.occurredAtMs >= sinceMs }

        override suspend fun forGoal(goalId: Long): List<OutcomeEntity> =
            rows.filter { it.goalId == goalId }

        override suspend fun countOfType(actionClass: String, type: String, sinceMs: Long): Int =
            rows.count { it.actionClass == actionClass && it.type == type && it.occurredAtMs >= sinceMs }

        override suspend fun pruneOlderThan(cutoffMs: Long): Int {
            val before = rows.size
            rows.removeAll { it.occurredAtMs < cutoffMs }
            return before - rows.size
        }
    }
}
