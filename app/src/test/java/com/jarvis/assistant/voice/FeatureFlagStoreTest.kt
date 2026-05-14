package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.assistant.testing.stubSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class FeatureFlagStoreTest {

    private lateinit var context: Context
    private lateinit var store:   FeatureFlagStore

    @Before fun setUp() {
        context = mock()
        context.stubSharedPreferences()
        store = FeatureFlagStore(context)
    }

    @After fun tearDown() {
        // Clean every override the test may have flipped so each case starts
        // from defaults (the in-memory VoiceFeatureFlags map is process-wide).
        for (f in VoiceFeatureFlags.Flag.values()) VoiceFeatureFlags.clearOverride(f)
    }

    @Test fun `default value is reflected when no override`() {
        for (f in VoiceFeatureFlags.Flag.values()) {
            assertEquals(
                "Flag ${f.name} should report its declared default",
                f.defaultEnabled,
                VoiceFeatureFlags.isEnabled(f)
            )
        }
    }

    @Test fun `setOverride true flips an off-by-default flag on`() {
        // Pick one we know is off by default.
        val flag = VoiceFeatureFlags.Flag.ADAPTIVE_WAKE_THRESHOLD_ENABLED
        assertFalse(flag.defaultEnabled)
        store.setOverride(flag, true)
        assertTrue(VoiceFeatureFlags.isEnabled(flag))
    }

    @Test fun `setOverride false flips an on-by-default flag off`() {
        val flag = VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED
        assertTrue(flag.defaultEnabled)
        store.setOverride(flag, false)
        assertFalse(VoiceFeatureFlags.isEnabled(flag))
    }

    @Test fun `clearOverride restores default`() {
        val flag = VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED
        store.setOverride(flag, false)
        assertFalse(VoiceFeatureFlags.isEnabled(flag))
        store.clearOverride(flag)
        assertEquals(flag.defaultEnabled, VoiceFeatureFlags.isEnabled(flag))
        assertNull(store.getOverride(flag))
    }

    @Test fun `setOverride null is equivalent to clearOverride`() {
        val flag = VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED
        store.setOverride(flag, false)
        store.setOverride(flag, null)
        assertNull(store.getOverride(flag))
        assertEquals(flag.defaultEnabled, VoiceFeatureFlags.isEnabled(flag))
    }

    @Test fun `loadAtStartup applies persisted overrides into VoiceFeatureFlags`() {
        // Persist an override, then construct a fresh store and call loadAtStartup
        // to simulate a process restart.
        val flag = VoiceFeatureFlags.Flag.ADAPTIVE_WAKE_THRESHOLD_ENABLED
        store.setOverride(flag, true)

        // Reset the in-memory map only (don't touch SharedPreferences) to prove
        // the next loadAtStartup re-applies from the persistent store.
        VoiceFeatureFlags.clearOverride(flag)
        assertFalse("Pre-condition: flag back to default", VoiceFeatureFlags.isEnabled(flag))

        store.loadAtStartup()
        assertTrue("loadAtStartup must restore the persisted override",
            VoiceFeatureFlags.isEnabled(flag))
    }

    @Test fun `effectiveValue returns default when no override`() {
        val flag = VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED
        assertEquals(flag.defaultEnabled, store.effectiveValue(flag))
    }

    @Test fun `effectiveValue returns override when set`() {
        val flag = VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED
        store.setOverride(flag, false)
        assertFalse(store.effectiveValue(flag))
    }

    @Test fun `hasOverride is false before set and true after`() {
        val flag = VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED
        assertFalse(store.hasOverride(flag))
        store.setOverride(flag, false)
        assertTrue(store.hasOverride(flag))
        store.clearOverride(flag)
        assertFalse(store.hasOverride(flag))
    }

    @Test fun `clearAll wipes every override`() {
        store.setOverride(VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED, false)
        store.setOverride(VoiceFeatureFlags.Flag.ADAPTIVE_WAKE_THRESHOLD_ENABLED, true)
        store.clearAll()
        for (f in VoiceFeatureFlags.Flag.values()) {
            assertNull("Flag ${f.name} should have no override", store.getOverride(f))
        }
    }
}
