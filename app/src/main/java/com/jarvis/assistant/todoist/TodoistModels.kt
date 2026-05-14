package com.jarvis.assistant.todoist

/**
 * Lightweight wire/domain models for the Todoist v1 REST API.
 *
 * Kept deliberately spartan — only the fields Jarvis actually reads from
 * or writes to are modelled.  Anything else the API returns is ignored
 * (Gson tolerates unknown fields when deserialising).  Mirror-creating
 * the full schema is wasted effort and breaks every time Todoist adds a
 * field.
 *
 * Time values follow the Todoist convention:
 *   - `due.date`       — "YYYY-MM-DD" when a date-only due is set.
 *   - `due.datetime`   — RFC-3339 string when a specific time is set
 *                        (e.g. "2026-05-14T19:00:00Z").
 *   - `due.string`     — the natural-language form Todoist uses for
 *                        recurrence ("every monday at 9am").  This is
 *                        what Jarvis sends on create; the server expands
 *                        it into the structured fields.
 *
 * Priority in the public API is 1 (P4 / lowest) through 4 (P1 / highest).
 * The [TodoistPriority] enum maps human-speak ("urgent", "high") to the
 * raw int so parsing stays readable.
 */

/** A Todoist task / item.  Server-shaped — what GET /tasks returns. */
data class TodoistTask(
    val id: String,
    val content: String,
    val description: String? = null,
    val projectId: String? = null,
    val sectionId: String? = null,
    val parentId: String? = null,
    val labels: List<String> = emptyList(),
    /** 1 (lowest) .. 4 (highest); 1 is the default. */
    val priority: Int = 1,
    val due: TodoistDue? = null,
    val url: String? = null,
    val isCompleted: Boolean = false,
    val createdAt: String? = null,
)

data class TodoistProject(
    val id: String,
    val name: String,
    val color: String? = null,
    val parentId: String? = null,
    val isFavorite: Boolean = false,
    val isInboxProject: Boolean = false,
)

data class TodoistSection(
    val id: String,
    val name: String,
    val projectId: String,
    val order: Int = 0,
)

data class TodoistLabel(
    val id: String,
    val name: String,
    val color: String? = null,
    val isFavorite: Boolean = false,
)

/**
 * Due-date payload.  At least one of [date] / [datetime] / [string] must
 * be set when creating.  On read the API populates whichever apply.
 */
data class TodoistDue(
    /** Natural-language string ("tomorrow at 7pm", "every monday"). */
    val string: String? = null,
    /** "YYYY-MM-DD" date-only. */
    val date: String? = null,
    /** RFC-3339 datetime (with timezone) — "2026-05-14T19:00:00Z". */
    val datetime: String? = null,
    /** IANA timezone — "Europe/London".  Optional; server defaults to user TZ. */
    val timezone: String? = null,
    val isRecurring: Boolean = false,
)

/**
 * A separate reminder attached to a task.  Todoist supports relative
 * offsets ("10 minutes before"), absolute datetimes, and location-based
 * triggers.  Jarvis writes the location flavour for "remind me when I
 * get home" once we wire the contextual engine.
 */
data class TodoistReminder(
    val id: String? = null,
    val itemId: String,
    val type: String,           // "relative" | "absolute" | "location"
    val notifyUid: String? = null,
    val minuteOffset: Int? = null,
    val due: TodoistDue? = null,
    val name: String? = null,   // location name
    val locLat: Double? = null,
    val locLong: Double? = null,
    val radius: Int? = null,    // metres
    val locTrigger: String? = null, // "on_enter" | "on_leave"
)

/**
 * Human-speak priority labels mapped to Todoist's int scale.
 *   - URGENT → 4 (P1 in the UI)
 *   - HIGH   → 3 (P2)
 *   - MEDIUM → 2 (P3)
 *   - LOW    → 1 (P4 — the default)
 */
enum class TodoistPriority(val apiValue: Int, val spoken: String) {
    LOW(1,     "low priority"),
    MEDIUM(2,  "medium priority"),
    HIGH(3,    "high priority"),
    URGENT(4,  "urgent");

    companion object {
        fun fromApi(v: Int?): TodoistPriority = when (v) {
            4    -> URGENT
            3    -> HIGH
            2    -> MEDIUM
            else -> LOW
        }

        /** Loose match of "p1"/"p2".../"high"/"urgent"/etc. */
        fun match(token: String): TodoistPriority? {
            val t = token.lowercase().trim()
            return when (t) {
                "p1", "priority 1", "urgent", "asap"     -> URGENT
                "p2", "priority 2", "high"               -> HIGH
                "p3", "priority 3", "medium", "normal"   -> MEDIUM
                "p4", "priority 4", "low"                -> LOW
                else                                     -> null
            }
        }
    }
}
