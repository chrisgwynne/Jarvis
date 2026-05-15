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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContextResolverTest {

    private lateinit var haStore: RecentHomeAssistantContextStore
    private lateinit var msgStore: RecentMessageContextStore
    private lateinit var calStore: RecentCalendarContextStore
    private lateinit var todoStore: RecentTodoistContextStore

    private fun makeResolver(
        ha: RecentHomeAssistantContextStore? = null,
        msg: RecentMessageContextStore? = null,
        cal: RecentCalendarContextStore? = null,
        todo: RecentTodoistContextStore? = null,
    ) = ContextResolver(ContextBundle(
        visual = null,
        mapsNavigation = null,
        recentApp = null,
        message = msg ?: msgStore,
        calendar = cal ?: calStore,
        homeAssistant = ha ?: haStore,
        todoist = todo ?: todoStore,
        proactive = null,
    ))

    @Before
    fun setUp() {
        haStore   = RecentHomeAssistantContextStore()
        msgStore  = RecentMessageContextStore()
        calStore  = RecentCalendarContextStore()
        todoStore = RecentTodoistContextStore()
    }

    // ── None when no context ──────────────────────────────────────────────────

    @Test
    fun `returns None when all stores are empty`() {
        val result = makeResolver().resolve("turn it off")
        assertTrue(result is ContextResolver.Resolved.None)
    }

    @Test
    fun `returns None for non-pronoun phrase`() {
        haStore.set(RecentHomeAssistantContext(
            entityId = "light.kitchen", friendlyName = "Kitchen Light",
            domain = "light", lastAction = "on"
        ))
        val result = makeResolver().resolve("what time is it")
        assertTrue("Expected None for generic time query",
            result is ContextResolver.Resolved.None)
    }

    // ── Home Assistant hints ──────────────────────────────────────────────────

    @Test
    fun `turn it off resolves to HaEntity when HA context is live`() {
        haStore.set(RecentHomeAssistantContext(
            entityId = "light.kitchen", friendlyName = "Kitchen Light",
            domain = "light", lastAction = "on"
        ))
        val result = makeResolver().resolve("turn it off")
        assertTrue(result is ContextResolver.Resolved.HaEntity)
    }

    @Test
    fun `turn it on resolves to HaEntity`() {
        haStore.set(RecentHomeAssistantContext(
            entityId = "switch.fan", friendlyName = "Bedroom Fan",
            domain = "switch", lastAction = "off"
        ))
        val result = makeResolver().resolve("turn it on")
        assertTrue(result is ContextResolver.Resolved.HaEntity)
    }

    @Test
    fun `flip it resolves to HaEntity`() {
        haStore.set(RecentHomeAssistantContext(
            entityId = "light.living", friendlyName = "Living Room Light",
            domain = "light", lastAction = "on"
        ))
        val result = makeResolver().resolve("flip it")
        assertTrue(result is ContextResolver.Resolved.HaEntity)
    }

    @Test
    fun `turn it off without HA context returns None`() {
        val result = makeResolver().resolve("turn it off")
        assertTrue(result is ContextResolver.Resolved.None)
    }

    // ── Calendar hints ────────────────────────────────────────────────────────

    @Test
    fun `move it to Friday resolves to CalendarEvent`() {
        calStore.set(RecentCalendarContext(
            title = "Team standup",
            startMs = System.currentTimeMillis(),
            endMs = System.currentTimeMillis() + 3_600_000L
        ))
        val result = makeResolver().resolve("move it to Friday")
        assertTrue(result is ContextResolver.Resolved.CalendarEvent)
    }

    @Test
    fun `reschedule that resolves to CalendarEvent`() {
        calStore.set(RecentCalendarContext(
            title = "Dentist",
            startMs = System.currentTimeMillis(),
            endMs = System.currentTimeMillis() + 3_600_000L
        ))
        val result = makeResolver().resolve("reschedule that")
        assertTrue(result is ContextResolver.Resolved.CalendarEvent)
    }

    // ── Message hints ─────────────────────────────────────────────────────────

    @Test
    fun `reply resolves to Message when message context is live`() {
        msgStore.set(RecentMessageContext(
            sender = "Mike", senderNumber = "07700900000",
            body = "Are you coming?", channel = MessageChannel.SMS
        ))
        val result = makeResolver().resolve("reply with I'm on my way")
        assertTrue(result is ContextResolver.Resolved.Message)
    }

    // ── Todoist hints ─────────────────────────────────────────────────────────

    @Test
    fun `mark it done resolves to TodoistTask`() {
        todoStore.set(RecentTodoistContext(
            taskId = "task-001", taskContent = "Buy milk",
            projectName = "Inbox", lastAction = "read"
        ))
        val result = makeResolver().resolve("mark it done")
        assertTrue(result is ContextResolver.Resolved.TodoistTask)
    }

    @Test
    fun `make it urgent resolves to TodoistTask`() {
        todoStore.set(RecentTodoistContext(
            taskId = "task-002", taskContent = "Fix bug",
            projectName = "Work", lastAction = "read"
        ))
        val result = makeResolver().resolve("make it urgent")
        assertTrue(result is ContextResolver.Resolved.TodoistTask)
    }

    // ── Goal-type bias ────────────────────────────────────────────────────────

    @Test
    fun `HA goal biases to HaEntity when both HA and message context exist`() {
        haStore.set(RecentHomeAssistantContext(
            entityId = "light.kitchen", friendlyName = "Kitchen Light",
            domain = "light", lastAction = "on"
        ))
        msgStore.set(RecentMessageContext(
            sender = "Dave", body = "Hello", channel = MessageChannel.SMS
        ))
        val result = makeResolver().resolve("turn that off", goalType = GoalType.HA_CONTROL)
        assertTrue(result is ContextResolver.Resolved.HaEntity)
    }

    @Test
    fun `REPLY_TO_MESSAGE goal biases to Message`() {
        haStore.set(RecentHomeAssistantContext(
            entityId = "light.kitchen", friendlyName = "Kitchen Light",
            domain = "light", lastAction = "on"
        ))
        msgStore.set(RecentMessageContext(
            sender = "Dave", body = "Hello", channel = MessageChannel.SMS
        ))
        val result = makeResolver().resolve("send that", goalType = GoalType.REPLY_TO_MESSAGE)
        assertTrue(result is ContextResolver.Resolved.Message)
    }

    // ── Recency fallback ──────────────────────────────────────────────────────

    @Test
    fun `resolveByRecency picks most recently set context`() {
        // Set HA first, then message shortly after
        haStore.set(RecentHomeAssistantContext(
            entityId = "light.kitchen", friendlyName = "Kitchen Light",
            domain = "light", lastAction = "on",
            recordedAt = System.currentTimeMillis() - 10_000  // older
        ))
        msgStore.set(RecentMessageContext(
            sender = "Mike", body = "Hi",
            channel = MessageChannel.SMS,
            recordedAt = System.currentTimeMillis()  // newer
        ))
        // Generic pronoun with no goal hint
        val result = makeResolver().resolve("handle that")
        assertTrue("Newer message context should win", result is ContextResolver.Resolved.Message)
    }
}
