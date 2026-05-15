package com.jarvis.assistant.wearables.meta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * StubMetaWearablesProviderTest — the stub MUST be a no-op that
 * never crashes and always reports SDK_UNAVAILABLE.  This is the
 * acceptance criterion "If SDK/device unavailable: app still builds,
 * Jarvis still works, settings show 'not connected', no crashes."
 */
class StubMetaWearablesProviderTest {

    @Test fun `stub starts at SDK_UNAVAILABLE and stays there`() = runBlocking {
        val p = StubMetaWearablesProvider()
        assertEquals(MetaWearablesState.SDK_UNAVAILABLE, p.currentState)
        assertFalse(p.connect())
        assertEquals(MetaWearablesState.SDK_UNAVAILABLE, p.currentState)
        p.disconnect()    // no-throw
        p.startCameraSession()
        p.stopCameraSession()
        assertNull(p.capturePhoto())
        assertFalse(p.startStream { _, _, _ -> false })
        p.stopStream()
    }

    @Test fun `stub never throws from any method`() = runBlocking {
        val p = StubMetaWearablesProvider()
        // Walk every public method.  Any throw would fail the test.
        p.connect()
        p.disconnect()
        p.startCameraSession()
        p.stopCameraSession()
        p.capturePhoto()
        p.startStream { _, _, _ -> true }
        p.stopStream()
        p.peekRecent()
        p.clearRecent()
    }
}
