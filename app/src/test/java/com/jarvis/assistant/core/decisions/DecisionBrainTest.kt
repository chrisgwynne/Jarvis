package com.jarvis.assistant.core.decisions

import com.jarvis.assistant.proactive.ProactiveAction
import com.jarvis.assistant.proactive.ProactiveEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionBrainTest {

    private val brain = DecisionBrain()

    // ── Reactive labelling ────────────────────────────────────────────────────

    @Test
    fun `chat outcome becomes RespondChat`() {
        val d = brain.labelReactive(ReactiveOutcome.Chat())
        assertTrue(d is Decision.RespondChat)
        assertEquals(DecisionTrace.Source.REACTIVE, d.trace.source)
    }

    @Test
    fun `tool outcome becomes ExecuteTool and preserves name`() {
        val d = brain.labelReactive(
            ReactiveOutcome.Tool("SendMessage"),
            classifiers = listOf("IntentClassifier", "ToolUsePolicy"),
        )
        assertTrue(d is Decision.ExecuteTool)
        assertEquals("SendMessage", (d as Decision.ExecuteTool).toolName)
        assertEquals(listOf("IntentClassifier", "ToolUsePolicy"), d.trace.classifiers)
    }

    @Test
    fun `plan outcome becomes ExecutePlan with step count`() {
        val d = brain.labelReactive(ReactiveOutcome.Plan(stepCount = 3))
        assertTrue(d is Decision.ExecutePlan)
        assertEquals(3, (d as Decision.ExecutePlan).stepCount)
    }

    @Test
    fun `clarify outcome becomes AskClarification`() {
        val d = brain.labelReactive(ReactiveOutcome.Clarify("Text whom?"))
        assertTrue(d is Decision.AskClarification)
        assertEquals("Text whom?", (d as Decision.AskClarification).prompt)
    }

    @Test
    fun `suppressed outcome carries the reason into the trace`() {
        val d = brain.labelReactive(
            ReactiveOutcome.Suppressed(SuppressionReason.USER_DISLIKE)
        )
        assertTrue(d is Decision.Ignore)
        assertEquals(SuppressionReason.USER_DISLIKE, (d as Decision.Ignore).reason)
        assertEquals(SuppressionReason.USER_DISLIKE, d.trace.suppressionReason)
    }

    // ── Proactive labelling ───────────────────────────────────────────────────

    @Test
    fun `NoAction with reason becomes Ignore with matching reason`() {
        val d = brain.labelProactive(ProactiveAction.NoAction(SuppressionReason.DRIVING))
        assertTrue(d is Decision.Ignore)
        assertEquals(SuppressionReason.DRIVING, (d as Decision.Ignore).reason)
        assertEquals(SuppressionReason.DRIVING, d.trace.suppressionReason)
        assertEquals(DecisionTrace.Source.PROACTIVE, d.trace.source)
    }

    @Test
    fun `SpeakAction becomes ExecuteTool with tts prefix`() {
        val d = brain.labelProactive(
            ProactiveAction.SpeakAction(
                text = "Battery's low.",
                dedupeKey = "battery_low",
                sourceType = ProactiveEventType.LOW_BATTERY,
            )
        )
        assertTrue(d is Decision.ExecuteTool)
        val tool = (d as Decision.ExecuteTool).toolName
        assertTrue("expected tts prefix, got $tool", tool.startsWith("tts:"))
        assertNull(d.trace.suppressionReason)
    }

    @Test
    fun `PassiveAction becomes ExecuteTool with notify prefix`() {
        val d = brain.labelProactive(
            ProactiveAction.PassiveAction(
                title = "Meeting at 3",
                body = null,
                dedupeKey = "cal_meeting_3",
                sourceType = ProactiveEventType.UPCOMING_MEETING,
            )
        )
        assertTrue(d is Decision.ExecuteTool)
        assertTrue((d as Decision.ExecuteTool).toolName.startsWith("notify:"))
    }
}
