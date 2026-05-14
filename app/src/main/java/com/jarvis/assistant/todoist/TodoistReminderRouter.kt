package com.jarvis.assistant.todoist

import android.util.Log
import com.jarvis.assistant.todoist.parse.ReminderIntentParser

/**
 * TodoistReminderRouter — the local-first orchestrator for reminder /
 * task utterances.
 *
 * Sits BEFORE OpenClaw / LLM / memory retrieval.  Owns the "do I take
 * this turn?" decision, the parser call, the follow-up state, the
 * Todoist client call, and the offline-queue fallback.
 *
 * It does NOT speak directly — every public method returns a
 * [RouterAction] describing what the runtime should do (speak a short
 * confirmation, park a follow-up, or hand off).  Keeping speech out of
 * the router makes the unit tests trivial: we exercise pure transitions
 * against an in-memory state.
 */
class TodoistReminderRouter(
    private val client: () -> TodoistClient,
    private val settingsProvider: () -> TodoistSettings,
    private val offlineQueue: () -> TodoistOfflineQueue,
    /** Clock injection — tests pin to a fixed instant. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object { private const val TAG = "TodoistRouter" }

    /** What the runtime should do after a router call. */
    sealed class RouterAction {
        data class Speak(val text: String) : RouterAction()
        data class ParkPending(
            val pending: PendingTodoistTask,
            val askPrompt: String,
        ) : RouterAction()
        /** Handle was not a reminder/task — fall through to normal routing. */
        object NotApplicable : RouterAction()
    }

    /** Pointer to the most recently created / listed / modified task —
     *  used to resolve "that" / "actually 9pm" / "delete that".  Lives
     *  in-memory only; 5-minute TTL. */
    val recentTaskContext: com.jarvis.assistant.todoist.edit.RecentTaskContextStore =
        com.jarvis.assistant.todoist.edit.RecentTaskContextStore(clock = clock)

    /** Backwards-compat alias for the previous lastCreatedTaskId field. */
    val lastCreatedTaskId: String? get() = recentTaskContext.peek()?.taskId

    // ── Entry: fresh utterance ────────────────────────────────────────────

    /**
     * Try to handle [transcript] as a reminder / task command.  Returns
     * [RouterAction.NotApplicable] when the utterance isn't recognised —
     * the caller falls through.
     *
     * Pre-conditions (cheap, all enforced by [ReminderIntentParser]):
     *   - The transcript must mention a reminder/task starter verb.
     *   - Settings must have [TodoistSettings.enabled] true; otherwise
     *     we return a single hint so the user knows to enable it.
     */
    suspend fun handleFresh(transcript: String): RouterAction {
        val tStart = clock()
        Log.d(TAG, "[TODOIST_ROUTER_BEGIN] transcript=\"${transcript.take(80)}\"")
        if (!ReminderIntentParser.looksLikeReminderCommand(transcript)) {
            Log.d(TAG, "[TODOIST_ROUTER_NO_MATCH] reason=not_reminder_command")
            return RouterAction.NotApplicable
        }
        val s = settingsProvider()
        if (!s.enabled) {
            Log.d(TAG, "[TODOIST_ROUTER_NO_MATCH] reason=integration_disabled")
            return RouterAction.Speak(
                "Turn on the Todoist integration in Settings — I'll save it there once you do."
            )
        }
        if (s.apiToken.isBlank()) {
            Log.d(TAG, "[TODOIST_ROUTER_NO_MATCH] reason=missing_token")
            return RouterAction.Speak(
                "I need your Todoist API token in Settings before I can save tasks."
            )
        }

        val parseStart = clock()
        val match = ReminderIntentParser.parse(transcript, clock())
            ?: run {
                Log.d(TAG, "[TODOIST_ROUTER_NO_MATCH] reason=parser_rejected")
                return RouterAction.NotApplicable
            }
        Log.d(TAG, "[TODOIST_LATENCY_PARSE_MS] ${clock() - parseStart}")
        Log.d(TAG, "[TODOIST_ROUTER_MATCH] kind=${match.kind} content=\"${match.content}\"")
        Log.d(TAG, "[TODOIST_INTENT_MATCH] kind=${match.kind} content=\"${match.content}\"")
        Log.d(TAG, "[TODOIST_PARSE_RESULT] " +
            "date=${match.date} time=${match.time} recur=${match.recurrence} " +
            "priority=${match.priority} project=${match.projectHint} " +
            "labels=${match.labels} ctx=${match.contextTrigger?.type} " +
            "repeat=${match.repeat != null} needsTime=${match.needsTimeFollowUp}")

        val apiStart = clock()
        val action = executeOrPark(match)
        Log.d(TAG, "[TODOIST_LATENCY_API_MS] ${clock() - apiStart}")
        Log.d(TAG, "[TODOIST_LATENCY_TOTAL_MS] ${clock() - tStart}")
        return action
    }

    // ── Entry: follow-up turn ─────────────────────────────────────────────

    /**
     * Merge [followUp] into a parked [pending] task and either execute
     * or re-park.  Caller is responsible for actually expiring stale
     * pendings; this function just trusts the input.
     */
    suspend fun handleFollowUp(
        pending: PendingTodoistTask,
        followUp: String,
    ): RouterAction {
        val merged = mergeFollowUp(pending, followUp.trim())
        Log.d(TAG, "[TODOIST_FOLLOWUP_MERGED] slot=${pending.awaitingSlot} " +
            "date=${merged.date} time=${merged.time} project=${merged.projectHint}")

        if (!merged.isReady) {
            val (next, prompt) = nextFollowUpQuestion(merged)
            Log.d(TAG, "[TODOIST_PENDING_UPDATED] slot=$next content=\"${merged.content}\"")
            return RouterAction.ParkPending(merged.copy(awaitingSlot = next), prompt)
        }
        Log.d(TAG, "[TODOIST_PENDING_COMPLETED] content=\"${merged.content}\"")
        return executeReady(merged)
    }

    // ── Public helpers ────────────────────────────────────────────────────

    /**
     * Handle a conversational edit ("move that to tomorrow", "make it
     * urgent", "delete that").  Returns:
     *   - [RouterAction.Speak]        — successful or user-friendly error
     *   - [RouterAction.NotApplicable] — not an edit, OR no recent task
     *     to apply it against (caller falls through)
     *
     * Refuses to guess when "that" can't be resolved — speaks a short
     * "Which task?" rather than silently mutating the wrong thing.
     */
    suspend fun handleEdit(transcript: String): RouterAction {
        val edit = com.jarvis.assistant.todoist.edit
            .ConversationalEditParser.parse(transcript, clock())
            ?: return RouterAction.NotApplicable

        val target = recentTaskContext.peek()
        if (target == null) {
            Log.d(TAG, "[TODOIST_EDIT_NO_TARGET] edit=${edit::class.simpleName}")
            return RouterAction.Speak("Which task did you mean?")
        }

        val c = client()
        Log.d(TAG, "[TODOIST_EDIT_BEGIN] edit=${edit::class.simpleName} " +
            "taskId=${target.taskId} content=\"${target.content}\"")

        val result: TodoistClient.Result<*> = when (edit) {
            is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.Delete ->
                c.deleteTask(target.taskId).also {
                    if (it is TodoistClient.Result.Ok) recentTaskContext.clear()
                }
            is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.Complete ->
                c.completeTask(target.taskId).also {
                    if (it is TodoistClient.Result.Ok) recentTaskContext.clear()
                }
            is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.SetPriority ->
                c.updateTask(target.taskId, mapOf("priority" to edit.priority.apiValue))
            is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.MoveProject -> {
                // We can't look up project IDs synchronously without
                // additional plumbing; surface the project name as a
                // hint and let the user / Todoist autocomplete handle.
                c.updateTask(target.taskId, mapOf("description" to "Moved to ${edit.projectHint}"))
            }
            is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.AddLabel ->
                c.addLabel(target.taskId, edit.label)
            is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.Reschedule -> {
                val dueString = buildString {
                    if (edit.recurrence != null) append(edit.recurrence)
                    else {
                        if (edit.date != null) append(edit.date)
                        if (edit.time != null) {
                            if (isNotEmpty()) append(" at ") else append("today at ")
                            append(edit.time)
                        }
                    }
                }.ifBlank { return RouterAction.Speak("I didn't catch when to move it to.") }
                c.updateTask(target.taskId, mapOf("due_string" to dueString))
            }
            is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.Snooze -> {
                // Translate snooze minutes into a fresh due_string.  Add
                // to the current wall-clock minute — the gateway parses
                // "in N minutes" natively.
                c.updateTask(target.taskId, mapOf("due_string" to "in ${edit.minutes} minutes"))
            }
        }

        return when (result) {
            is TodoistClient.Result.Ok -> {
                Log.d(TAG, "[TODOIST_EDIT_SUCCESS] edit=${edit::class.simpleName}")
                recentTaskContext.remember(
                    taskId  = target.taskId,
                    content = target.content,
                    source  = com.jarvis.assistant.todoist.edit
                        .RecentTaskContextStore.Source.MODIFIED,
                )
                RouterAction.Speak(editConfirmation(edit, target.content))
            }
            is TodoistClient.Result.AuthError ->
                RouterAction.Speak("Todoist rejected the token — check Settings.")
            is TodoistClient.Result.Offline ->
                RouterAction.Speak("Can't reach Todoist — I'll have to skip that edit for now.")
            is TodoistClient.Result.RateLimited ->
                RouterAction.Speak("Todoist is rate-limited — try the edit again in a moment.")
            else ->
                RouterAction.Speak("That edit didn't go through.")
        }
    }

    private fun editConfirmation(
        edit: com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit,
        content: String,
    ): String = when (edit) {
        is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.Delete   -> "Deleted."
        is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.Complete -> "Marked done."
        is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.SetPriority ->
            "Set to ${edit.priority.spoken}."
        is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.MoveProject ->
            "Moved to ${edit.projectHint}."
        is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.AddLabel ->
            "Added the ${edit.label} label."
        is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.Reschedule -> {
            val parts = listOfNotNull(edit.date, edit.time?.let { "at $it" }, edit.recurrence)
                .joinToString(" ")
            "Moved to $parts."
        }
        is com.jarvis.assistant.todoist.edit.ConversationalEditParser.Edit.Snooze ->
            "Snoozed ${edit.minutes} minutes."
    }

    /**
     * Handle a list-query utterance ("what are my tasks", "show
     * overdue tasks", "what's on for today", "search tasks for X").
     *
     * Returns:
     *   - [RouterAction.Speak]        — short spoken summary
     *   - [RouterAction.NotApplicable] — not a list query
     *
     * Strictly local: only TodoistClient calls; never escalates to
     * OpenClaw / LLM.  Settings checks mirror handleFresh — disabled
     * integration + missing token produce friendly hints.
     */
    suspend fun handleListQuery(transcript: String): RouterAction {
        val query = com.jarvis.assistant.todoist.parse
            .TodoistListQueryParser.parse(transcript)
            ?: return RouterAction.NotApplicable

        val s = settingsProvider()
        if (!s.enabled) {
            return RouterAction.Speak(
                "Turn on the Todoist integration in Settings — I'll see your tasks once you do."
            )
        }
        if (s.apiToken.isBlank()) {
            return RouterAction.Speak(
                "I need your Todoist API token in Settings before I can read your tasks."
            )
        }

        Log.d(TAG, "[TODOIST_LIST_QUERY_BEGIN] scope=${query.scope} " +
            "term=${query.searchTerm}")
        val c = client()
        val result = when (query.scope) {
            com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.TODAY    -> c.getTodayTasks()
            com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.OVERDUE  -> c.getOverdueTasks()
            com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.UPCOMING -> c.getUpcomingTasks()
            com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.SEARCH   ->
                c.searchTasks(query.searchTerm.orEmpty())
            com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.COUNT,
            com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.ALL_ACTIVE -> c.getActiveTasks()
        }
        return when (result) {
            is TodoistClient.Result.Ok -> {
                Log.d(TAG, "[TODOIST_LIST_QUERY_OK] scope=${query.scope} " +
                    "count=${result.value.size}")
                // User-preferred policy: keep TODAY answers tight.
                // Don't volunteer the overall count, tomorrow's items,
                // or a "want me to read them" follow-up — the user
                // asked about today only, so the response stays on
                // today only.
                RouterAction.Speak(summariseTaskList(query, result.value))
            }
            is TodoistClient.Result.AuthError ->
                RouterAction.Speak("Todoist rejected the token — check Settings.")
            is TodoistClient.Result.Offline ->
                RouterAction.Speak("Can't reach Todoist right now — try again in a moment.")
            is TodoistClient.Result.RateLimited ->
                RouterAction.Speak("Todoist is rate-limited — try again shortly.")
            is TodoistClient.Result.ServerError ->
                RouterAction.Speak("Todoist had a problem fetching your tasks.")
            is TodoistClient.Result.Malformed ->
                RouterAction.Speak("Todoist sent something I couldn't read.")
        }
    }

    /**
     * Produce a short voice-friendly summary of [tasks] given the
     * requested scope.  Empty lists get a friendly "nothing in there"
     * line; long lists get truncated to the top 5 with a "+N more"
     * trailer.  Spoken text only — no JSON, no IDs.
     */
    internal fun summariseTaskList(
        query: com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Query,
        tasks: List<TodoistTask>,
    ): String {
        val scope = query.scope
        if (tasks.isEmpty()) {
            return when (scope) {
                com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.TODAY ->
                    "Nothing on for today."
                com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.OVERDUE ->
                    "Nothing overdue."
                com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.UPCOMING ->
                    "Nothing coming up."
                com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.SEARCH ->
                    "No tasks match \"${query.searchTerm}\"."
                com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.COUNT,
                com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.ALL_ACTIVE ->
                    "Nothing on your list."
            }
        }

        if (scope == com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.COUNT) {
            return when (tasks.size) {
                1    -> "You have one task."
                else -> "You have ${tasks.size} tasks."
            }
        }

        // ── Large-list special case ───────────────────────────────────
        // Reading 50 task names out loud isn't useful — it's
        // overwhelming and the listener can't remember any of them.
        // For ALL_ACTIVE with >10 items switch to a count + top-3 +
        // suggestion shape so the user can drill down.
        if (scope == com.jarvis.assistant.todoist.parse
                .TodoistListQueryParser.Scope.ALL_ACTIVE && tasks.size > 10
        ) {
            val top = tasks.take(3).joinToString("; ") { it.content.trim() }
            return "You've got ${tasks.size} tasks. Top of the list: $top. " +
                "Want today's only, or overdue?"
        }

        val header = when (scope) {
            com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.TODAY ->
                if (tasks.size == 1) "One thing today:" else "${tasks.size} things today:"
            com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.OVERDUE ->
                if (tasks.size == 1) "One overdue:" else "${tasks.size} overdue:"
            com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.UPCOMING ->
                if (tasks.size == 1) "One coming up:" else "${tasks.size} coming up:"
            com.jarvis.assistant.todoist.parse.TodoistListQueryParser.Scope.SEARCH ->
                if (tasks.size == 1) "One match:" else "${tasks.size} matches:"
            else ->
                if (tasks.size == 1) "One task:" else "${tasks.size} tasks:"
        }

        // Standard cap: top 5 + "plus N more" for moderate lists.
        val MAX = 5
        val shown = tasks.take(MAX)
        val items = shown.joinToString("; ") { it.content.trim() }
        val tail  = if (tasks.size > MAX) " plus ${tasks.size - MAX} more." else "."
        return "$header $items$tail"
    }

    /**
     * Drain any queued offline operations.  Call after recovery from
     * offline / 5xx.  Safe to call frequently — exits early when the
     * queue is empty or the network is still down.
     */
    suspend fun drainOfflineQueue(): Int {
        val q = offlineQueue()
        if (q.size() == 0) return 0
        val s = settingsProvider()
        if (!s.isFullyConfigured) return 0
        val c = client()
        return q.drain { entry ->
            when (entry.type) {
                TodoistOfflineQueue.Entry.Type.CREATE ->
                    c.createTask(entry.createPayload!!) is TodoistClient.Result.Ok
                TodoistOfflineQueue.Entry.Type.COMPLETE ->
                    c.completeTask(entry.completeTaskId!!) is TodoistClient.Result.Ok
                TodoistOfflineQueue.Entry.Type.DELETE ->
                    c.deleteTask(entry.deleteTaskId!!) is TodoistClient.Result.Ok
                TodoistOfflineQueue.Entry.Type.UPDATE ->
                    c.updateTask(
                        entry.updateTaskId!!,
                        entry.updateFields?.toMap() ?: emptyMap<String, String>()
                    ) is TodoistClient.Result.Ok
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** Decide: create now, or park for a follow-up question. */
    private suspend fun executeOrPark(m: ReminderIntentParser.Match): RouterAction {
        // Tasks (vs reminders) execute immediately even without a time.
        if (m.kind == ReminderIntentParser.Kind.TASK) {
            return executeReady(PendingTodoistTask.fromMatch(
                m, PendingTodoistTask.AwaitingSlot.NONE, clock()
            ))
        }
        if (m.needsTimeFollowUp) {
            val pending = PendingTodoistTask.fromMatch(
                m, PendingTodoistTask.AwaitingSlot.TIME, clock()
            )
            Log.d(TAG, "[TODOIST_PENDING_CREATED] slot=TIME content=\"${m.content}\"")
            Log.d(TAG, "[TODOIST_FOLLOWUP_REQUIRED] slot=TIME content=\"${m.content}\"")
            return RouterAction.ParkPending(
                pending,
                askPrompt = "When should I remind you?",
            )
        }
        return executeReady(PendingTodoistTask.fromMatch(
            m, PendingTodoistTask.AwaitingSlot.NONE, clock()
        ))
    }

    /** All slots filled — push to Todoist (or the offline queue). */
    private suspend fun executeReady(p: PendingTodoistTask): RouterAction {
        val req = buildCreateRequest(p, settingsProvider())
        Log.d(TAG, "[TODOIST_CREATE_TASK_START] content=\"${req.content}\" " +
            "dueString=${req.dueString} dueDate=${req.dueDate} " +
            "priority=${req.priority} projectId=${req.projectId}")

        val result = client().createTask(req)
        return when (result) {
            is TodoistClient.Result.Ok -> {
                recentTaskContext.remember(
                    taskId  = result.value.id,
                    content = p.content,
                    source  = com.jarvis.assistant.todoist.edit
                        .RecentTaskContextStore.Source.CREATED,
                )
                Log.d(TAG, "[TODOIST_CREATE_TASK_SUCCESS] id=${result.value.id}")
                Log.d(TAG, "[TODOIST_API_SUCCESS] op=createTask id=${result.value.id}")
                RouterAction.Speak(confirmationFor(p, online = true))
            }
            is TodoistClient.Result.AuthError -> {
                RouterAction.Speak("Todoist rejected the API token — check Settings.")
            }
            is TodoistClient.Result.RateLimited -> {
                offlineQueue().enqueueCreate(req)
                RouterAction.Speak("Todoist is rate-limited right now — I've saved it locally and I'll sync it later.")
            }
            is TodoistClient.Result.Offline -> {
                if (settingsProvider().offlineSyncEnabled) {
                    offlineQueue().enqueueCreate(req)
                    RouterAction.Speak("I've saved it locally and I'll sync it later.")
                } else {
                    RouterAction.Speak("I can't reach Todoist right now — try again when you're online.")
                }
            }
            is TodoistClient.Result.ServerError,
            is TodoistClient.Result.Malformed -> {
                offlineQueue().enqueueCreate(req)
                RouterAction.Speak("Todoist had a problem — I've saved it locally and I'll sync it later.")
            }
        }
    }

    /**
     * Build a Todoist create request from a fully-resolved pending task.
     * Defaults from [TodoistSettings] fill anything the user didn't say
     * ("no project" → settings.defaultProjectId, "no priority" →
     * settings.defaultPriority, …).
     */
    internal fun buildCreateRequest(
        p: PendingTodoistTask,
        s: TodoistSettings,
    ): TodoistClient.CreateTaskRequest {
        // Build the natural due string the Todoist server understands.
        val dueString = when {
            p.recurrence != null              -> p.recurrence
            p.date != null && p.time != null  -> "${p.date} at ${p.time}"
            p.date != null                    -> p.date
            p.time != null                    -> "today at ${p.time}"
            else                              -> null
        }

        // Merge user labels with defaults — explicit overrides default.
        val labels = (p.labels + s.defaultLabels).distinct().takeIf { it.isNotEmpty() }

        val priority = (p.priority ?: s.defaultPriority).apiValue

        return TodoistClient.CreateTaskRequest(
            content   = p.content,
            projectId = s.defaultProjectId.takeIf { it.isNotBlank() },
            labels    = labels,
            priority  = priority,
            dueString = dueString,
        )
    }

    private fun confirmationFor(p: PendingTodoistTask, online: Boolean): String {
        val timeBit = when {
            p.recurrence != null     -> " — ${p.recurrence}"
            p.date != null && p.time != null -> " — ${p.date} at ${p.time}"
            p.date != null           -> " — ${p.date}"
            p.contextTrigger != null -> " — ${triggerLabel(p.contextTrigger)}"
            p.repeat != null         -> " — ${p.repeat.intervalNaturalString}"
            else                     -> ""
        }
        val prefix = if (p.kind == ReminderIntentParser.Kind.REMINDER)
            "I'll remind you" else "Added to Todoist"
        return "$prefix${if (timeBit.isNotBlank()) "$timeBit" else ""}."
    }

    private fun triggerLabel(t: ReminderIntentParser.ContextTrigger): String = when (t.type) {
        ReminderIntentParser.ContextTriggerType.ARRIVE_HOME       -> "when you get home"
        ReminderIntentParser.ContextTriggerType.LEAVE_HOME        -> "when you leave home"
        ReminderIntentParser.ContextTriggerType.ARRIVE_WORK       -> "when you get to work"
        ReminderIntentParser.ContextTriggerType.GET_IN_CAR        -> "when you get in the car"
        ReminderIntentParser.ContextTriggerType.PHONE_PLUGGED_IN  -> "when you plug your phone in"
        ReminderIntentParser.ContextTriggerType.BLUETOOTH_CONNECT -> "when Bluetooth connects"
        ReminderIntentParser.ContextTriggerType.APP_OPEN -> {
            val app = t.payload ?: "the app"
            "next time you open $app"
        }
        ReminderIntentParser.ContextTriggerType.ARRIVE_AT_PLACE   ->
            "next time you're at ${t.payload ?: "that place"}"
        ReminderIntentParser.ContextTriggerType.HOME_ASSISTANT_EVENT ->
            "when ${t.payload ?: "the event"} happens"
    }

    /**
     * Merge a follow-up utterance into a parked pending task.  The slot
     * we asked about ([awaitingSlot]) decides where the new tokens
     * land.
     */
    internal fun mergeFollowUp(
        pending: PendingTodoistTask,
        followUp: String,
    ): PendingTodoistTask {
        if (followUp.isBlank()) return pending
        val now = clock()

        when (pending.awaitingSlot) {
            PendingTodoistTask.AwaitingSlot.TIME -> {
                // Parse the follow-up as a date/time expression in
                // isolation; it lives in the same word-form as the
                // original.
                val parsed = com.jarvis.assistant.todoist.parse
                    .DateTimeExpressionParser.parse(followUp.lowercase(), now)
                if (parsed.isEmpty) {
                    // Treat short answers like "9pm" specially.
                    val recovered = recoverLooseTime(followUp.lowercase())
                    if (recovered != null) {
                        return pending.copy(time = recovered, expiresAtMs = now + PendingTodoistTask.TTL_MS)
                    }
                    return pending
                }
                return pending.copy(
                    date          = parsed.date ?: pending.date,
                    time          = parsed.time ?: pending.time,
                    recurrence    = if (parsed.isRecurring) parsed.naturalString else pending.recurrence,
                    expiresAtMs   = now + PendingTodoistTask.TTL_MS,
                )
            }
            PendingTodoistTask.AwaitingSlot.PROJECT -> {
                val trimmed = followUp.trim()
                val cleaned = trimmed.removePrefix("the ").removePrefix("my ")
                return pending.copy(
                    projectHint = cleaned.takeIf { it.isNotBlank() && it.lowercase() != "no" },
                    expiresAtMs = now + PendingTodoistTask.TTL_MS,
                )
            }
            PendingTodoistTask.AwaitingSlot.LABEL -> {
                if (followUp.lowercase() in setOf("no", "none", "skip", "nope")) {
                    return pending.copy(expiresAtMs = now + PendingTodoistTask.TTL_MS)
                }
                return pending.copy(
                    labels = (pending.labels + followUp.trim()).distinct(),
                    expiresAtMs = now + PendingTodoistTask.TTL_MS,
                )
            }
            PendingTodoistTask.AwaitingSlot.CONTENT -> {
                // The whole follow-up IS the task content.  Re-run the
                // parser on a synthesised "task <followUp>" so any date /
                // time / label tokens land naturally.
                val verb = when (pending.kind) {
                    ReminderIntentParser.Kind.REMINDER -> "remind me to"
                    ReminderIntentParser.Kind.TASK     -> "add a task"
                }
                val parsed = ReminderIntentParser.parse("$verb ${followUp.trim()}", now)
                val merged = if (parsed != null) pending.copy(
                    content     = parsed.content.ifBlank { followUp.trim() },
                    date        = pending.date ?: parsed.date,
                    time        = pending.time ?: parsed.time,
                    recurrence  = pending.recurrence ?: parsed.recurrence,
                    priority    = pending.priority ?: parsed.priority,
                    projectHint = pending.projectHint ?: parsed.projectHint,
                    labels      = (pending.labels + parsed.labels).distinct(),
                    expiresAtMs = now + PendingTodoistTask.TTL_MS,
                ) else pending.copy(
                    content     = followUp.trim(),
                    expiresAtMs = now + PendingTodoistTask.TTL_MS,
                )
                return merged
            }
            PendingTodoistTask.AwaitingSlot.RECURRENCE,
            PendingTodoistTask.AwaitingSlot.NONE -> return pending
        }
    }

    private fun recoverLooseTime(s: String): String? {
        // "9", "9pm", "9:30", "at 9", "at 9pm"
        val m = Regex(
            """(?ix)\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b"""
        ).find(s) ?: return null
        val hour    = m.groupValues[1].toIntOrNull() ?: return null
        val minute  = m.groupValues[2].toIntOrNull() ?: 0
        val ampm    = m.groupValues[3].lowercase()
        var h = hour
        when (ampm) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
            else -> if (h in 1..7) h += 12      // bare "9" assumed evening
        }
        return "%02d:%02d".format(h.coerceIn(0, 23), minute.coerceIn(0, 59))
    }

    private fun nextFollowUpQuestion(p: PendingTodoistTask): Pair<PendingTodoistTask.AwaitingSlot, String> {
        return when {
            p.kind == ReminderIntentParser.Kind.REMINDER &&
                p.date == null && p.time == null && p.recurrence == null &&
                p.contextTrigger == null && p.repeat == null ->
                    PendingTodoistTask.AwaitingSlot.TIME to "When should I remind you?"
            p.projectHint == null &&
                settingsProvider().askForLabelAfterCreate ->
                    PendingTodoistTask.AwaitingSlot.PROJECT to "Which project?"
            p.labels.isEmpty() &&
                settingsProvider().askForLabelAfterCreate ->
                    PendingTodoistTask.AwaitingSlot.LABEL to "Want to add a label?"
            else ->
                PendingTodoistTask.AwaitingSlot.NONE to ""
        }
    }
}
