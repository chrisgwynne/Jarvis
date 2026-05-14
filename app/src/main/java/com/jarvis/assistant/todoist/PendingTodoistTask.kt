package com.jarvis.assistant.todoist

import com.jarvis.assistant.todoist.parse.ReminderIntentParser

/**
 * PendingTodoistTask — a half-built reminder/task held while the runtime
 * waits for a follow-up answer ("When should I remind you?", "Which
 * project?").  Mirrors the shape of
 * [com.jarvis.assistant.tools.device.messaging.PendingMessageIntent].
 *
 * One pending task per session.  The runtime owns the single slot and
 * drops it after [TTL_MS] or after a successful execution.
 */
data class PendingTodoistTask(
    val kind: ReminderIntentParser.Kind,
    val content: String,
    val date: String? = null,
    val time: String? = null,
    val recurrence: String? = null,
    val priority: TodoistPriority? = null,
    val projectHint: String? = null,
    val labels: List<String> = emptyList(),
    val contextTrigger: ReminderIntentParser.ContextTrigger? = null,
    val repeat: ReminderIntentParser.RepeatPolicy? = null,
    /** Which slot we last asked about, so the next-turn merge can target it. */
    val awaitingSlot: AwaitingSlot,
    val createdMs: Long,
    val expiresAtMs: Long,
) {
    enum class AwaitingSlot {
        TIME, LABEL, PROJECT, RECURRENCE,
        /**
         * The user said the verb (e.g. "create a task") but no content —
         * we're waiting for the next utterance to BE the content.  Used
         * to plug the `[INVALID_REMOTE_ROUTE]` regression where bare
         * "Create a task" / "Add a todo" fell through to OpenClaw because
         * the strict parser rejects an empty content.
         */
        CONTENT,
        NONE
    }

    companion object {
        /** 60 s window — long enough for a natural follow-up, short
         *  enough that a stale stash never bleeds into the next session. */
        const val TTL_MS: Long = 60_000L

        fun fromMatch(
            m: ReminderIntentParser.Match,
            awaitingSlot: AwaitingSlot,
            nowMs: Long = System.currentTimeMillis(),
        ) = PendingTodoistTask(
            kind            = m.kind,
            content         = m.content,
            date            = m.date,
            time            = m.time,
            recurrence      = m.recurrence,
            priority        = m.priority,
            projectHint     = m.projectHint,
            labels          = m.labels,
            contextTrigger  = m.contextTrigger,
            repeat          = m.repeat,
            awaitingSlot    = awaitingSlot,
            createdMs       = nowMs,
            expiresAtMs     = nowMs + TTL_MS,
        )
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    /**
     * Returns true when the pending task can be created right now — i.e.
     * it has at least content + either a date/time or a recurrence or a
     * contextual trigger.  Tasks (vs reminders) are ready as soon as
     * content is non-blank.
     */
    val isReady: Boolean get() {
        if (content.isBlank()) return false
        if (kind == ReminderIntentParser.Kind.TASK) return true
        return date != null || time != null || recurrence != null ||
            contextTrigger != null || repeat != null
    }
}
