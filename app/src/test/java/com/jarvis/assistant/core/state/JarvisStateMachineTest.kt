package com.jarvis.assistant.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JarvisStateMachineTest {

    private lateinit var machine: JarvisStateMachine

    @Before fun setUp() { machine = JarvisStateMachine() }

    // ── Valid transitions ──────────────────────────────────────────────────────

    @Test fun `SERVICE_STOPPED can transition to IDLE_WAKE`() {
        assertTrue(machine.transition(JarvisState.IdleWake))
        assertTrue(machine.isIn<JarvisState.IdleWake>())
    }

    @Test fun `IDLE_WAKE to WAKE_DETECTED is valid`() {
        machine.transition(JarvisState.IdleWake)
        assertTrue(machine.transition(JarvisState.WakeDetected))
    }

    @Test fun `full happy path IDLE → WAKE → LISTEN → THINK → SPEAK → LISTEN`() {
        machine.transition(JarvisState.IdleWake)
        machine.transition(JarvisState.WakeDetected)
        machine.transition(JarvisState.Listening)
        machine.transition(JarvisState.Thinking)
        machine.transition(JarvisState.Speaking)
        machine.transition(JarvisState.Listening)

        assertTrue(machine.isIn<JarvisState.Listening>())
    }

    @Test fun `SPEAKING to INTERRUPTED to LISTENING (barge-in path)`() {
        machine.forceTransition(JarvisState.Speaking)
        assertTrue(machine.transition(JarvisState.Interrupted))
        assertTrue(machine.transition(JarvisState.Listening))
        assertTrue(machine.isIn<JarvisState.Listening>())
    }

    @Test fun `THINKING can route through TOOL_RUNNING`() {
        machine.forceTransition(JarvisState.Thinking)
        assertTrue(machine.transition(JarvisState.ToolRunning("flashlight")))
        assertTrue(machine.transition(JarvisState.Speaking))
    }

    @Test fun `LISTENING can transition to MIC_UNAVAILABLE`() {
        machine.forceTransition(JarvisState.Listening)
        assertTrue(machine.transition(JarvisState.MicUnavailable))
    }

    @Test fun `MIC_UNAVAILABLE recovers to IDLE_WAKE`() {
        machine.forceTransition(JarvisState.MicUnavailable)
        assertTrue(machine.transition(JarvisState.IdleWake))
    }

    @Test fun `silence path SPEAKING to SILENCED to IDLE_WAKE`() {
        machine.forceTransition(JarvisState.Speaking)
        assertTrue(machine.transition(JarvisState.Silenced))
        assertTrue(machine.transition(JarvisState.IdleWake))
    }

    @Test fun `THINKING can transition to OFFLINE_FALLBACK`() {
        machine.forceTransition(JarvisState.Thinking)
        assertTrue(machine.transition(JarvisState.OfflineFallback))
    }

    @Test fun `OFFLINE_FALLBACK recovers to IDLE_WAKE`() {
        machine.forceTransition(JarvisState.OfflineFallback)
        assertTrue(machine.transition(JarvisState.IdleWake))
    }

    // ── Illegal transitions ────────────────────────────────────────────────────

    @Test fun `SERVICE_STOPPED cannot jump to LISTENING`() {
        assertFalse(machine.transition(JarvisState.Listening))
        assertTrue(machine.isIn<JarvisState.ServiceStopped>())  // state unchanged
    }

    @Test fun `IDLE_WAKE cannot jump to SPEAKING`() {
        machine.transition(JarvisState.IdleWake)
        assertFalse(machine.transition(JarvisState.Speaking))
        assertTrue(machine.isIn<JarvisState.IdleWake>())
    }

    @Test fun `SPEAKING cannot go directly to THINKING`() {
        machine.forceTransition(JarvisState.Speaking)
        assertFalse(machine.transition(JarvisState.Thinking))
    }

    // ── No-op same-state transition ────────────────────────────────────────────

    @Test fun `transitioning to the same state is a no-op and returns true`() {
        machine.transition(JarvisState.IdleWake)
        assertTrue(machine.transition(JarvisState.IdleWake))  // no-op
        assertTrue(machine.isIn<JarvisState.IdleWake>())
    }

    // ── Force transition ──────────────────────────────────────────────────────

    @Test fun `forceTransition bypasses graph`() {
        machine.forceTransition(JarvisState.Speaking)
        assertTrue(machine.isIn<JarvisState.Speaking>())
    }

    // ── State helpers ──────────────────────────────────────────────────────────

    @Test fun `LISTENING isMicActive is true`() {
        assertTrue(JarvisState.Listening.isMicActive)
        assertFalse(JarvisState.Speaking.isMicActive)
        assertFalse(JarvisState.IdleWake.isMicActive)
    }

    @Test fun `SPEAKING isSpeaking is true`() {
        assertTrue(JarvisState.Speaking.isSpeaking)
        assertFalse(JarvisState.Listening.isSpeaking)
    }

    @Test fun `toLegacyState maps correctly`() {
        assertEquals("IDLE",       JarvisState.IdleWake.toLegacyState())
        assertEquals("LISTENING",  JarvisState.Listening.toLegacyState())
        assertEquals("PROCESSING", JarvisState.Thinking.toLegacyState())
        assertEquals("SPEAKING",   JarvisState.Speaking.toLegacyState())
        assertEquals("PROCESSING", JarvisState.ToolRunning("x").toLegacyState())
        assertEquals("IDLE",       JarvisState.MicUnavailable.toLegacyState())
    }
}
