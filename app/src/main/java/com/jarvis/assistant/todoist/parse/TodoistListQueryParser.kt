package com.jarvis.assistant.todoist.parse

/**
 * TodoistListQueryParser — recognises read-only "show me my tasks"
 * style utterances and produces a [Query] describing which slice of
 * the user's Todoist list to fetch.
 *
 * Strictly local — no LLM, no remote routing.  Returns null when the
 * utterance isn't a recognised query so the caller falls through to
 * the normal pipeline.
 *
 * Why this exists: without query recognition the LLM fallback was
 * answering "I don't have a task list feature" — the user had Todoist
 * connected but Jarvis didn't realise the LIST-side of the
 * integration existed.  This parser closes the gap.
 *
 * Supported forms:
 *
 *   - "what are my tasks" / "what's on my list" / "show my todoist"
 *   - "list my tasks" / "list my todos" / "list my reminders"
 *   - "what's on for today" / "today's tasks" / "what's due today"
 *   - "what's overdue" / "show overdue tasks" / "anything overdue"
 *   - "what's coming up" / "upcoming tasks" / "what's coming this week"
 *   - "search tasks for <X>" / "find task about <X>"
 *   - "how many tasks do I have"
 */
object TodoistListQueryParser {

    enum class Scope { ALL_ACTIVE, TODAY, OVERDUE, UPCOMING, SEARCH, COUNT }

    data class Query(val scope: Scope, val searchTerm: String? = null)

    // ── Patterns ──────────────────────────────────────────────────────────

    private val TODAY_RX = Regex(
        """(?ix)
        \b(?:
            (?:what(?:'?s|\s+is)\s+)?(?:on\s+for\s+today|due\s+today|today'?s\s+(?:tasks?|todos?|reminders?|list)|on\s+today)
          | (?:what\s+(?:do\s+i\s+have\s+)?today)
          | (?:tasks?\s+(?:for\s+)?today)
        )\b
        """,
    )

    private val OVERDUE_RX = Regex(
        """(?ix)
        \b(?:
            overdue
          | what(?:'?s|\s+is)?\s+overdue
          | anything\s+overdue
          | tasks?\s+(?:that\s+are\s+)?overdue
          | (?:past|late)\s+tasks?
        )\b
        """,
    )

    private val UPCOMING_RX = Regex(
        """(?ix)
        \b(?:
            upcoming
          | (?:what(?:'?s|\s+is)?\s+)?coming\s+up(?:\s+this\s+week)?
          | tasks?\s+(?:for\s+|coming\s+)(?:this\s+|next\s+)?week
          | what(?:'?s|\s+is)?\s+(?:on|due)\s+this\s+week
        )\b
        """,
    )

    /**
     * "All active tasks" forms.  After TranscriptNormalizer expands
     * common contractions (`whats` → `what is`), both "what's my
     * tasks" and "what are my tasks" land here.  We accept the full
     * grammatical set:
     *
     *   what is/are/'s my tasks/todos/reminders
     *   what's on my todo list / task list / reminders list / todoist
     *   show me my tasks / list my todos / read my reminders
     *   what tasks have I got / what reminders have I got
     */
    private val ALL_TASKS_RX = Regex(
        """(?ix)
        \b(?:
            what(?:'?s|\s+is|\s+are)\s+(?:on\s+)?(?:my\s+)?(?:to-?do\s*list|task\s*list|todoist|reminders?\s*list)
          | what(?:'?s|\s+is|\s+are)\s+(?:all\s+)?my\s+(?:entire\s+|whole\s+|complete\s+|full\s+)?(?:tasks?|todos?|reminders?)
          | (?:show|list|read)\s+(?:me\s+)?
              (?:
                  all\s+(?:of\s+)?my\s+(?:entire\s+|whole\s+|complete\s+|full\s+)?
                | my\s+(?:entire\s+|whole\s+|complete\s+|full\s+)?
                | every\s+
              )?
              (?:tasks?|todos?|reminders?|todoist|to-?do\s*list)
          | (?:what\s+(?:reminders|tasks|todos)\s+have\s+i\s+got)
          | (?:what\s+have\s+i\s+got\s+(?:to\s+do|on))
        )\b
        """,
    )

    /**
     * Standalone "today" qualifier — when a list-style utterance also
     * contains "today", we prefer the TODAY scope over ALL_ACTIVE.
     */
    private val TODAY_QUALIFIER_RX = Regex("""\btoday\b""", RegexOption.IGNORE_CASE)

    /**
     * Explicit "everything / all my tasks" qualifier.  When present in
     * an ALL_TASKS-style phrase, we honour the request and return
     * everything.  When ABSENT, ALL_TASKS phrasings default to TODAY
     * scope — user-preferred behaviour: "what are my tasks" answers
     * with today's, not the entire list of 50.
     */
    private val EVERYTHING_QUALIFIER_RX = Regex(
        """\b(?:all|everything|every|whole|entire|complete|full)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val COUNT_RX = Regex(
        """(?ix)
        \b(?:
            how\s+many\s+(?:tasks?|todos?|reminders?|things\s+to\s+do)
          | (?:do\s+i\s+have\s+)?many\s+tasks?
        )\b
        """,
    )

    /**
     * Search form: "search tasks for X" / "find task about X" /
     * "find a task that mentions X".  Captures the search term in
     * group 1.
     */
    private val SEARCH_RX = Regex(
        """(?ix)
        \b(?:search|find)\s+(?:a\s+|the\s+)?(?:tasks?|todos?|reminders?)\s+
        (?:for|about|mentioning|with|containing|that\s+(?:mentions?|contains?))\s+
        (.+?)
        \s*[.?!]?\s*$
        """,
    )

    /** Cheap predicate the runtime can use to short-circuit. */
    fun looksLikeListQuery(raw: String): Boolean {
        val lower = raw.lowercase().trim()
        if (lower.isBlank()) return false
        return TODAY_RX.containsMatchIn(lower) ||
            OVERDUE_RX.containsMatchIn(lower) ||
            UPCOMING_RX.containsMatchIn(lower) ||
            ALL_TASKS_RX.containsMatchIn(lower) ||
            COUNT_RX.containsMatchIn(lower) ||
            SEARCH_RX.containsMatchIn(lower)
    }

    /** Full parse — return the resolved [Query] or null. */
    fun parse(raw: String): Query? {
        val lower = raw.lowercase().trim()
        if (lower.isBlank()) return null

        // Order matters: more specific scopes (search / overdue / today /
        // upcoming) before the catch-all ALL_TASKS form so "search tasks
        // for printer" doesn't get rewritten to "all tasks".
        SEARCH_RX.find(lower)?.let { m ->
            val term = m.groupValues[1].trim()
            if (term.isNotBlank()) return Query(Scope.SEARCH, term)
        }
        if (OVERDUE_RX.containsMatchIn(lower))  return Query(Scope.OVERDUE)
        if (TODAY_RX.containsMatchIn(lower))    return Query(Scope.TODAY)
        if (UPCOMING_RX.containsMatchIn(lower)) return Query(Scope.UPCOMING)
        if (COUNT_RX.containsMatchIn(lower))    return Query(Scope.COUNT)
        // User-preferred policy: ALL_TASKS_RX phrasings DEFAULT to
        // today-only.  The user has to explicitly say "everything" /
        // "all my tasks" / "the whole list" to opt into the full
        // active list — otherwise "what are my tasks" answers with
        // today's items.
        if (ALL_TASKS_RX.containsMatchIn(lower)) {
            return when {
                TODAY_QUALIFIER_RX.containsMatchIn(lower)      -> Query(Scope.TODAY)
                EVERYTHING_QUALIFIER_RX.containsMatchIn(lower) -> Query(Scope.ALL_ACTIVE)
                else                                           -> Query(Scope.TODAY)
            }
        }
        return null
    }
}
