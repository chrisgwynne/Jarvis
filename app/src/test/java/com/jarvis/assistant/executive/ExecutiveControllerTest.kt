package com.jarvis.assistant.executive

import com.jarvis.assistant.voice.VoiceFeatureFlags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier-C unit tests for [ExecutiveController.decide] verdict shape and the
 * task lifecycle bookkeeping consumed by the proactive dispatcher.
 */
class ExecutiveControllerTest {

    private lateinit var ec: ExecutiveController

    @Before fun setUp() {
        VoiceFeatureFlags.setOverride(
            VoiceFeatureFlags.Flag.EXECUTIVE_CONTROLLER_ENABLED, true
        )
        ec = ExecutiveController()
    }

    @After fun tearDown() {
        VoiceFeatureFlags.clearOverride(
            VoiceFeatureFlags.Flag.EXECUTIVE_CONTROLLER_ENABLED
        )
    }

    private fun event(
        urgency:    Float = 0.5f,
        relevance:  Float = 0.5f,
        confidence: Float = 1.0f,
        source:     String = "proactive"
    ) = ExecutiveController.AttentionEvent(
        source     = source,
        urgency    = urgency,
        relevance  = relevance,
        confidence = confidence
    )

    @Test fun `flag off forces SILENT_NOTIFY`() {
        VoiceFeatureFlags.setOverride(
            VoiceFeatureFlags.Flag.EXECUTIVE_CONTROLLER_ENABLED, false
        )
        val v = ec.decide(event(urgency = 0.95f))
        assertEquals(ExecutiveController.PriorityVerdict.SILENT_NOTIFY, v.verdict)
        assertTrue(v.reason.contains("executive_disabled"))
    }

    @Test fun `high urgency speaks now`() {
        val v = ec.decide(event(urgency = 0.85f))
        assertEquals(ExecutiveController.PriorityVerdict.SPEAK_NOW, v.verdict)
    }

    @Test fun `medium urgency with no active tasks speaks now`() {
        val v = ec.decide(event(urgency = 0.60f))
        assertEquals(ExecutiveController.PriorityVerdict.SPEAK_NOW, v.verdict)
    }

    @Test fun `low confidence is ignored`() {
        val v = ec.decide(event(confidence = 0.10f))
        assertEquals(ExecutiveController.PriorityVerdict.IGNORE, v.verdict)
    }

    @Test fun `medium urgency with active task downgrades`() {
        val id = ec.createTask("dictation in progress", origin = "voice")
        ec.updateTaskState(id, ExecutiveController.TaskState.ACTIVE)
        val v = ec.decide(event(urgency = 0.60f))
        // active_task_count >= 1 → no longer the "free to speak" branch
        assertTrue(
            "Expected SILENT_NOTIFY/WAIT/ASK_CONFIRMATION when busy, got ${v.verdict}",
            v.verdict != ExecutiveController.PriorityVerdict.SPEAK_NOW
        )
    }

    @Test fun `tasks snapshot reflects creation and state changes`() {
        val a = ec.createTask("a", origin = "voice")
        ec.createTask("b", origin = "proactive")
        ec.updateTaskState(a, ExecutiveController.TaskState.ACTIVE)
        val snap = ec.snapshot()
        assertEquals(2, snap.size)
        assertTrue(snap.any { it.id == a && it.state == ExecutiveController.TaskState.ACTIVE })
    }
}
