package com.jarvis.assistant.todoist.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoistListQueryParserTest {

    /** Local alias — `S.TODAY` reads cleanly inside the assertions below. */
    private object S {
        val TODAY      = TodoistListQueryParser.Scope.TODAY
        val OVERDUE    = TodoistListQueryParser.Scope.OVERDUE
        val UPCOMING   = TodoistListQueryParser.Scope.UPCOMING
        val ALL_ACTIVE = TodoistListQueryParser.Scope.ALL_ACTIVE
        val SEARCH     = TodoistListQueryParser.Scope.SEARCH
        val COUNT      = TodoistListQueryParser.Scope.COUNT
    }

    // ── Today ─────────────────────────────────────────────────────────────

    @Test
    fun `what's on for today`() {
        assertEquals(S.TODAY,
            TodoistListQueryParser.parse("what's on for today")!!.scope)
        assertEquals(S.TODAY,
            TodoistListQueryParser.parse("today's tasks")!!.scope)
        assertEquals(S.TODAY,
            TodoistListQueryParser.parse("what's due today")!!.scope)
        assertEquals(S.TODAY,
            TodoistListQueryParser.parse("what do I have today")!!.scope)
    }

    // ── Overdue ───────────────────────────────────────────────────────────

    @Test
    fun `overdue queries`() {
        listOf(
            "what's overdue",
            "show overdue tasks",
            "anything overdue",
            "any overdue tasks",
        ).forEach {
            assertEquals("'$it' should be OVERDUE", S.OVERDUE,
                TodoistListQueryParser.parse(it)!!.scope)
        }
    }

    // ── Upcoming ──────────────────────────────────────────────────────────

    @Test
    fun `upcoming queries`() {
        assertEquals(S.UPCOMING,
            TodoistListQueryParser.parse("what's coming up")!!.scope)
        assertEquals(S.UPCOMING,
            TodoistListQueryParser.parse("upcoming tasks")!!.scope)
        assertEquals(S.UPCOMING,
            TodoistListQueryParser.parse("what's on this week")!!.scope)
    }

    // ── All-active / generic "show my tasks" ──────────────────────────────

    @Test
    fun `bare list-style phrases now default to TODAY (user-preferred)`() {
        // CHANGED: bare "what are my tasks" / "show my todoist" /
        // "what reminders have I got" no longer dump the entire list.
        // They default to today's items per the user's preferred
        // policy — only an explicit "all my tasks" / "everything"
        // opens up ALL_ACTIVE.  See the "everything qualifier" tests
        // below for the opt-in path.
        assertEquals(S.TODAY,
            TodoistListQueryParser.parse("what are my tasks")!!.scope)
        assertEquals(S.TODAY,
            TodoistListQueryParser.parse("show me my tasks")!!.scope)
        assertEquals(S.TODAY,
            TodoistListQueryParser.parse("list my todos")!!.scope)
        assertEquals(S.TODAY,
            TodoistListQueryParser.parse("show my todoist")!!.scope)
        assertEquals(S.TODAY,
            TodoistListQueryParser.parse("what's on my todo list")!!.scope)
        assertEquals(S.TODAY,
            TodoistListQueryParser.parse("what reminders have i got")!!.scope)
    }

    // ── Count ─────────────────────────────────────────────────────────────

    @Test
    fun `how many tasks routes to COUNT`() {
        assertEquals(S.COUNT,
            TodoistListQueryParser.parse("how many tasks do I have")!!.scope)
        assertEquals(S.COUNT,
            TodoistListQueryParser.parse("how many todos")!!.scope)
    }

    // ── Search ────────────────────────────────────────────────────────────

    @Test
    fun `search captures term`() {
        val q = TodoistListQueryParser.parse("search tasks for printer")!!
        assertEquals(S.SEARCH, q.scope)
        assertEquals("printer", q.searchTerm)
    }

    @Test
    fun `find about captures term`() {
        val q = TodoistListQueryParser.parse("find a task about the dentist")!!
        assertEquals(S.SEARCH, q.scope)
        assertTrue(q.searchTerm!!.contains("dentist"))
    }

    // ── Negatives ─────────────────────────────────────────────────────────

    @Test
    fun `non-query utterances return null`() {
        listOf(
            "what time is it",
            "remind me to call mum",
            "send mike a whatsapp",
            "turn the volume down",
            "open spotify",
        ).forEach {
            assertNull("'$it' should NOT be a list query",
                TodoistListQueryParser.parse(it))
        }
    }

    @Test
    fun `blank returns null`() {
        assertNull(TodoistListQueryParser.parse(""))
        assertNull(TodoistListQueryParser.parse("   "))
    }

    // ── Predicate ─────────────────────────────────────────────────────────

    @Test
    fun `looksLikeListQuery matches every spec form`() {
        listOf(
            "what are my tasks",
            "show me my todoist",
            "what's overdue",
            "today's tasks",
            "what's coming up",
            "how many tasks do i have",
            "search tasks for printer",
        ).forEach {
            assertTrue("'$it' should look like a list query",
                TodoistListQueryParser.looksLikeListQuery(it))
        }
        listOf(
            "remind me to take bins out",
            "send mike a whatsapp",
            "what time is it",
        ).forEach {
            assertFalse("'$it' should NOT look like a list query",
                TodoistListQueryParser.looksLikeListQuery(it))
        }
    }

    // ── Priority ordering ─────────────────────────────────────────────────

    @Test
    fun `search wins over generic all-tasks when both could match`() {
        // "find tasks about printer" — SEARCH_RX wins, ALL_ACTIVE_RX
        // doesn't match this phrasing.
        val q = TodoistListQueryParser.parse("find tasks about printer")!!
        assertEquals(S.SEARCH, q.scope)
    }

    @Test
    fun `overdue wins over ALL_ACTIVE when both keyword classes appear`() {
        // "show my overdue tasks" — OVERDUE_RX wins because it's
        // evaluated before ALL_ACTIVE in parse().
        val q = TodoistListQueryParser.parse("show my overdue tasks")!!
        assertEquals(S.OVERDUE, q.scope)
    }

    // ── Regression: contraction expansion forms still resolve ───────────
    // (Policy: they now DEFAULT to TODAY scope — see today-default
    // tests below.  These tests only assert the parser resolves the
    // form; scope assertions live with the policy tests.)

    @Test
    fun `what is my tasks parses (post-normalization)`() {
        assertNotNull(TodoistListQueryParser.parse("what is my tasks"))
    }

    @Test
    fun `whats my tasks parses`() {
        assertNotNull(TodoistListQueryParser.parse("what's my tasks"))
    }

    @Test
    fun `whats my todo list parses`() {
        assertNotNull(TodoistListQueryParser.parse("what's my todo list"))
    }

    @Test
    fun `what reminders have I got parses`() {
        assertNotNull(TodoistListQueryParser.parse("what reminders have i got"))
    }

    // ── Regression: "today" qualifier upgrades ALL_ACTIVE → TODAY ────────

    @Test
    fun `whats on my todo list today routes to TODAY`() {
        // "what's on my todo list today" — ALL_TASKS_RX matches the
        // first half, but the standalone "today" qualifier should
        // upgrade the scope.  Previously this returned ALL_ACTIVE
        // which dumped the entire 50-task list at the user.
        val q = TodoistListQueryParser.parse("what's on my todo list today")
        assertEquals(S.TODAY, q!!.scope)
    }

    @Test
    fun `whats my tasks today routes to TODAY`() {
        val q = TodoistListQueryParser.parse("what's my tasks today")
        assertEquals(S.TODAY, q!!.scope)
    }

    @Test
    fun `show me my tasks for today routes to TODAY`() {
        val q = TodoistListQueryParser.parse("show me my tasks for today")
        assertEquals(S.TODAY, q!!.scope)
    }

    // ── Today-default policy ──────────────────────────────────────────────

    @Test
    fun `what are my tasks DEFAULTS to TODAY`() {
        // User preference: a bare "what are my tasks" answers with
        // today only, never the full 50-item list.
        assertEquals(S.TODAY, TodoistListQueryParser.parse("what are my tasks")!!.scope)
        assertEquals(S.TODAY, TodoistListQueryParser.parse("what is my tasks")!!.scope)
        assertEquals(S.TODAY, TodoistListQueryParser.parse("what's my tasks")!!.scope)
        assertEquals(S.TODAY, TodoistListQueryParser.parse("show me my tasks")!!.scope)
        assertEquals(S.TODAY, TodoistListQueryParser.parse("list my todos")!!.scope)
        assertEquals(S.TODAY, TodoistListQueryParser.parse("what's on my todo list")!!.scope)
    }

    @Test
    fun `everything qualifier opts into ALL_ACTIVE`() {
        // Only an explicit "all" / "everything" / "whole" / "entire"
        // qualifier inside a recognised list-style phrase opens up
        // ALL_ACTIVE.  Each phrase here both (a) hits ALL_TASKS_RX
        // and (b) carries an EVERYTHING_QUALIFIER_RX hit.
        assertEquals(S.ALL_ACTIVE,
            TodoistListQueryParser.parse("show me all my tasks")!!.scope)
        assertEquals(S.ALL_ACTIVE,
            TodoistListQueryParser.parse("read my entire todo list")!!.scope)
        assertEquals(S.ALL_ACTIVE,
            TodoistListQueryParser.parse("list all my reminders")!!.scope)
        assertEquals(S.ALL_ACTIVE,
            TodoistListQueryParser.parse("show me my whole todo list")!!.scope)
        assertEquals(S.ALL_ACTIVE,
            TodoistListQueryParser.parse("what are all my tasks")!!.scope)
    }

    @Test
    fun `explicit today still wins over everything`() {
        // If the user says "today" the scope is TODAY even when "all"
        // also appears — today wins because they were specific.
        val q = TodoistListQueryParser.parse("show me all my tasks for today")
        assertEquals(S.TODAY, q!!.scope)
    }
}
