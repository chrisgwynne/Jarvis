package com.jarvis.assistant.todoist.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecentTaskContextStoreTest {

    @Test
    fun `peek returns most recent within TTL`() {
        var now = 1_000L
        val s = RecentTaskContextStore(ttlMs = 60_000L, clock = { now })
        s.remember("t1", "buy milk", RecentTaskContextStore.Source.CREATED)
        now += 5_000L
        val e = s.peek()
        assertNotNull(e)
        assertEquals("t1", e!!.taskId)
        assertEquals(RecentTaskContextStore.Source.CREATED, e.source)
    }

    @Test
    fun `expired entry returns null and clears`() {
        var now = 0L
        val s = RecentTaskContextStore(ttlMs = 60_000L, clock = { now })
        s.remember("t1", "x", RecentTaskContextStore.Source.CREATED)
        now = 61_000L
        assertNull(s.peek())
        // Slot is cleared — a second peek won't bring it back.
        assertNull(s.peek())
    }

    @Test
    fun `last-writer wins`() {
        val s = RecentTaskContextStore()
        s.remember("a", "alpha", RecentTaskContextStore.Source.CREATED)
        s.remember("b", "beta",  RecentTaskContextStore.Source.MODIFIED)
        val e = s.peek()!!
        assertEquals("b", e.taskId)
        assertEquals(RecentTaskContextStore.Source.MODIFIED, e.source)
    }

    @Test
    fun `clear empties the slot`() {
        val s = RecentTaskContextStore()
        s.remember("a", "alpha", RecentTaskContextStore.Source.CREATED)
        s.clear()
        assertNull(s.peek())
    }

    @Test
    fun `blank taskId is ignored`() {
        val s = RecentTaskContextStore()
        s.remember("", "x", RecentTaskContextStore.Source.CREATED)
        assertNull(s.peek())
    }
}
