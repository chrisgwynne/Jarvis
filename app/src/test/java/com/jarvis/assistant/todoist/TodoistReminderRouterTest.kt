package com.jarvis.assistant.todoist

import com.jarvis.assistant.todoist.parse.ReminderIntentParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class TodoistReminderRouterTest {

    private val now: Long = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        .apply { set(2026, Calendar.MAY, 13, 10, 0, 0); set(Calendar.MILLISECOND, 0) }
        .timeInMillis

    private val defaultSettings = TodoistSettings.DEFAULT.copy(
        enabled  = true,
        apiToken = "test_token",
    )

    /** Stub client recording every call and returning a fixed result. */
    private class StubClient(
        private val createResult: TodoistClient.Result<TodoistTask> =
            TodoistClient.Result.Ok(TodoistTask(id = "abc123", content = "ok"))
    ) : TodoistClient(tokenProvider = { "stub" }) {
        var createInvocations = mutableListOf<CreateTaskRequest>()
        override suspend fun createTask(req: CreateTaskRequest): Result<TodoistTask> {
            createInvocations += req
            return createResult
        }
    }

    /**
     * Build a router with a stub client.  The offline-queue lambda
     * throws on access because none of the kept tests reach the offline
     * branch — they all exercise the happy path or simple parse logic.
     */
    private fun router(
        client: StubClient = StubClient(),
        settings: TodoistSettings = defaultSettings,
    ) = TodoistReminderRouter(
        client           = { client },
        settingsProvider = { settings },
        offlineQueue     = { error("queue not expected to be touched in this test") },
        clock            = { now },
    )

    // ── Fresh-turn behaviour ───────────────────────────────────────────────

    @Test
    fun `disabled returns hint without calling client`() = runBlocking {
        val c = StubClient()
        val r = router(client = c, settings = defaultSettings.copy(enabled = false))
            .handleFresh("remind me to take bins out tomorrow at 7")
        assertTrue(r is TodoistReminderRouter.RouterAction.Speak)
        assertTrue((r as TodoistReminderRouter.RouterAction.Speak).text
            .contains("Settings", ignoreCase = true))
        assertEquals(0, c.createInvocations.size)
    }

    @Test
    fun `missing token returns hint without calling client`() = runBlocking {
        val c = StubClient()
        val r = router(
            client = c,
            settings = defaultSettings.copy(apiToken = "")
        ).handleFresh("remind me to take bins out tomorrow at 7")
        assertTrue(r is TodoistReminderRouter.RouterAction.Speak)
        assertTrue((r as TodoistReminderRouter.RouterAction.Speak).text
            .contains("token", ignoreCase = true))
        assertEquals(0, c.createInvocations.size)
    }

    @Test
    fun `non-reminder utterance returns NotApplicable`() = runBlocking {
        val r = router().handleFresh("what time is it")
        assertTrue(r is TodoistReminderRouter.RouterAction.NotApplicable)
    }

    @Test
    fun `task without time creates immediately`() = runBlocking {
        val c = StubClient()
        val r = router(client = c).handleFresh("todo call Mike")
        assertTrue(r is TodoistReminderRouter.RouterAction.Speak)
        assertEquals(1, c.createInvocations.size)
        assertTrue(c.createInvocations[0].content.contains("call mike", ignoreCase = true))
    }

    @Test
    fun `reminder without time parks and asks for time`() = runBlocking {
        val r = router().handleFresh("remind me to call the dentist")
        assertTrue(r is TodoistReminderRouter.RouterAction.ParkPending)
        val parked = r as TodoistReminderRouter.RouterAction.ParkPending
        assertTrue(parked.askPrompt.contains("remind", ignoreCase = true))
        assertEquals(PendingTodoistTask.AwaitingSlot.TIME, parked.pending.awaitingSlot)
    }

    @Test
    fun `reminder with date and time creates immediately`() = runBlocking {
        val c = StubClient()
        val r = router(client = c).handleFresh("remind me about MOT on July 12 at 9am")
        assertTrue(r is TodoistReminderRouter.RouterAction.Speak)
        assertEquals(1, c.createInvocations.size)
        val req = c.createInvocations[0]
        assertTrue("dueString should be set: $req", req.dueString != null)
    }

    // ── Follow-up merge ────────────────────────────────────────────────────

    @Test
    fun `follow-up 9pm fills the time slot`() = runBlocking {
        val c = StubClient()
        val router = router(client = c)
        // Park a pending task with awaitingSlot=TIME.
        val parked = (router.handleFresh("remind me to call the dentist")
            as TodoistReminderRouter.RouterAction.ParkPending).pending
        // Follow-up "9pm" should fill the time.
        val r = router.handleFollowUp(parked, "9pm")
        assertTrue(r is TodoistReminderRouter.RouterAction.Speak)
        assertEquals(1, c.createInvocations.size)
        val req = c.createInvocations[0]
        assertTrue("dueString should mention 21:00: '${req.dueString}'",
            req.dueString!!.contains("21:00"))
    }

    @Test
    fun `follow-up tomorrow at 8 fills both date and time`() = runBlocking {
        val c = StubClient()
        val router = router(client = c)
        val parked = (router.handleFresh("remind me to call the dentist")
            as TodoistReminderRouter.RouterAction.ParkPending).pending
        val r = router.handleFollowUp(parked, "tomorrow at 8am")
        assertTrue(r is TodoistReminderRouter.RouterAction.Speak)
        assertEquals(1, c.createInvocations.size)
        val req = c.createInvocations[0]
        assertEquals("2026-05-14 at 08:00", req.dueString)
    }

    // ── buildCreateRequest unit checks ─────────────────────────────────────

    @Test
    fun `buildCreateRequest fills due string from date+time`() {
        val router = router()
        val pending = PendingTodoistTask(
            kind = ReminderIntentParser.Kind.REMINDER,
            content = "take bins out",
            date = "2026-05-14", time = "19:00",
            awaitingSlot = PendingTodoistTask.AwaitingSlot.NONE,
            createdMs = now, expiresAtMs = now + 60_000L,
        )
        val req = router.buildCreateRequest(pending, defaultSettings)
        assertEquals("2026-05-14 at 19:00", req.dueString)
    }

    @Test
    fun `buildCreateRequest uses recurrence over date when present`() {
        val router = router()
        val pending = PendingTodoistTask(
            kind = ReminderIntentParser.Kind.REMINDER,
            content = "journal",
            date = "2026-05-14", time = "08:00",
            recurrence = "every day at 8am",
            awaitingSlot = PendingTodoistTask.AwaitingSlot.NONE,
            createdMs = now, expiresAtMs = now + 60_000L,
        )
        val req = router.buildCreateRequest(pending, defaultSettings)
        assertEquals("every day at 8am", req.dueString)
    }

    @Test
    fun `buildCreateRequest applies default priority`() {
        val router = router()
        val pending = PendingTodoistTask(
            kind = ReminderIntentParser.Kind.TASK,
            content = "buy milk",
            awaitingSlot = PendingTodoistTask.AwaitingSlot.NONE,
            createdMs = now, expiresAtMs = now + 60_000L,
        )
        val req = router.buildCreateRequest(
            pending,
            defaultSettings.copy(defaultPriority = TodoistPriority.HIGH)
        )
        assertEquals(TodoistPriority.HIGH.apiValue, req.priority)
    }

    @Test
    fun `mergeFollowUp into TIME slot accepts loose 9pm`() {
        val router = router()
        val parked = PendingTodoistTask(
            kind = ReminderIntentParser.Kind.REMINDER,
            content = "call dentist",
            awaitingSlot = PendingTodoistTask.AwaitingSlot.TIME,
            createdMs = now, expiresAtMs = now + 60_000L,
        )
        val merged = router.mergeFollowUp(parked, "9pm")
        assertEquals("21:00", merged.time)
    }
}

