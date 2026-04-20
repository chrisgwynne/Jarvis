package com.jarvis.assistant.runtime.plan

import android.content.Context
import android.util.Log
import com.jarvis.assistant.llm.LlmResult
import com.jarvis.assistant.runtime.reference.LastActionStore
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolRegistry
import com.jarvis.assistant.tools.framework.ToolResult
import java.util.UUID

/**
 * PlanRunner — converts a multi-step LLM tool call into a confirm-once,
 * journalled execution flow with reverse-order undo support.
 *
 * Lifecycle of a plan:
 *
 *   1. Caller invokes [proposeFromMultiCall] with the LLM's MultiToolCall.
 *      Single-step calls fall through (return null) so the existing
 *      single-step dispatch path stays simple.
 *   2. Runner returns a [Proposal] containing a one-line spoken summary
 *      ("Three things — text, reminder, DND. Go?") and the pending plan.
 *      Caller speaks the summary and stashes the plan.
 *   3. On the next user turn the caller invokes [confirm] with go/cancel.
 *      [confirm] returns a [Resolution] describing what happened so the
 *      caller can speak the right next thing.
 *   4. Successful execution journals every step.  [undoLastPlan] walks the
 *      most recent plan in reverse, calling [Tool.undo] on each step that
 *      succeeded; steps with !isReversible are skipped with a note.
 */
class PlanRunner(
    private val context: Context,
    private val registry: ToolRegistry,
    private val journalDao: ActionJournalDao,
    /** Window used by [undoLastPlan] to refuse undoing very old plans. */
    private val undoMaxAgeMs: Long = 5 * 60 * 1000L,
    private val lastActionStore: LastActionStore? = null
) {

    companion object {
        private const val TAG = "PlanRunner"
        /** Step count below this never makes a plan — single tool call goes direct. */
        const val MIN_STEPS_FOR_PLAN = 2
    }

    /** Result of [proposeFromMultiCall]. */
    sealed class Proposal {
        /** Single-call request — caller should run it directly, not as a plan. */
        object SingleStep : Proposal()
        /** No tool name from the LLM matched a registered tool — bail. */
        object Empty : Proposal()
        /** Plan ready; caller speaks [Pending.confirmation] and waits. */
        data class Pending(val plan: Plan, val confirmation: String) : Proposal()
    }

    /** Result of [confirm]. */
    sealed class Resolution {
        data class Ran(val spoken: String, val planId: String, val anyFailed: Boolean) : Resolution()
        data class Cancelled(val spoken: String) : Resolution()
        data class Halted(val spoken: String, val planId: String) : Resolution()
    }

    /** Result of [undoLastPlan]. */
    sealed class UndoResult {
        object Nothing : UndoResult()
        data class TooOld(val ageMs: Long) : UndoResult()
        data class Done(val spoken: String, val undone: Int, val skipped: Int) : UndoResult()
    }

    /**
     * Build a [Proposal] from the LLM's MultiToolCall.
     *
     * Returns [Proposal.SingleStep] for ≤1 calls so the existing simple path
     * keeps owning that flow without redesign.
     */
    fun proposeFromMultiCall(
        multi: LlmResult.MultiToolCall,
        originatingTranscript: String
    ): Proposal {
        val resolved = multi.calls.mapNotNull { call ->
            val tool = registry.findByName(call.toolName) ?: return@mapNotNull null
            tool to call
        }
        if (resolved.isEmpty()) return Proposal.Empty
        if (resolved.size < MIN_STEPS_FOR_PLAN) return Proposal.SingleStep

        val steps = resolved.mapIndexed { idx, (tool, call) ->
            PlannedStep(
                ordinal    = idx,
                toolName   = tool.name,
                argsJson   = call.argsJson,
                shortLabel = labelFor(tool),
                reversible = tool.isReversible
            )
        }
        val allReversible = steps.all { it.reversible }
        val plan = Plan(
            id                    = UUID.randomUUID().toString(),
            steps                 = steps,
            summarySpoken         = summarise(steps, allReversible),
            allReversible         = allReversible,
            originatingTranscript = originatingTranscript
        )
        return Proposal.Pending(plan, plan.summarySpoken)
    }

    /**
     * Execute every step in [plan] in order, journalling as we go.
     *
     * Halts on the first ToolResult.Failure and marks remaining steps as
     * SKIPPED.  Returns a [Resolution] with the spoken next-line for the
     * caller.
     */
    suspend fun execute(plan: Plan): Resolution {
        var anyFailed = false
        for (step in plan.steps) {
            val tool = registry.findByName(step.toolName)
                ?: run {
                    journal(plan, step, JournalEntry.STATUS_SKIPPED, "")
                    continue
                }
            val argsMap = parseArgs(step.argsJson)
            val input   = ToolInput(plan.originatingTranscript, argsMap)
            val journalId = journal(plan, step, JournalEntry.STATUS_PENDING, "")

            val result = registry.execute(context, tool, input)
            val now = System.currentTimeMillis()
            when (result) {
                is ToolResult.Success -> {
                    if (result.rawData.isNotEmpty()) journalDao.updatePayload(journalId, result.rawData)
                    journalDao.setStatus(journalId, JournalEntry.STATUS_SUCCEEDED, now)
                }
                is ToolResult.Failure -> {
                    anyFailed = true
                    journalDao.setStatus(journalId, JournalEntry.STATUS_FAILED, now)
                    Log.w(TAG, "Step ${step.ordinal} ${step.toolName} failed: ${result.spokenFeedback}")
                    // Mark every remaining step as SKIPPED so undo can ignore them.
                    for (skip in plan.steps.drop(step.ordinal + 1)) {
                        val skipId = journal(plan, skip, JournalEntry.STATUS_SKIPPED, "")
                        journalDao.setStatus(skipId, JournalEntry.STATUS_SKIPPED, now)
                    }
                    if (plan.autoRollbackOnHalt) {
                        val rollback = rollbackHaltedPlan(plan.id)
                        val suffix = (rollback as? UndoResult.Done)?.spoken?.let { " — $it" } ?: ""
                        return Resolution.Halted(
                            spoken = "Stopped at ${step.shortLabel}: ${result.spokenFeedback}$suffix",
                            planId = plan.id,
                        )
                    }
                    return Resolution.Halted(
                        spoken = "Stopped at ${step.shortLabel}: ${result.spokenFeedback}",
                        planId = plan.id
                    )
                }
                else -> { /* Augmented / NotMatched are unexpected here */ }
            }
        }
        // Record into the referential store so "undo that" can route back
        // through [undoLastPlan].  Skip when every step failed — there's
        // nothing reversible to refer to.
        if (!anyFailed) {
            lastActionStore?.recordPlanRun(
                planId                = plan.id,
                originatingTranscript = plan.originatingTranscript,
                shortLabel            = plan.steps.firstOrNull()?.shortLabel?.let { "$it plan" }
                    ?: "plan",
                reversible            = plan.allReversible
            )
        }

        return Resolution.Ran(
            spoken    = "Done.",
            planId    = plan.id,
            anyFailed = anyFailed
        )
    }

    /** Cancel a pending plan without executing anything. */
    fun cancel(plan: Plan): Resolution.Cancelled {
        Log.d(TAG, "Plan ${plan.id} cancelled by user")
        return Resolution.Cancelled("Okay, scrapped it.")
    }

    /**
     * Roll back a plan that halted partway. Walks every SUCCEEDED step of
     * [planId] in reverse and calls [Tool.undo] on each reversible one.
     * Used by the runtime after [Resolution.Halted] so the user can ask
     * "roll that back" without the halt leaving partial state behind.
     */
    suspend fun rollbackHaltedPlan(planId: String): UndoResult {
        val entries = journalDao.forPlan(planId)
        if (entries.isEmpty()) return UndoResult.Nothing
        var undone = 0
        var skipped = 0
        for (entry in entries.sortedByDescending { it.ordinal }) {
            if (entry.status != JournalEntry.STATUS_SUCCEEDED) continue
            val tool = registry.findByName(entry.toolName)
            if (tool == null || !tool.isReversible) {
                skipped++
                continue
            }
            try {
                val argsMap = parseArgs(entry.argsJson)
                val result = tool.undo(ToolInput(entry.originatingTranscript, argsMap), entry.undoPayload)
                if (result is ToolResult.Success) {
                    journalDao.setStatus(entry.id, JournalEntry.STATUS_UNDONE)
                    undone++
                } else {
                    skipped++
                }
            } catch (e: Exception) {
                Log.w(TAG, "rollback undo() threw on ${entry.toolName}: ${e.message}")
                skipped++
            }
        }
        val spoken = when {
            undone == 0 && skipped == 0 -> "Nothing to roll back."
            undone == 0 -> "Couldn't undo any of it — those steps don't reverse."
            skipped == 0 -> if (undone == 1) "Rolled back." else "Rolled $undone back."
            else -> "Rolled back $undone — couldn't reverse $skipped."
        }
        return UndoResult.Done(spoken, undone, skipped)
    }

    /**
     * Walk the most recent plan in reverse, undoing each SUCCEEDED step.
     * Refuses if the plan is older than [undoMaxAgeMs] — undoing a stale
     * plan is more surprising than helpful.
     */
    suspend fun undoLastPlan(): UndoResult {
        val planId = journalDao.mostRecentSucceededPlanId()
            ?: return UndoResult.Nothing
        val entries = journalDao.forPlan(planId)
        if (entries.isEmpty()) return UndoResult.Nothing

        val newest = entries.maxOf { it.completedAtMs ?: it.createdAtMs }
        val age = System.currentTimeMillis() - newest
        if (age > undoMaxAgeMs) return UndoResult.TooOld(age)

        var undone = 0
        var skipped = 0
        for (entry in entries.sortedByDescending { it.ordinal }) {
            if (entry.status != JournalEntry.STATUS_SUCCEEDED) continue
            val tool = registry.findByName(entry.toolName)
            if (tool == null || !tool.isReversible) {
                skipped++
                continue
            }
            try {
                val argsMap = parseArgs(entry.argsJson)
                val result = tool.undo(ToolInput(entry.originatingTranscript, argsMap), entry.undoPayload)
                if (result is ToolResult.Success) {
                    journalDao.setStatus(entry.id, JournalEntry.STATUS_UNDONE)
                    undone++
                } else {
                    skipped++
                }
            } catch (e: Exception) {
                Log.w(TAG, "undo() threw on ${entry.toolName}: ${e.message}")
                skipped++
            }
        }
        val spoken = when {
            undone == 0 && skipped == 0 -> "Nothing to undo."
            undone == 0                 -> "Couldn't undo any of it — those steps don't reverse."
            skipped == 0                -> if (undone == 1) "Undone." else "All $undone undone."
            else                        -> "Undone $undone — couldn't reverse $skipped."
        }
        return UndoResult.Done(spoken, undone, skipped)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Build the one-line spoken confirmation for a plan.  Format:
     *   "Three things — text, reminder, DND. Go?"
     *   "Two things — one of them won't reverse. Go?"   (when any irreversible)
     */
    private fun summarise(steps: List<PlannedStep>, allReversible: Boolean): String {
        val nLabel = when (steps.size) {
            2 -> "Two"; 3 -> "Three"; 4 -> "Four"; 5 -> "Five"
            else -> steps.size.toString()
        }
        val labels = steps.joinToString(", ") { it.shortLabel }
        val warn = if (allReversible) "" else " — one won't reverse"
        return "$nLabel things — $labels$warn. Go?"
    }

    /** Best short label for a step: prefer [Tool.name] underscore-stripped. */
    private fun labelFor(tool: Tool): String = tool.name.replace('_', ' ')

    private fun parseArgs(argsJson: String): Map<String, String> = try {
        @Suppress("UNCHECKED_CAST")
        (com.jarvis.assistant.llm.NetworkClient.gson.fromJson(argsJson, Map::class.java) as Map<*, *>)
            .entries.associate { (k, v) -> k.toString() to v.toString() }
    } catch (e: Exception) {
        Log.w(TAG, "Malformed args, using empty: ${e.message}")
        emptyMap()
    }

    private suspend fun journal(
        plan: Plan,
        step: PlannedStep,
        status: String,
        undoPayload: String
    ): Long = journalDao.insert(
        JournalEntry(
            planId                = plan.id,
            ordinal               = step.ordinal,
            toolName              = step.toolName,
            argsJson              = step.argsJson,
            undoPayload           = undoPayload,
            status                = status,
            originatingTranscript = plan.originatingTranscript,
            createdAtMs           = System.currentTimeMillis(),
            completedAtMs         = null
        )
    )
}
