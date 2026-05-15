package com.jarvis.assistant.wearables.meta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the state-machine helper predicates so a refactor to
 * [MetaWearablesState] can't accidentally flip a transition the rest
 * of the runtime depends on.
 */
class MetaWearablesStateTest {

    @Test fun `isReadyForCapture only for CAMERA_READY and STREAMING`() {
        for (s in MetaWearablesState.values()) {
            val expect = s == MetaWearablesState.CAMERA_READY ||
                s == MetaWearablesState.STREAMING
            assertTrue("$s isReadyForCapture mismatch", s.isReadyForCapture == expect)
        }
    }

    @Test fun `canConnect only for DISCONNECTED and ERROR`() {
        for (s in MetaWearablesState.values()) {
            val expect = s == MetaWearablesState.DISCONNECTED ||
                s == MetaWearablesState.ERROR
            assertTrue("$s canConnect mismatch", s.canConnect == expect)
        }
    }

    @Test fun `canDisconnect covers every live state`() {
        val live = setOf(
            MetaWearablesState.CONNECTING,
            MetaWearablesState.CONNECTED,
            MetaWearablesState.CAMERA_READY,
            MetaWearablesState.STREAMING,
            MetaWearablesState.CAPTURING,
        )
        for (s in MetaWearablesState.values()) {
            assertTrue("$s canDisconnect mismatch", s.canDisconnect == (s in live))
        }
    }

    @Test fun `disabled state is dormant`() {
        val s = MetaWearablesState.DISABLED
        assertFalse(s.isReadyForCapture)
        assertFalse(s.canConnect)
        assertFalse(s.canDisconnect)
    }
}
