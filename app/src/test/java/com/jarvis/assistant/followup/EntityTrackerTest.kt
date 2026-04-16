package com.jarvis.assistant.followup

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EntityTrackerTest {

    private lateinit var tracker: EntityTracker

    @Before fun setUp() {
        tracker = EntityTracker()
    }

    // ── Basic tracking ────────────────────────────────────────────────────────

    @Test
    fun `track adds new entity`() {
        tracker.track(EntityReference(EntityType.CONTACT, label = "Chris"))
        assertNotNull(tracker.getMostSalient(EntityType.CONTACT))
        assertEquals("Chris", tracker.getMostSalient(EntityType.CONTACT)!!.label)
    }

    @Test
    fun `tracking same label resets salience`() {
        tracker.track(EntityReference(EntityType.CONTACT, label = "Chris", salience = 0.3f))
        tracker.decaySalience()
        tracker.track(EntityReference(EntityType.CONTACT, label = "Chris"))
        assertEquals(1.0f, tracker.getMostSalient(EntityType.CONTACT)!!.salience, 0.01f)
    }

    @Test
    fun `getMostSalient returns null when no entities`() {
        assertNull(tracker.getMostSalient(EntityType.CONTACT))
    }

    @Test
    fun `getMostSalient returns highest salience entity`() {
        tracker.track(EntityReference(EntityType.CONTACT, label = "Alice"))
        tracker.decaySalience()
        tracker.decaySalience()
        tracker.track(EntityReference(EntityType.CONTACT, label = "Bob"))  // fresh — salience 1.0

        assertEquals("Bob", tracker.getMostSalient(EntityType.CONTACT)!!.label)
    }

    // ── Decay ─────────────────────────────────────────────────────────────────

    @Test
    fun `decaySalience reduces salience over time`() {
        tracker.track(EntityReference(EntityType.CONTACT, label = "Alice"))
        repeat(5) { tracker.decaySalience() }
        // After 5 decays of 0.15 each: 1.0 - 0.75 = 0.25 — still positive
        val entity = tracker.getMostSalient(EntityType.CONTACT)
        assertNotNull(entity)
        assertTrue(entity!!.salience > 0f)
    }

    @Test
    fun `entity is pruned after enough decays`() {
        tracker.track(EntityReference(EntityType.CONTACT, label = "Alice"))
        repeat(8) { tracker.decaySalience() }  // 8 × 0.15 = 1.2 > 1.0 — should be pruned
        assertNull(tracker.getMostSalient(EntityType.CONTACT))
    }

    // ── Pronoun resolution ────────────────────────────────────────────────────

    @Test
    fun `him resolves to most salient CONTACT`() {
        tracker.track(EntityReference(EntityType.CONTACT, label = "Chris"))
        val resolved = tracker.resolvePronoun("him")
        assertNotNull(resolved)
        assertEquals("Chris", resolved!!.label)
    }

    @Test
    fun `her resolves to most salient CONTACT`() {
        tracker.track(EntityReference(EntityType.CONTACT, label = "Sarah"))
        assertEquals("Sarah", tracker.resolvePronoun("her")!!.label)
    }

    @Test
    fun `them resolves to most salient CONTACT`() {
        tracker.track(EntityReference(EntityType.CONTACT, label = "The Team"))
        assertEquals("The Team", tracker.resolvePronoun("them")!!.label)
    }

    @Test
    fun `pronoun returns null when no matching entity`() {
        assertNull(tracker.resolvePronoun("him"))
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    @Test
    fun `clear removes all entities`() {
        tracker.track(EntityReference(EntityType.CONTACT, label = "Alice"))
        tracker.track(EntityReference(EntityType.APP, label = "Spotify"))
        tracker.clear()
        assertNull(tracker.getMostSalient(EntityType.CONTACT))
        assertNull(tracker.getMostSalient(EntityType.APP))
    }

    // ── updateFromUtterance ───────────────────────────────────────────────────

    @Test
    fun `updateFromUtterance tracks contact from message command`() {
        tracker.updateFromUtterance("message Chris about dinner")
        assertNotNull(tracker.getMostSalient(EntityType.CONTACT))
        assertEquals("Chris", tracker.getMostSalient(EntityType.CONTACT)!!.label)
    }
}
