package com.jarvis.assistant.session

import com.jarvis.assistant.session.context.ContextBundle
import com.jarvis.assistant.session.context.MessageChannel
import com.jarvis.assistant.session.context.RecentCalendarContext
import com.jarvis.assistant.session.context.RecentCalendarContextStore
import com.jarvis.assistant.session.context.RecentHomeAssistantContext
import com.jarvis.assistant.session.context.RecentHomeAssistantContextStore
import com.jarvis.assistant.session.context.RecentMessageContext
import com.jarvis.assistant.session.context.RecentMessageContextStore
import com.jarvis.assistant.session.context.RecentTodoistContext
import com.jarvis.assistant.session.context.RecentTodoistContextStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskContinuationEngineTest {

    private lateinit var engine: SessionStateEngine
    private lateinit var haStore: RecentHomeAssistantContextStore
    private lateinit var msgStore: RecentMessageContextStore
    private lateinit var calStore: RecentCalendarContextStore
    private lateinit var todoStore: RecentTodoistContextStore

    private fun makeEngine(
        ha: RecentHomeAssistantContextStore? = null,
        msg: RecentMessageContextStore? = null,
        cal: RecentCalendarContextStore? = null,
        todo: RecentTodoistContextStore? = null,
    ): TaskContinuationEngine {
        return TaskContinuationEngine(
            sessionStateEngine = engine,
            contextBundle = ContextBundle(
                visual = null,
                mapsNavigation = null,
                recentApp = null,
                message = msg ?: msgStore,
                calendar = cal ?: calStore,
                homeAssistant = ha ?: haStore,
                todoist = todo ?: todoStore,
                proactive = null,
            )
        )
    }

    @Before
    fun setUp() {
        engine   = SessionStateEngine()
        haStore  = RecentHomeAssistantContextStore()
        msgStore = RecentMessageContextStore()
        calStore = RecentCalendarContextStore()
        todoStore = RecentTodoistContextStore()
        engine.startSession("test-session")
    }

    // ── PassThrough when no matching pattern ──────────────────────────────────

    @Test
    fun `unrelated phrase passes through`() {
        val result = makeEngine().resolve("what time is it")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.PassThrough)
    }

    // ── HA toggle ─────────────────────────────────────────────────────────────

    @Test
    fun `turn it off with HA context returns Execute off`() {
        haStore.set(RecentHomeAssistantContext(
            entityId = "light.kitchen", friendlyName = "Kitchen Light",
            domain = "light", lastAction = "on"
        ))
        val result = makeEngine().resolve("turn it off")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.Execute)
        val exec = result as TaskContinuationEngine.ContinuationResult.Execute
        assertEquals("smart_home", exec.toolName)
        assertEquals("off", exec.params["action"])
        assertEquals("Kitchen Light", exec.params["entity_name"])
    }

    @Test
    fun `turn it on with HA context returns Execute on`() {
        haStore.set(RecentHomeAssistantContext(
            entityId = "light.kitchen", friendlyName = "Kitchen Light",
            domain = "light", lastAction = "off"
        ))
        val result = makeEngine().resolve("turn it on")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.Execute)
        val exec = result as TaskContinuationEngine.ContinuationResult.Execute
        assertEquals("on", exec.params["action"])
    }

    @Test
    fun `toggle it uses opposite action`() {
        haStore.set(RecentHomeAssistantContext(
            entityId = "switch.fan", friendlyName = "Bedroom Fan",
            domain = "switch", lastAction = "on"
        ))
        val result = makeEngine().resolve("toggle it")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.Execute)
        val exec = result as TaskContinuationEngine.ContinuationResult.Execute
        assertEquals("off", exec.params["action"])  // opposite of "on"
    }

    @Test
    fun `HA toggle without context passes through`() {
        // haStore is empty
        val result = makeEngine().resolve("turn it off")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.PassThrough)
    }

    @Test
    fun `switch it off with HA context returns Execute off`() {
        haStore.set(RecentHomeAssistantContext(
            entityId = "light.living_room", friendlyName = "Living Room Light",
            domain = "light", lastAction = "on"
        ))
        val result = makeEngine().resolve("switch it off")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.Execute)
        val exec = result as TaskContinuationEngine.ContinuationResult.Execute
        assertEquals("off", exec.params["action"])
    }

    // ── Calendar move ─────────────────────────────────────────────────────────

    @Test
    fun `move it to Friday with calendar context returns Execute with when`() {
        calStore.set(RecentCalendarContext(
            title = "Team standup", startMs = System.currentTimeMillis(),
            endMs = System.currentTimeMillis() + 3_600_000L
        ))
        val result = makeEngine().resolve("move it to Friday")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.Execute)
        val exec = result as TaskContinuationEngine.ContinuationResult.Execute
        assertEquals("calendar", exec.toolName)
        assertEquals("Friday", exec.params["when"])
        assertEquals("Team standup", exec.params["title"])
    }

    @Test
    fun `reschedule it alone asks for when slot`() {
        calStore.set(RecentCalendarContext(
            title = "Dentist", startMs = System.currentTimeMillis(),
            endMs = System.currentTimeMillis() + 3_600_000L
        ))
        val result = makeEngine().resolve("reschedule it")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.AskSlot)
        val ask = result as TaskContinuationEngine.ContinuationResult.AskSlot
        assertEquals("when", ask.slotName)
    }

    @Test
    fun `calendar move without context passes through`() {
        val result = makeEngine().resolve("move it to Friday")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.PassThrough)
    }

    // ── Message reply ─────────────────────────────────────────────────────────

    @Test
    fun `reply with body and SMS context returns Execute sms_send`() {
        msgStore.set(RecentMessageContext(
            sender = "Mike", senderNumber = "07700900000",
            body = "Are you coming?", channel = MessageChannel.SMS
        ))
        val result = makeEngine().resolve("reply with I'm on my way")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.Execute)
        val exec = result as TaskContinuationEngine.ContinuationResult.Execute
        assertEquals("sms_send", exec.toolName)
        assertEquals("Mike", exec.params["contact"])
        assertEquals("I'm on my way", exec.params["body"])
    }

    @Test
    fun `reply with body and WhatsApp context returns Execute whatsapp_send`() {
        msgStore.set(RecentMessageContext(
            sender = "Sarah", senderNumber = "+447700900001",
            body = "Dinner tonight?", channel = MessageChannel.WHATSAPP
        ))
        val result = makeEngine().resolve("reply yes, sounds great")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.Execute)
        val exec = result as TaskContinuationEngine.ContinuationResult.Execute
        assertEquals("whatsapp_send", exec.toolName)
        assertEquals("yes, sounds great", exec.params["body"])
    }

    @Test
    fun `reply alone with message context asks for body slot`() {
        msgStore.set(RecentMessageContext(
            sender = "Dave", body = "Call me back",
            channel = MessageChannel.SMS
        ))
        val result = makeEngine().resolve("reply")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.AskSlot)
        val ask = result as TaskContinuationEngine.ContinuationResult.AskSlot
        assertEquals("body", ask.slotName)
    }

    @Test
    fun `reply without message context passes through`() {
        val result = makeEngine().resolve("reply with sure")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.PassThrough)
    }

    // ── Todoist actions ───────────────────────────────────────────────────────

    @Test
    fun `mark it done with Todoist context returns Execute complete`() {
        todoStore.set(RecentTodoistContext(
            taskId = "task-001", taskContent = "Buy milk",
            projectName = "Inbox", lastAction = "read"
        ))
        val result = makeEngine().resolve("mark it done")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.Execute)
        val exec = result as TaskContinuationEngine.ContinuationResult.Execute
        assertEquals("todoist", exec.toolName)
        assertEquals("complete", exec.params["action"])
        assertEquals("task-001", exec.params["task_id"])
    }

    @Test
    fun `make it urgent with Todoist context returns Execute update priority 4`() {
        todoStore.set(RecentTodoistContext(
            taskId = "task-002", taskContent = "Fix bug",
            projectName = "Work", lastAction = "read"
        ))
        val result = makeEngine().resolve("make it urgent")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.Execute)
        val exec = result as TaskContinuationEngine.ContinuationResult.Execute
        assertEquals("todoist", exec.toolName)
        assertEquals("update", exec.params["action"])
        assertEquals("4", exec.params["priority"])
    }

    @Test
    fun `todoist done without context passes through`() {
        val result = makeEngine().resolve("mark it done")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.PassThrough)
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Test
    fun `cancel with active goal returns Cancel`() {
        engine.setGoal(ConversationGoal(
            type   = GoalType.REPLY_TO_MESSAGE,
            status = GoalStatus.AWAITING_SLOT,
            slots  = listOf(PendingSlot("body", "What should I say?"))
        ))
        val result = makeEngine().resolve("cancel")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.Cancel)
    }

    @Test
    fun `cancel without active goal passes through`() {
        // No goal set — cancel should not be intercepted
        val result = makeEngine().resolve("cancel")
        assertTrue(result is TaskContinuationEngine.ContinuationResult.PassThrough)
    }
}
