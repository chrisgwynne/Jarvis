package com.jarvis.assistant.followup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentFactCarrierTest {

    @Test
    fun `peek returns the most recent fact within TTL`() {
        var now = 1_000L
        val c = RecentFactCarrier(ttlMs = 60_000L, clock = { now })
        c.remember("location", "You're on High Street in Wrexham.")
        now += 1_000
        val f = c.peek()
        assertNotNull(f)
        assertEquals("location", f!!.topic)
        assertEquals("You're on High Street in Wrexham.", f.spoken)
    }

    @Test
    fun `expired entry returns null and is cleared`() {
        var now = 0L
        val c = RecentFactCarrier(ttlMs = 60_000L, clock = { now })
        c.remember("location", "On High Street.")
        now = 61_000L
        assertNull(c.peek())
        // re-peek doesn't suddenly resurrect it
        assertNull(c.peek())
    }

    @Test
    fun `last-writer-wins for a single slot`() {
        val c = RecentFactCarrier()
        c.remember("location", "On High Street.")
        c.remember("weather",  "It's raining.")
        val f = c.peek()!!
        assertEquals("weather", f.topic)
    }

    @Test
    fun `clear empties the slot`() {
        val c = RecentFactCarrier()
        c.remember("location", "On High Street.")
        c.clear()
        assertNull(c.peek())
    }

    @Test
    fun `blank inputs are ignored`() {
        val c = RecentFactCarrier()
        c.remember("", "")
        c.remember("location", "  ")
        c.remember("  ", "On High Street.")
        assertNull(c.peek())
    }

    @Test
    fun `toPromptFragment cites the topic and the spoken line`() {
        val c = RecentFactCarrier()
        c.remember("location", "You're on High Street in Wrexham.")
        val frag = c.toPromptFragment()
        assertTrue(frag.contains("location"))
        assertTrue(frag.contains("High Street"))
    }

    @Test
    fun `empty fragment when no fact present`() {
        val c = RecentFactCarrier()
        assertEquals("", c.toPromptFragment())
    }
}
