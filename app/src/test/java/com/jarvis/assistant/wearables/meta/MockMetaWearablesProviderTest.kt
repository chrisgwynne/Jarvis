package com.jarvis.assistant.wearables.meta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockMetaWearablesProviderTest — exercise the simulated state
 * machine end-to-end.  The mock is the "fake glasses for testing
 * without hardware" backend the spec explicitly requires.
 */
class MockMetaWearablesProviderTest {

    @Test fun `connect transitions DISCONNECTED to CONNECTED`() = runBlocking {
        val p = MockMetaWearablesProvider()
        assertEquals(MetaWearablesState.DISCONNECTED, p.currentState)
        assertTrue(p.connect())
        assertEquals(MetaWearablesState.CONNECTED, p.currentState)
    }

    @Test fun `startCameraSession after connect lands at CAMERA_READY`() = runBlocking {
        val p = MockMetaWearablesProvider()
        p.connect()
        assertTrue(p.startCameraSession())
        assertEquals(MetaWearablesState.CAMERA_READY, p.currentState)
    }

    @Test fun `capturePhoto returns a content URI and publishes recent context`() = runBlocking {
        val p = MockMetaWearablesProvider(clock = { 42L })
        p.connect()
        p.startCameraSession()
        val uri = p.capturePhoto()
        assertNotNull(uri)
        assertTrue("URI should reference the mock path, got $uri",
            uri!!.startsWith("content://com.jarvis.assistant.mock/wearable/photo_"))
        val recent = p.peekRecent()
        assertNotNull(recent)
        assertEquals(RecentVisualContext.Source.MOCK_WEARABLE, recent!!.source)
        assertEquals(RecentVisualContext.MediaType.PHOTO, recent.mediaType)
        assertEquals(uri, recent.uri)
    }

    @Test fun `simulateDisconnect drops to DISCONNECTED`() = runBlocking {
        val p = MockMetaWearablesProvider()
        p.connect(); p.startCameraSession()
        p.simulateDisconnect()
        assertEquals(MetaWearablesState.DISCONNECTED, p.currentState)
    }

    @Test fun `simulatePermissionMissing pins state`() = runBlocking {
        val p = MockMetaWearablesProvider()
        p.simulatePermissionMissing(true)
        assertFalse("connect must fail when permission is missing", p.connect())
        assertEquals(MetaWearablesState.PERMISSION_MISSING, p.currentState)
        p.simulatePermissionMissing(false)
        assertEquals(MetaWearablesState.DISCONNECTED, p.currentState)
    }

    @Test fun `simulateError lands at ERROR and sets lastError`() = runBlocking {
        val p = MockMetaWearablesProvider()
        p.simulateError(true)
        assertEquals(MetaWearablesState.ERROR, p.currentState)
        assertNotNull(p.lastError)
        p.simulateError(false)
        assertEquals(MetaWearablesState.DISCONNECTED, p.currentState)
        assertNull(p.lastError)
    }

    @Test fun `expired recent context is hidden by peekRecent`() = runBlocking {
        var now = 1_000L
        val p = MockMetaWearablesProvider(clock = { now })
        p.connect(); p.startCameraSession()
        p.capturePhoto()
        assertNotNull(p.peekRecent())
        now += RecentVisualContext.DEFAULT_TTL_MS + 1
        assertNull("expired context should be hidden", p.peekRecent())
    }
}
