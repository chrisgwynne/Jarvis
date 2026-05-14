package com.jarvis.assistant.todoist.parse

import com.jarvis.assistant.todoist.TodoistPriority

/**
 * ReminderIntentParser — pure, deterministic intent + slot extraction for
 * reminder / task utterances.
 *
 * **Local-first contract.**  This runs BEFORE OpenClaw, LLM, or any
 * memory retrieval.  Returning [Match] is a firm decision that the
 * runtime will route through the Todoist tool path.  Returning null
 * means "this isn't a reminder/task command" — the runtime falls
 * through to the normal pipeline.
 *
 * Two kinds of utterance are recognised:
 *
 *   **Reminder** — "remind me to take bins out tomorrow at 7"
 *      Starter verbs: remind me [to/about], set a reminder, prompt me,
 *      nudge me, don't let me forget, …
 *
 *   **Task** — "add buy milk to my reminders", "todo call mike"
 *      Starter verbs: add task / todo, add to my list, new task, put X
 *      on my todo, stick X on my list, …
 *
 * Both produce the same [Match] shape with [kind] differentiating.
 * Slot extraction handles:
 *
 *   - Content (the task / reminder body) — everything left after
 *     stripping date/time/recurrence/priority/project/label markers.
 *   - Date / time / recurrence — via [DateTimeExpressionParser].
 *   - Priority — "p1".."p4" / "urgent" / "high" / "medium" / "low".
 *   - Project — "in <name>" / "to <name> list" / "on my <name> list".
 *   - Label — "with label X" / "@X" anywhere.
 *   - Contextual triggers — "when I get home", "next time I open Etsy".
 *   - Repeat hints — "every 10 minutes", "until I mark it done".
 */
object ReminderIntentParser {

    enum class Kind { REMINDER, TASK }

    enum class ContextTriggerType {
        ARRIVE_HOME, LEAVE_HOME, ARRIVE_AT_PLACE, ARRIVE_WORK,
        APP_OPEN, BLUETOOTH_CONNECT, PHONE_PLUGGED_IN, GET_IN_CAR,
        HOME_ASSISTANT_EVENT,
    }

    /** Where & when to trigger a contextual reminder (location, app, …). */
    data class ContextTrigger(
        val type: ContextTriggerType,
        /** Free-form payload — place name, app package label, HA event id. */
        val payload: String? = null,
    )

    /** Repeating-reminder policy ("keep reminding me every 10 minutes"). */
    data class RepeatPolicy(
        val intervalNaturalString: String,   // "every 10 minutes" / "every hour"
        val stopOnComplete: Boolean = true,
    )

    /** A successful parse — every field optional except [kind] and [content]. */
    data class Match(
        val kind: Kind,
        val content: String,
        val date: String? = null,
        val time: String? = null,
        val recurrence: String? = null,
        val priority: TodoistPriority? = null,
        val projectHint: String? = null,
        val labels: List<String> = emptyList(),
        val contextTrigger: ContextTrigger? = null,
        val repeat: RepeatPolicy? = null,
        /** True when the utterance was clearly a reminder/task but no
         *  date/time/recurrence could be resolved — the runtime should
         *  ask "When should I remind you?". */
        val needsTimeFollowUp: Boolean = false,
    )

    // ── Verb anchors ──────────────────────────────────────────────────────

    /** Reminder starter verbs — anything in this list flips kind=REMINDER. */
    private val REMINDER_STARTER_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+|hey\s+|can\s+you\s+|could\s+you\s+)?
        (?:
            remind\s+me(?:\s+to|\s+about)?
          | set\s+(?:a|the|an)?\s*reminder
          | add\s+(?:a|the|an)?\s*reminder
          | make\s+(?:a|the|an)?\s*reminder
          | create\s+(?:a|the|an)?\s*reminder
          | prompt\s+me(?:\s+to)?
          | nudge\s+me(?:\s+to)?
          | (?:don'?t|do\s+not)\s+let\s+me\s+forget(?:\s+to)?
          | remember\s+to\s+remind\s+me
          | make\s+sure\s+i\s+remember(?:\s+to)?
        )
        \b
        """,
    )

    /** Task starter verbs — flips kind=TASK. */
    private val TASK_STARTER_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+|hey\s+)?
        (?:
            add\s+(?:a|the|an)?\s*(?:task|todo|to-?do)
          | create\s+(?:a|the|an)?\s*(?:task|todo|to-?do)
          | make\s+(?:a|the|an)?\s*(?:task|todo|to-?do)
          | new\s+(?:task|todo|to-?do)
          | todo
        )
        \b
        """,
    )

    /**
     * "add X to my reminders/list/todo".  Variants land here when the
     * verb is "add" but the noun "reminder/list/todo" appears later —
     * REMINDER_STARTER_RX wouldn't catch that form.
     */
    /**
     * "add X to my reminders/list/todo" — capturing groups:
     *
     *   group 1 — content (X)
     *   group 2 — optional project qualifier ("work" in "put X on my
     *             work list").  Captured separately so the router can
     *             pass it through as projectHint.
     *   group 3 — the noun phrase ("reminders", "todo list", "list").
     *
     * The project qualifier is OPTIONAL: "add buy milk to my reminders"
     * leaves group 2 blank, "put printer maintenance on my work list"
     * captures group 2 = "work".
     */
    private val ADD_TO_LIST_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:add|put|stick|chuck)\s+(.+?)\s+
        (?:to|on|in)\s+
        (?:my\s+)?
        (?:([a-z][a-z0-9_\- ]{0,20}?)\s+)?
        (reminders?|to-?do(?:s|\s+list)?|task\s+list|list)
        \b
        """,
    )

    /** "I need to remember X", "don't forget X", "put X in Todoist". */
    private val NATURAL_VARIANT_RX = Regex(
        """(?ix)
        ^\s*
        (?:
            i\s+need\s+to\s+remember(?:\s+to)?
          | i\s+need\s+to\s+(?:do|finish|complete)
          | i('?ve|\s+have)\s+got\s+to
          | (?:don'?t|do\s+not)\s+forget(?:\s+to)?
          | put\s+(?:.+?)\s+in\s+todoist
          | stick\s+(?:.+?)\s+on\s+my\s+list
        )
        \b
        """,
    )

    // ── Slot markers stripped from the content ────────────────────────────

    private val PRIORITY_RX = Regex(
        """(?ix)
        \b
        (
            (?:make\s+(?:that|it|this)\s+)?(urgent|asap)
          | p[1-4]
          | priority\s*[1-4]
          | (?:high|medium|low)\s+priority
        )
        \b
        """,
    )

    /** "in <project>" anchored to the end of the utterance. */
    private val PROJECT_RX = Regex(
        """(?ix)
        \s+(?:in|to)\s+(?:my\s+|the\s+)?
        ([a-z][a-z0-9_\- ]{1,40})\s+(?:project|list)\s*$
        """,
    )

    private val LABEL_RX = Regex(
        """(?i)\s+(?:with\s+(?:label|tag)|labeled|labelled)\s+([a-z0-9_\-]+)|@([a-z0-9_\-]+)\b""",
    )

    private val REPEAT_RX = Regex(
        """(?ix)
        \b(
            keep\s+reminding\s+me(?:\s+every\s+\d+\s+(?:minutes?|hours?|days?))?
          | remind\s+me\s+every\s+\d+\s+(?:minutes?|hours?|days?)
          | remind\s+me\s+again\s+in\s+\d+\s+(?:minutes?|hours?|days?)
          | (?:every|each)\s+\d+\s+(?:minutes?|hours?)
          | until\s+i\s+(?:mark\s+it\s+done|finish|complete\s+it)
        )\b
        """,
    )

    private val CONTEXT_TRIGGER_RX = Regex(
        """(?ix)
        \b(?:when|next\s+time)\s+i\s+
        (?:
            (?:get|arrive|come)\s+home
          | leave\s+home
          | (?:get|arrive|come)\s+to\s+work
          | get\s+in\s+(?:the\s+)?car
          | open\s+([a-z][a-z0-9_\-\. ]{1,30})
          | use\s+([a-z][a-z0-9_\-\. ]{1,30})
          | plug\s+(?:my\s+)?phone\s+in
          | connect\s+to\s+bluetooth
          | (?:get|arrive|come)\s+to\s+([a-z][a-z0-9_\-\. ]{1,30})
        )
        """,
    )

    // ── Public entry ──────────────────────────────────────────────────────

    /**
     * Returns a [Match] when [raw] reads like a reminder / task command,
     * null otherwise.  Pure — no I/O.  Pass [nowMs] from a clock injector
     * in tests so date resolution is deterministic.
     */
    fun parse(
        raw: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Match? {
        if (raw.isBlank()) return null
        val lower = raw.lowercase().trim()

        val kind = classifyKind(lower) ?: return null

        // ── Fast path for "add X to my [reminders/list]" ──────────────────
        // ADD_TO_LIST_RX captures the content directly in group 1, so we
        // skip the generic strip pipeline and extract cleanly.  The
        // resulting Match still goes through slot extraction for date /
        // priority / project hints that may follow.
        ADD_TO_LIST_RX.find(lower)?.let { addMatch ->
            val captured = addMatch.groupValues[1].trim()
            val projectHint = addMatch.groupValues[2].trim().takeIf { it.isNotBlank() }
            // Run slot extraction against the captured content only (no
            // verb prefix to confuse the regexes).  The project qualifier
            // (group 2) was captured separately because the noun phrase
            // ("list", "todo") would otherwise swallow it.
            return parseFromContent(captured, kind, nowMs, projectHintOverride = projectHint)
        }

        // 1. Slot scrape — every detected marker carries an IntRange we use
        //    to strip from the content.  We work on a mutable copy.
        var work = lower
        val rangesToStrip = mutableListOf<IntRange>()

        val dt = DateTimeExpressionParser.parse(work, nowMs)
        dt.consumedRange?.let { rangesToStrip += it }

        val priorityMatch = PRIORITY_RX.find(work)
        val priority = priorityMatch?.let { TodoistPriority.match(it.groupValues[1]) }
        priorityMatch?.range?.let { rangesToStrip += it }

        val projectMatch = PROJECT_RX.find(work)
        val projectHint = projectMatch?.groupValues?.getOrNull(1)?.trim()
        projectMatch?.range?.let { rangesToStrip += it }

        val labels = mutableListOf<String>()
        LABEL_RX.findAll(work).forEach { lm ->
            val v = listOf(lm.groupValues[1], lm.groupValues[2]).firstOrNull { it.isNotBlank() }
            if (!v.isNullOrBlank()) labels += v
            rangesToStrip += lm.range
        }

        val repeatMatch = REPEAT_RX.find(work)
        val repeat = repeatMatch?.let {
            ReminderIntentParser.RepeatPolicy(intervalNaturalString = it.value.trim())
        }
        repeatMatch?.range?.let { rangesToStrip += it }

        val ctxMatch = CONTEXT_TRIGGER_RX.find(work)
        val contextTrigger = ctxMatch?.let { contextTriggerFrom(it) }
        ctxMatch?.range?.let { rangesToStrip += it }

        // 2. Strip starter verb itself.
        val starterRange = findStarterRange(lower, kind)
        starterRange?.let { rangesToStrip += it }

        // 3. Build the cleaned content.
        val content = stripRanges(work, rangesToStrip)
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("to ")
            .removePrefix("about ")
            .removePrefix("that ")
            .trim()

        if (content.isBlank() && kind == Kind.TASK && contextTrigger == null) {
            // Task starters like "todo" need at least SOMETHING — no
            // content means it's not a real task command.
            return null
        }

        // 4. Decide if we still need a follow-up question for the time.
        val gotConcreteTime = dt.time != null || dt.isRecurring ||
            (dt.date != null && contextTrigger == null)
        val needsTimeFollowUp = kind == Kind.REMINDER && !gotConcreteTime &&
            contextTrigger == null && repeat == null

        return Match(
            kind              = kind,
            content           = if (content.isBlank()) "(no description)" else content,
            date              = dt.date,
            time              = dt.time,
            recurrence        = if (dt.isRecurring) dt.naturalString else null,
            priority          = priority,
            projectHint       = projectHint?.takeIf { it.isNotBlank() },
            labels            = labels,
            contextTrigger    = contextTrigger,
            repeat            = repeat,
            needsTimeFollowUp = needsTimeFollowUp,
        )
    }

    /** Cheap classifier — used by the runtime to decide whether to route
     *  through Todoist at all, without doing the full parse work. */
    fun looksLikeReminderCommand(raw: String): Boolean {
        val lower = raw.lowercase().trim()
        return REMINDER_STARTER_RX.containsMatchIn(lower) ||
            TASK_STARTER_RX.containsMatchIn(lower) ||
            ADD_TO_LIST_RX.containsMatchIn(lower) ||
            NATURAL_VARIANT_RX.containsMatchIn(lower)
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun classifyKind(lower: String): Kind? = when {
        REMINDER_STARTER_RX.containsMatchIn(lower) -> Kind.REMINDER
        TASK_STARTER_RX.containsMatchIn(lower)     -> Kind.TASK
        ADD_TO_LIST_RX.containsMatchIn(lower)      -> Kind.REMINDER   // "add X to my reminders"
        NATURAL_VARIANT_RX.containsMatchIn(lower)  -> Kind.REMINDER
        else                                       -> null
    }

    private fun findStarterRange(lower: String, kind: Kind): IntRange? {
        val rx = when (kind) {
            Kind.REMINDER -> listOf(REMINDER_STARTER_RX, ADD_TO_LIST_RX, NATURAL_VARIANT_RX)
            Kind.TASK     -> listOf(TASK_STARTER_RX)
        }
        for (r in rx) {
            r.find(lower)?.let { return it.range }
        }
        return null
    }

    private fun stripRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        // Merge overlaps so we never double-delete.
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (r in sorted) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                merged += r
            }
        }
        val sb = StringBuilder()
        var cursor = 0
        for (r in merged) {
            if (cursor < r.first) sb.append(text, cursor, r.first)
            cursor = (r.last + 1).coerceAtMost(text.length)
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    /**
     * Run slot extraction against a pre-stripped content string (the
     * result of unwrapping ADD_TO_LIST_RX's group 1, for example).  No
     * verb prefix to strip — saves one regex pass and avoids re-matching
     * the starter against the content itself.
     */
    private fun parseFromContent(
        content: String,
        kind: Kind,
        nowMs: Long,
        projectHintOverride: String? = null,
    ): Match {
        val rangesToStrip = mutableListOf<IntRange>()
        val dt = DateTimeExpressionParser.parse(content, nowMs)
        dt.consumedRange?.let { rangesToStrip += it }

        val priorityMatch = PRIORITY_RX.find(content)
        val priority = priorityMatch?.let { TodoistPriority.match(it.groupValues[1]) }
        priorityMatch?.range?.let { rangesToStrip += it }

        val labels = mutableListOf<String>()
        LABEL_RX.findAll(content).forEach { lm ->
            val v = listOf(lm.groupValues[1], lm.groupValues[2]).firstOrNull { it.isNotBlank() }
            if (!v.isNullOrBlank()) labels += v
            rangesToStrip += lm.range
        }

        val stripped = stripRanges(content, rangesToStrip)
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "(no description)" }

        return Match(
            kind              = kind,
            content           = stripped,
            date              = dt.date,
            time              = dt.time,
            recurrence        = if (dt.isRecurring) dt.naturalString else null,
            priority          = priority,
            projectHint       = projectHintOverride,
            labels            = labels,
            needsTimeFollowUp = kind == Kind.REMINDER &&
                dt.date == null && dt.time == null && !dt.isRecurring,
        )
    }

    private fun contextTriggerFrom(m: MatchResult): ContextTrigger {
        val v = m.value.lowercase()
        return when {
            v.contains("get in") && v.contains("car")           -> ContextTrigger(ContextTriggerType.GET_IN_CAR)
            v.contains("plug")   && v.contains("phone")         -> ContextTrigger(ContextTriggerType.PHONE_PLUGGED_IN)
            v.contains("connect to bluetooth")                  -> ContextTrigger(ContextTriggerType.BLUETOOTH_CONNECT)
            v.contains("leave home")                             -> ContextTrigger(ContextTriggerType.LEAVE_HOME)
            v.contains("home")                                   -> ContextTrigger(ContextTriggerType.ARRIVE_HOME)
            v.contains("work")                                   -> ContextTrigger(ContextTriggerType.ARRIVE_WORK)
            v.contains("open") -> {
                val app = m.groupValues.getOrNull(1)?.trim().orEmpty()
                ContextTrigger(ContextTriggerType.APP_OPEN, payload = app.ifBlank { null })
            }
            v.contains("use") -> {
                val app = m.groupValues.getOrNull(2)?.trim().orEmpty()
                ContextTrigger(ContextTriggerType.APP_OPEN, payload = app.ifBlank { null })
            }
            else -> {
                val place = m.groupValues.getOrNull(3)?.trim()
                ContextTrigger(ContextTriggerType.ARRIVE_AT_PLACE, payload = place?.ifBlank { null })
            }
        }
    }
}
