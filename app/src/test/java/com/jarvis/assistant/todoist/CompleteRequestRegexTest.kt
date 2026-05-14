package com.jarvis.assistant.todoist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CompleteRequestRegexTest — pin the bulk + by-name regexes used by
 * [TodoistReminderRouter.handleCompleteRequest].  These are the
 * predicates that translate a user reply like
 *   "complete those tasks"  → bulk
 *   "mark pick up Mike done" → by-name "pick up Mike"
 *   "complete that"         → NotApplicable (existing edit path owns it)
 */
class CompleteRequestRegexTest {

    private val bulkRx = Regex(
        """^(?:please\s+)?
           (?:complete|mark|tick(?:\s+off)?|cross\s+off|finish|close)\s+
           (?:(?:all|those|these|the|my|today'?s|today)\s+){0,3}
           (?:tasks?|todos?|reminders?|to-?dos?|everything|the\s+list)
           (?:\s+for\s+today)?
           (?:\s+as\s+(?:done|complete|finished|off))?
           \s*[.?!]?\s*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
    )
    private val byNameRx = Regex(
        """^(?:please\s+)?
           (?:complete|mark|tick(?:\s+off)?|cross\s+off|finish|close)\s+
           (?:the\s+|my\s+)?
           (.+?)
           (?:\s+(?:as\s+(?:done|complete|finished)|task|todo|reminder|off))?
           \s*[.?!]?\s*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
    )

    @Test fun `bulk phrasings match`() {
        listOf(
            "complete those tasks",
            "complete all my tasks",
            "complete today's tasks",
            "mark all the tasks as done",       // by-name matches too, but bulk wins in caller
            "finish today's tasks",
            "close the list",
            "tick off everything for today",
        ).forEach {
            assertTrue("'$it' should match bulkRx",
                bulkRx.containsMatchIn(it))
        }
    }

    @Test fun `by-name phrasings extract content group`() {
        val cases = mapOf(
            "complete pick up Mike"            to "pick up Mike",
            "mark take the bins out as done"   to "take the bins out",
            "finish call mum"                  to "call mum",
            "close the dentist appointment"    to "dentist appointment",
        )
        for ((utterance, expected) in cases) {
            val m = byNameRx.find(utterance)
                ?: error("'$utterance' should match byNameRx")
            val captured = m.groupValues[1].trim()
            assertTrue(
                "'$utterance' captured '$captured' (expected to contain '$expected')",
                captured.contains(expected, ignoreCase = true),
            )
        }
    }

    @Test fun `anchor-only forms are rejected by the caller`() {
        // The caller filters out "that" / "it" — verify the regex
        // captures them in group 1 (so we can filter against the set).
        val m = byNameRx.find("complete that")
            ?: error("'complete that' should match byNameRx")
        assertTrue(m.groupValues[1].trim() == "that")
    }

    @Test fun `non-completion utterances do not match`() {
        listOf(
            "what are my tasks",
            "add a task",
            "create a task",
            "remind me to call mum",
            "send a whatsapp to mike",
        ).forEach {
            assertFalse("'$it' must NOT match bulkRx",
                bulkRx.containsMatchIn(it))
        }
    }
}
