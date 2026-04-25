package com.jarvis.assistant.proactive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [CooldownStore] invariants that other components rely
 * on. Kept narrow on purpose — the store is only meaningful as the source of
 * truth for dedupe-key state; we only pin the invariants downstream code
 * actually depends on.
 */
class CooldownStoreTest {

    @Test
    fun `markIgnored before markSurfaced does not accumulate stale ignore count`() {
        // Regression: markIgnored used to bump the in-memory counter before
        // checking whether the key had ever been surfaced. A later markSurfaced
        // would then persist that stale count with a fresh timestamp — so a
        // brand-new dispatch would inherit the ignore history of whatever
        // earlier (orphaned) verdict happened to use the same dedupe key.
        val store = CooldownStore(dao = null)

        store.markIgnored("orphan_key")
        store.markIgnored("orphan_key")

        assertEquals(
            "ignore count must not accumulate for a never-surfaced key",
            0,
            store.ignoreCount("orphan_key"),
        )

        store.markSurfaced("orphan_key")
        assertEquals(
            "fresh surface must start with a clean ignore count",
            0,
            store.ignoreCount("orphan_key"),
        )
    }

    @Test
    fun `markIgnored after markSurfaced increments count`() {
        val store = CooldownStore(dao = null)
        store.markSurfaced("real_key")
        store.markIgnored("real_key")
        store.markIgnored("real_key")
        assertEquals(2, store.ignoreCount("real_key"))
    }

    @Test
    fun `markAccepted clears ignore count`() {
        val store = CooldownStore(dao = null)
        store.markSurfaced("k")
        store.markIgnored("k")
        store.markIgnored("k")
        store.markAccepted("k")
        assertEquals(0, store.ignoreCount("k"))
    }
}
