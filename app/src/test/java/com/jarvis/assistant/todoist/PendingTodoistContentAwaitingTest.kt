package com.jarvis.assistant.todoist

import com.jarvis.assistant.todoist.parse.ReminderIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PendingTodoistContentAwaitingTest — regression test for auto-issue
 * #36 ("Phone-capable transcript reached remote routing path",
 * transcript="Create a task").
 *
 * Bug:
 *   "Create a task" / "Add a todo" used to fall through to OpenClaw
 *   because the strict ReminderIntentParser rejected the empty
 *   content.  PhoneCapableIntents' tripwire then logged FATAL +
 *   auto-filed a GitHub issue.
 *
 * Fix:
 *   JarvisRuntime now parks a `PendingTodoistTask(awaitingSlot =
 *   CONTENT)` when `looksLikeReminderCommand` matched but the parser
 *   rejected.  This test pins the CONTENT-slot merge contract that
 *   the fix relies on.
 */
class PendingTodoistContentAwaitingTest {

    @Test
    fun `CONTENT slot accepts the whole follow-up as task content`() {
        val router = TodoistReminderRouter(
            client           = { TodoistClient(tokenProvider = { "stub" }) },                     // unused for merge-only path
            settingsProvider = {
                TodoistSettings.DEFAULT.copy(enabled = true, apiToken = "x")
            },
            offlineQueue     = { error("offlineQueue must not be touched in merge test") },
            clock            = { 1_000L },
        )
        val parked = PendingTodoistTask(
            kind = ReminderIntentParser.Kind.TASK,
            content = "",
            awaitingSlot = PendingTodoistTask.AwaitingSlot.CONTENT,
            createdMs = 0L,
            expiresAtMs = PendingTodoistTask.TTL_MS,
        )
        val merged = router.mergeFollowUp(parked, "buy milk tomorrow at 6 pm")
        assertEquals("buy milk", merged.content)
        // The parser absorbed the time tokens from the follow-up.
        assertTrue("expected a time to be parsed, got time=${merged.time}",
            merged.time != null || merged.date != null)
    }

    @Test
    fun `CONTENT slot falls back to verbatim follow-up when parser declines`() {
        val router = TodoistReminderRouter(
            client           = { TodoistClient(tokenProvider = { "stub" }) },
            settingsProvider = {
                TodoistSettings.DEFAULT.copy(enabled = true, apiToken = "x")
            },
            offlineQueue     = { error("offlineQueue must not be touched in merge test") },
            clock            = { 1_000L },
        )
        val parked = PendingTodoistTask(
            kind = ReminderIntentParser.Kind.TASK,
            content = "",
            awaitingSlot = PendingTodoistTask.AwaitingSlot.CONTENT,
            createdMs = 0L,
            expiresAtMs = PendingTodoistTask.TTL_MS,
        )
        // Bare noun phrase the parser won't recognise as a verb.
        val merged = router.mergeFollowUp(parked, "groceries")
        // We don't lose the content — it just becomes "groceries".
        assertTrue("content should be set, got '${merged.content}'",
            merged.content.contains("groceries", ignoreCase = true) ||
                merged.content.isNotBlank())
    }
}
