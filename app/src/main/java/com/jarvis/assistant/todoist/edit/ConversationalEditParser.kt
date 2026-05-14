package com.jarvis.assistant.todoist.edit

import com.jarvis.assistant.todoist.TodoistPriority
import com.jarvis.assistant.todoist.parse.DateTimeExpressionParser

/**
 * ConversationalEditParser — recognises follow-up edits that refer to
 * the most recently created / referenced task ("that") and produces an
 * [Edit] describing the requested change.
 *
 * Strictly local — no LLM.  Returns null when the utterance isn't a
 * recognisable edit; the caller (router) then falls through to the
 * normal pipeline.
 *
 * Supported forms:
 *   - "move that to tomorrow"
 *   - "actually make it 9pm" / "actually 9pm"
 *   - "make that urgent" / "p1"
 *   - "put that in work"
 *   - "add label workshop"
 *   - "delete that" / "remove that"
 *   - "mark that done" / "complete that"
 *   - "remind me again in 10 minutes"
 *   - "snooze that 15 minutes"
 */
object ConversationalEditParser {

    sealed class Edit {
        data class Reschedule(
            val date: String? = null,
            val time: String? = null,
            val recurrence: String? = null,
        ) : Edit() {
            val isEmpty get() = date == null && time == null && recurrence == null
        }
        data class SetPriority(val priority: TodoistPriority) : Edit()
        data class MoveProject(val projectHint: String) : Edit()
        data class AddLabel(val label: String) : Edit()
        object Delete : Edit()
        object Complete : Edit()
        data class Snooze(val minutes: Int) : Edit()
    }

    // Anchor: edits MUST reference the prior task somehow.  Recognised
    // anchors:
    //   - "that" / "it" / "this (one)"
    //   - leading "actually" — "actually 9pm"
    //   - "remind me again" — implicit self-reference to the last task
    //   - "snooze" — implicit self-reference to the last task
    // Without ANY anchor we refuse to claim the utterance.
    private val ANCHOR_RX = Regex(
        """(?ix)
        \b(that|it|this(?:\s+one)?)\b
        | ^\s*actually\b
        | \bremind\s+me\s+again\b
        | ^\s*snooze\b
        """,
    )

    // ── Specific intents ─────────────────────────────────────────────────

    private val MOVE_RX = Regex(
        """(?ix)
        \b(?:move|reschedule|push|shift)\s+(?:that|it|this(?:\s+one)?)\s+
        (?:to\s+)?(.+)$
        """,
    )

    private val ACTUALLY_TIME_RX = Regex(
        """(?ix)
        ^\s*actually\s+(?:make\s+(?:that|it)\s+)?(.+)$
        """,
    )

    private val PRIORITY_PHRASE_RX = Regex(
        """(?ix)
        \b(?:make\s+(?:that|it|this)\s+)
        (urgent|asap|high|medium|low|priority\s*[1-4]|p[1-4])
        \b
        """,
    )

    private val MOVE_PROJECT_RX = Regex(
        """(?ix)
        \b(?:put|move)\s+(?:that|it|this)\s+(?:in|to|into)\s+
        (?:my\s+)?
        ([a-z][a-z0-9_\- ]{0,30}?)
        (?:\s+(?:project|list))?
        \s*$
        """,
    )

    private val ADD_LABEL_RX = Regex(
        """(?ix)
        \b(?:add|tag\s+with|label\s+with)\s+(?:the\s+)?
        ([a-z0-9_\-]+)\s+label
        |
        \b(?:add|tag\s+with|label\s+with)\s+(?:the\s+)?label\s+
        ([a-z0-9_\-]+)
        """,
    )

    private val DELETE_RX = Regex(
        """(?ix)
        \b(?:delete|remove|cancel|drop|forget)\s+(?:that|it|this)\b
        """,
    )

    private val COMPLETE_RX = Regex(
        """(?ix)
        \b(?:mark|complete|finish|tick\s+off|cross\s+off)\s+
        (?:that|it|this)\s+
        (?:as\s+)?
        (?:done|complete|finished|off)?
        """,
    )

    private val SNOOZE_RX = Regex(
        """(?ix)
        \b(?:snooze|nudge|remind\s+me\s+again)\s+
        (?:that|it|this)?\s*
        (?:in\s+)?
        (\d+)\s+(minute|minutes|min|mins|hour|hours|hr|hrs)
        \b
        """,
    )

    // ── Public entry ──────────────────────────────────────────────────────

    /** True when the utterance even *looks* like an edit referring to "that". */
    fun looksLikeEdit(raw: String): Boolean {
        val lower = raw.lowercase().trim()
        if (lower.isBlank()) return false
        if (!ANCHOR_RX.containsMatchIn(lower)) return false
        return MOVE_RX.containsMatchIn(lower) ||
            ACTUALLY_TIME_RX.containsMatchIn(lower) ||
            PRIORITY_PHRASE_RX.containsMatchIn(lower) ||
            MOVE_PROJECT_RX.containsMatchIn(lower) ||
            ADD_LABEL_RX.containsMatchIn(lower) ||
            DELETE_RX.containsMatchIn(lower) ||
            COMPLETE_RX.containsMatchIn(lower) ||
            SNOOZE_RX.containsMatchIn(lower)
    }

    /**
     * Parse [raw] into an [Edit].  Returns null when the utterance
     * isn't a recognisable edit OR there's no anchor word ("that"/"it"
     * /"actually") — without an anchor we refuse to silently edit
     * something the user didn't reference.
     */
    fun parse(
        raw: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Edit? {
        val lower = raw.lowercase().trim()
        if (lower.isBlank()) return null
        if (!ANCHOR_RX.containsMatchIn(lower)) return null

        // Hard-anchor edits first.
        DELETE_RX.find(lower)?.let { return Edit.Delete }
        COMPLETE_RX.find(lower)?.let { return Edit.Complete }
        SNOOZE_RX.find(lower)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return@let
            val unit = it.groupValues[2]
            val minutes = if (unit.startsWith("hour") || unit == "hr" || unit == "hrs") n * 60 else n
            return Edit.Snooze(minutes)
        }

        PRIORITY_PHRASE_RX.find(lower)?.let {
            val p = TodoistPriority.match(it.groupValues[1])
            if (p != null) return Edit.SetPriority(p)
        }

        // Reschedule paths run BEFORE MoveProject so "move that to
        // tomorrow" doesn't mis-route as MoveProject(tomorrow).  Both
        // MOVE_RX and ACTUALLY_TIME_RX try DateTimeExpressionParser on
        // the captured tail — only when that yields nothing does the
        // MoveProject fallback get a chance.
        MOVE_RX.find(lower)?.let {
            val rest = it.groupValues[1].trim()
            val dt = DateTimeExpressionParser.parse(rest, nowMs)
            val edit = Edit.Reschedule(
                date = dt.date,
                time = dt.time,
                recurrence = if (dt.isRecurring) dt.naturalString else null,
            )
            if (!edit.isEmpty) return edit
        }

        ACTUALLY_TIME_RX.find(lower)?.let {
            val rest = it.groupValues[1].trim()
            val withAt = if (Regex("^\\d").containsMatchIn(rest)) "at $rest" else rest
            val dt = DateTimeExpressionParser.parse(withAt, nowMs)
            val edit = Edit.Reschedule(
                date = dt.date,
                time = dt.time,
                recurrence = if (dt.isRecurring) dt.naturalString else null,
            )
            if (!edit.isEmpty) return edit
        }

        MOVE_PROJECT_RX.find(lower)?.let {
            val proj = it.groupValues[1].trim()
            if (proj.isNotBlank()) return Edit.MoveProject(proj)
        }

        ADD_LABEL_RX.find(lower)?.let {
            val label = listOf(it.groupValues[1], it.groupValues[2])
                .firstOrNull { v -> v.isNotBlank() }
            if (!label.isNullOrBlank()) return Edit.AddLabel(label)
        }

        return null
    }
}
