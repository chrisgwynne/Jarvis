package com.jarvis.assistant.runtime.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastActionStoreTest {

    @Test
    fun recordAndRetrieveToolCall() {
        val now = 1_000_000L
        val store = LastActionStore(capacity = 8, ttlMs = 60_000L, nowMsProvider = { now })
        val entry = store.recordToolCall(
            toolName = "flashlight",
            argsJson = "{\"mode\":\"on\"}",
            originatingTranscript = "turn on the flashlight",
            shortLabel = "flashlight",
            reversible = true
        )
        assertEquals(entry, store.mostRecent())
        assertEquals(entry, store.mostRecentReversible())
    }

    @Test
    fun ringBufferEvictsOldest() {
        var now = 0L
        val store = LastActionStore(capacity = 2, ttlMs = 60_000L, nowMsProvider = { now })
        now = 10; store.recordToolCall("a", "", "t", "a", true)
        now = 20; store.recordToolCall("b", "", "t", "b", true)
        now = 30; store.recordToolCall("c", "", "t", "c", true)

        val snap = store.snapshot()
        assertEquals(2, snap.size)
        assertEquals("c", (snap[0] as LastAction.ToolCall).toolName)
        assertEquals("b", (snap[1] as LastAction.ToolCall).toolName)
    }

    @Test
    fun expiredEntriesFilteredFromSnapshot() {
        var now = 0L
        val store = LastActionStore(capacity = 8, ttlMs = 1_000L, nowMsProvider = { now })
        now = 100; store.recordToolCall("old", "", "t", "old", false)
        now = 200; store.recordToolCall("fresh", "", "t", "fresh", false)
        now = 1_300 // old (100) is now 1200 ms old, past ttl=1000
        val snap = store.snapshot()
        assertEquals(1, snap.size)
        assertEquals("fresh", (snap[0] as LastAction.ToolCall).toolName)
    }

    @Test
    fun mostRecentReversibleSkipsIrreversible() {
        var now = 0L
        val store = LastActionStore(capacity = 8, ttlMs = 60_000L, nowMsProvider = { now })
        now = 100; store.recordToolCall("a", "", "t", "a", reversible = true)
        now = 200; store.recordToolCall("b", "", "t", "b", reversible = false)

        assertEquals("b", (store.mostRecent() as LastAction.ToolCall).toolName)
        assertEquals("a", (store.mostRecentReversible() as LastAction.ToolCall).toolName)
    }

    @Test
    fun promptFragmentRendersLabels() {
        val store = LastActionStore()
        assertNull(store.toPromptFragment())

        store.recordToolCall("flashlight", "", "turn on flashlight", "flashlight", true)
        store.recordToolCall("sms", "", "text Mike", "text Mike", false)

        val fragment = store.toPromptFragment()
        assertNotNull(fragment)
        assertTrue("Fragment should list most recent first: $fragment", fragment!!.indexOf("text Mike") < fragment.indexOf("flashlight"))
    }
}
