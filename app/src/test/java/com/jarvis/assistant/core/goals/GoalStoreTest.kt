package com.jarvis.assistant.core.goals

import com.jarvis.assistant.core.situations.SituationType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalStoreTest {

    private val nowMs = 1_714_000_000_000L

    @Test
    fun `upsert inserts fresh goal when none exists for key`() = runBlocking {
        val dao = FakeGoalDao()
        val store = GoalStore(dao) { nowMs }
        val goal = store.upsert(
            type = GoalType.GET_READY_TO_LEAVE,
            title = "leave",
            rootDedupeKey = "goal:leave:1",
            ttlMs = 60_000L,
            originSituation = SituationType.LEAVING_HOME_SOON,
            evidence = listOf("a", "b"),
        )
        assertEquals(1L, goal.id)
        assertEquals(Goal.STATUS_ACTIVE, goal.status)
        assertEquals(SituationType.LEAVING_HOME_SOON, goal.originSituation)
        assertEquals(listOf("a", "b"), goal.evidence)
        assertEquals(1, store.active.value.size)
    }

    @Test
    fun `upsert merges onto existing row by rootDedupeKey`() = runBlocking {
        val dao = FakeGoalDao()
        val store = GoalStore(dao) { nowMs }
        val first = store.upsert(
            type = GoalType.GET_READY_TO_LEAVE, title = "leave",
            rootDedupeKey = "same", ttlMs = 60_000L,
        )
        val second = store.upsert(
            type = GoalType.GET_READY_TO_LEAVE, title = "still leave",
            rootDedupeKey = "same", ttlMs = 120_000L,
        )
        assertEquals(first.id, second.id)
        assertEquals("still leave", second.title)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `complete moves status away from active`() = runBlocking {
        val dao = FakeGoalDao()
        val store = GoalStore(dao) { nowMs }
        val goal = store.upsert(
            type = GoalType.RETURN_MISSED_CALL, title = "call",
            rootDedupeKey = "x", ttlMs = 60_000L,
        )
        store.complete(goal.id)
        assertEquals(0, store.active.value.size)
        assertEquals(Goal.STATUS_COMPLETED, dao.rows.first().status)
    }

    @Test
    fun `sweep abandons overdue goals`() = runBlocking {
        val dao = FakeGoalDao()
        var clock = nowMs
        val store = GoalStore(dao) { clock }
        store.upsert(
            type = GoalType.HANDLE_COMMUTE, title = "commute",
            rootDedupeKey = "c", ttlMs = 10_000L,
        )
        clock = nowMs + 20_000L
        val moved = store.sweep()
        assertEquals(1, moved)
        assertEquals(Goal.STATUS_ABANDONED, dao.rows.first().status)
        assertTrue(store.active.value.isEmpty())
    }

    @Test
    fun `findActiveOfType returns the open goal`() = runBlocking {
        val dao = FakeGoalDao()
        val store = GoalStore(dao) { nowMs }
        store.upsert(
            type = GoalType.WIND_DOWN_FOR_NIGHT, title = "night",
            rootDedupeKey = "n", ttlMs = 60_000L,
        )
        val g = store.findActiveOfType(GoalType.WIND_DOWN_FOR_NIGHT)
        assertEquals("night", g?.title)
        assertNull(store.findActiveOfType(GoalType.PREPARE_FOR_MEETING))
    }

    @Test
    fun `evidence round-trips through encode-decode`() {
        val encoded = GoalStore.encodeEvidence(listOf("a\u0001b", "c"))
        val decoded = GoalStore.decodeEvidence(encoded)
        assertEquals(listOf("a b", "c"), decoded)
    }

    private class FakeGoalDao : GoalDao {
        val rows = mutableListOf<GoalEntity>()
        private var nextId = 1L

        override suspend fun insert(entity: GoalEntity): Long {
            val e = entity.copy(id = nextId++)
            rows += e
            return e.id
        }

        override suspend fun update(entity: GoalEntity) {
            val idx = rows.indexOfFirst { it.id == entity.id }
            if (idx >= 0) rows[idx] = entity
        }

        override suspend fun findByRootKey(key: String): GoalEntity? =
            rows.firstOrNull { it.rootDedupeKey == key }

        override suspend fun activeAt(nowMs: Long): List<GoalEntity> =
            rows.filter { it.status == Goal.STATUS_ACTIVE && it.expiresAtMs > nowMs }

        override suspend fun overdue(nowMs: Long): List<GoalEntity> =
            rows.filter { it.status == Goal.STATUS_ACTIVE && it.expiresAtMs <= nowMs }

        override suspend fun setStatus(id: Long, status: String, nowMs: Long) {
            val idx = rows.indexOfFirst { it.id == id }
            if (idx >= 0) rows[idx] = rows[idx].copy(status = status, updatedAtMs = nowMs)
        }

        override suspend fun pruneOlderThan(cutoffMs: Long): Int {
            val before = rows.size
            rows.removeAll { it.status != Goal.STATUS_ACTIVE && it.updatedAtMs < cutoffMs }
            return before - rows.size
        }
    }
}
