package com.jarvis.assistant.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PendingSlotResolverTest {

    private lateinit var engine: SessionStateEngine
    private lateinit var resolver: PendingSlotResolver

    @Before
    fun setUp() {
        engine = SessionStateEngine()
        resolver = PendingSlotResolver(engine)
        engine.startSession("test-session")
    }

    // ── PassThrough when no active goal ───────────────────────────────────────

    @Test
    fun `passthrough when no session`() {
        val freshEngine = SessionStateEngine()
        val r = PendingSlotResolver(freshEngine).resolve("8pm")
        assertTrue(r is PendingSlotResolver.SlotResult.PassThrough)
    }

    @Test
    fun `passthrough when session has no active goal`() {
        val r = resolver.resolve("8pm")
        assertTrue(r is PendingSlotResolver.SlotResult.PassThrough)
    }

    // ── Single-slot goal fills and becomes ready ──────────────────────────────

    @Test
    fun `fills single slot and returns FilledAndReady`() {
        engine.setGoal(ConversationGoal(
            type   = GoalType.CREATE_TODOIST_TASK,
            status = GoalStatus.AWAITING_SLOT,
            slots  = listOf(PendingSlot("time", "When should I remind you?"))
        ))
        val result = resolver.resolve("8pm")
        assertTrue(result is PendingSlotResolver.SlotResult.FilledAndReady)
        val goal = (result as PendingSlotResolver.SlotResult.FilledAndReady).goal
        assertEquals("8pm", goal.slot("time"))
        assertEquals(GoalStatus.READY_TO_EXECUTE, goal.status)
    }

    @Test
    fun `fills single body slot for reply goal`() {
        engine.setGoal(ConversationGoal(
            type   = GoalType.REPLY_TO_MESSAGE,
            status = GoalStatus.AWAITING_SLOT,
            slots  = listOf(PendingSlot("body", "What should I say?"))
        ))
        val result = resolver.resolve("I'll be there in 10")
        assertTrue(result is PendingSlotResolver.SlotResult.FilledAndReady)
        val goal = (result as PendingSlotResolver.SlotResult.FilledAndReady).goal
        assertEquals("I'll be there in 10", goal.slot("body"))
    }

    @Test
    fun `fills when slot for calendar edit goal`() {
        engine.setGoal(ConversationGoal(
            type   = GoalType.EDIT_CALENDAR_EVENT,
            status = GoalStatus.AWAITING_SLOT,
            slots  = listOf(PendingSlot("when", "When do you want to move it to?"))
        ))
        val result = resolver.resolve("Friday at 10")
        assertTrue(result is PendingSlotResolver.SlotResult.FilledAndReady)
        val goal = (result as PendingSlotResolver.SlotResult.FilledAndReady).goal
        assertEquals("Friday at 10", goal.slot("when"))
    }

    // ── Multi-slot goal: fills first, asks for next ───────────────────────────

    @Test
    fun `fills first slot and returns FilledNeedsMore when more remain`() {
        engine.setGoal(ConversationGoal(
            type   = GoalType.CREATE_CALENDAR_EVENT,
            status = GoalStatus.AWAITING_SLOT,
            slots  = listOf(
                PendingSlot("title",  "What's the event?"),
                PendingSlot("when",   "When is it?"),
            )
        ))
        val result = resolver.resolve("team standup")
        assertTrue(result is PendingSlotResolver.SlotResult.FilledNeedsMore)
        val next = (result as PendingSlotResolver.SlotResult.FilledNeedsMore).nextPrompt
        assertEquals("When is it?", next)
    }

    @Test
    fun `second resolve fills second slot and returns FilledAndReady`() {
        engine.setGoal(ConversationGoal(
            type   = GoalType.CREATE_CALENDAR_EVENT,
            status = GoalStatus.AWAITING_SLOT,
            slots  = listOf(
                PendingSlot("title", "What's the event?"),
                PendingSlot("when",  "When is it?"),
            )
        ))
        resolver.resolve("team standup")
        val result = resolver.resolve("Monday at 9am")
        assertTrue(result is PendingSlotResolver.SlotResult.FilledAndReady)
        val goal = (result as PendingSlotResolver.SlotResult.FilledAndReady).goal
        assertEquals("team standup", goal.slot("title"))
        assertEquals("Monday at 9am", goal.slot("when"))
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    fun `cancel returns Cancelled`() {
        engine.setGoal(ConversationGoal(
            type   = GoalType.REPLY_TO_MESSAGE,
            status = GoalStatus.AWAITING_SLOT,
            slots  = listOf(PendingSlot("body", "What should I say?"))
        ))
        val result = resolver.resolve("cancel")
        assertTrue(result is PendingSlotResolver.SlotResult.Cancelled)
    }

    @Test
    fun `never mind returns Cancelled`() {
        engine.setGoal(ConversationGoal(
            type   = GoalType.REPLY_TO_MESSAGE,
            status = GoalStatus.AWAITING_SLOT,
            slots  = listOf(PendingSlot("body", "What should I say?"))
        ))
        val result = resolver.resolve("never mind")
        assertTrue(result is PendingSlotResolver.SlotResult.Cancelled)
    }

    @Test
    fun `forget it returns Cancelled`() {
        engine.setGoal(ConversationGoal(
            type   = GoalType.REPLY_TO_MESSAGE,
            status = GoalStatus.AWAITING_SLOT,
            slots  = listOf(PendingSlot("body", "What should I say?"))
        ))
        val result = resolver.resolve("forget it")
        assertTrue(result is PendingSlotResolver.SlotResult.Cancelled)
    }

    @Test
    fun `cancellation phrase inside longer sentence is NOT treated as cancel`() {
        engine.setGoal(ConversationGoal(
            type   = GoalType.REPLY_TO_MESSAGE,
            status = GoalStatus.AWAITING_SLOT,
            slots  = listOf(PendingSlot("body", "What should I say?"))
        ))
        val result = resolver.resolve("just cancel the plans for tonight")
        assertTrue(result is PendingSlotResolver.SlotResult.FilledAndReady)
    }
}
