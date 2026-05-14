package com.jarvis.assistant.personality.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentPhraseStoreTest {

    @Test fun `wasRecent reports recent records`() {
        val store = RecentPhraseStore(clock = { 1_000L })
        store.record("flashlight", "Torch on.")
        assertTrue(store.wasRecent("flashlight", "Torch on."))
        assertFalse(store.wasRecent("flashlight", "Torch off."))
    }

    @Test fun `entries expire after TTL`() {
        var now = 0L
        val store = RecentPhraseStore(ttlMs = 1_000L, clock = { now })
        store.record("cat", "phrase")
        now = 999L
        assertTrue(store.wasRecent("cat", "phrase"))
        now = 5_000L
        assertFalse(store.wasRecent("cat", "phrase"))
    }

    @Test fun `capacity ring keeps the latest entries`() {
        val store = RecentPhraseStore(capacity = 2, clock = { 1_000L })
        store.record("c", "a")
        store.record("c", "b")
        store.record("c", "d")
        assertFalse("oldest entry should have been evicted",
            store.wasRecent("c", "a"))
        assertTrue(store.wasRecent("c", "b"))
        assertTrue(store.wasRecent("c", "d"))
        assertEquals(2, store.recentCount("c"))
    }
}
