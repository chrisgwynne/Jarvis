package com.jarvis.assistant.todoist

import com.jarvis.assistant.todoist.parse.TodoistListQueryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoistListSummaryTest {

    /**
     * Construct a router with a no-op client; the test only exercises
     * the pure summariser ([summariseTaskList]) which doesn't touch
     * network — but is internal so we go through a real router
     * instance.
     */
    private val router = TodoistReminderRouter(
        client           = { TodoistClient(tokenProvider = { "stub" }) },
        settingsProvider = { TodoistSettings.DEFAULT.copy(enabled = true, apiToken = "x") },
        offlineQueue     = { error("not used in summary tests") },
        clock            = { 0L },
    )

    private fun task(content: String) =
        TodoistTask(id = "1", content = content)

    // ── Empty list copy ───────────────────────────────────────────────────

    @Test
    fun `empty TODAY yields nothing on for today`() {
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.TODAY)
        assertEquals("Nothing on for today.",
            router.summariseTaskList(q, emptyList()))
    }

    @Test
    fun `empty OVERDUE yields nothing overdue`() {
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.OVERDUE)
        assertEquals("Nothing overdue.",
            router.summariseTaskList(q, emptyList()))
    }

    @Test
    fun `empty ALL_ACTIVE yields nothing on your list`() {
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.ALL_ACTIVE)
        assertEquals("Nothing on your list.",
            router.summariseTaskList(q, emptyList()))
    }

    @Test
    fun `empty SEARCH echoes the term`() {
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.SEARCH, "printer")
        assertEquals("No tasks match \"printer\".",
            router.summariseTaskList(q, emptyList()))
    }

    // ── Count scope ──────────────────────────────────────────────────────

    @Test
    fun `COUNT scope speaks just the number`() {
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.COUNT)
        assertEquals("You have one task.",
            router.summariseTaskList(q, listOf(task("a"))))
        assertEquals("You have 3 tasks.",
            router.summariseTaskList(q, listOf(task("a"), task("b"), task("c"))))
    }

    // ── Non-empty TODAY / OVERDUE ────────────────────────────────────────

    @Test
    fun `single task gets singular header`() {
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.TODAY)
        val out = router.summariseTaskList(q, listOf(task("buy milk")))
        assertTrue(out.startsWith("One thing today:"))
        assertTrue(out.contains("buy milk"))
    }

    @Test
    fun `multiple tasks get count header`() {
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.OVERDUE)
        val out = router.summariseTaskList(q, listOf(task("a"), task("b")))
        assertTrue(out.startsWith("2 overdue:"))
        assertTrue(out.contains("a"))
        assertTrue(out.contains("b"))
    }

    // ── Truncation ───────────────────────────────────────────────────────

    @Test
    fun `more than 5 tasks gets plus N more suffix`() {
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.ALL_ACTIVE)
        val tasks = (1..8).map { task("task $it") }
        val out = router.summariseTaskList(q, tasks)
        assertTrue("expected 'plus 3 more' in: $out", out.contains("plus 3 more"))
        // First 5 appear; last 3 don't.
        for (i in 1..5) assertTrue("task $i should be in: $out", out.contains("task $i"))
        for (i in 6..8) assertTrue("task $i should NOT be in: $out", !out.contains("task $i"))
    }

    // ── Large-list summary ────────────────────────────────────────────────

    @Test
    fun `more than 10 ALL_ACTIVE tasks gets count plus top 3 plus suggestion`() {
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.ALL_ACTIVE)
        val tasks = (1..15).map { task("task $it") }
        val out = router.summariseTaskList(q, tasks)
        // Count present.
        assertTrue("count should be in: $out", out.contains("15"))
        // First three appear.
        assertTrue(out.contains("task 1"))
        assertTrue(out.contains("task 2"))
        assertTrue(out.contains("task 3"))
        // Later tasks are NOT spoken individually.
        assertFalse("'task 5' should not be in: $out", out.contains("task 5"))
        assertFalse("'task 12' should not be in: $out", out.contains("task 12"))
        // Suggestion to filter is present.
        assertTrue("expected a filter suggestion in: $out",
            out.contains("today", ignoreCase = true) ||
            out.contains("overdue", ignoreCase = true))
    }

    @Test
    fun `exactly 10 ALL_ACTIVE tasks keeps the full short-list shape`() {
        // 10 is the boundary — still uses standard "top 5 + plus N more".
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.ALL_ACTIVE)
        val tasks = (1..10).map { task("task $it") }
        val out = router.summariseTaskList(q, tasks)
        assertTrue("expected 'plus 5 more' in: $out", out.contains("plus 5 more"))
    }

    // ── Safety — never contains technical fragments ──────────────────────

    @Test
    fun `summary never contains Java class names or JSON`() {
        val q = TodoistListQueryParser.Query(TodoistListQueryParser.Scope.ALL_ACTIVE)
        val out = router.summariseTaskList(q, listOf(task("regular task")))
        val banned = listOf("Exception", "java.", "kotlin.", "com.jarvis", "{", "}", "HTTP")
        for (b in banned) {
            assertTrue("summary should not contain '$b': $out", !out.contains(b))
        }
    }
}
