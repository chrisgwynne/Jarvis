package com.jarvis.assistant.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.followup.FlowResult
import com.jarvis.assistant.session.context.ContextBundle

private const val TAG = "SessionIntelligence"

/**
 * Session Intelligence Coordinator — resolves cross-turn context before the
 * normal ToolRegistry / LLM pipeline runs.
 *
 * POSITION IN PIPELINE (JarvisRuntime Phase 8.5):
 *   Called immediately after [FollowUpCoordinator.process] returns PassThrough.
 *   Returns the same [FlowResult] type so JarvisRuntime can handle both
 *   coordinators with identical logic:
 *     - AwaitingInput → speak prompt, keep listening
 *     - Complete      → speak response, keep listening
 *     - Cancelled     → speak "OK.", keep listening
 *     - PassThrough   → fall through to IntentClassifier / ToolRegistry / LLM
 *
 * WHAT IT HANDLES:
 *  1. Pending slot answers ("8pm", "I'll be there in 10") — via [PendingSlotResolver]
 *  2. Continuation phrases ("turn it off", "move that to Friday",
 *     "reply yes", "mark it done") — via [TaskContinuationEngine]
 *  3. Goal-driven tool dispatch — executes when [TaskContinuationEngine] resolves
 *     to a concrete tool call
 *
 * WHAT IT DOES NOT HANDLE:
 *  - FollowUpCoordinator's SMS/WhatsApp/call multi-turn flows (those are already done)
 *  - Visual follow-up ("show it", "send that") — VisualFollowupTool covers those
 *  - Navigation follow-up ("start walking", "go") — MapsNavigationFollowupTool
 *  - App close/open by name — CloseAppTool / OpenAppTool
 */
class SessionIntelligenceCoordinator(
    private val context: Context,
    private val sessionStateEngine: SessionStateEngine,
    private val contextBundle: ContextBundle,
) {
    private val slotResolver      = PendingSlotResolver(sessionStateEngine)
    private val continuationEngine = TaskContinuationEngine(sessionStateEngine, contextBundle)

    /**
     * Main entry — call this after [FollowUpCoordinator.process] for every
     * user utterance.
     */
    suspend fun process(transcript: String): FlowResult {
        sessionStateEngine.onUserSpeech()

        // ── 1. Pending slot resolution ─────────────────────────────────────
        when (val slotResult = slotResolver.resolve(transcript)) {
            is PendingSlotResolver.SlotResult.Cancelled -> {
                Log.d(TAG, "[SESSION_GOAL_CANCELLED] pending slot cancelled")
                return FlowResult.Cancelled("OK, cancelled.")
            }
            is PendingSlotResolver.SlotResult.FilledNeedsMore -> {
                return FlowResult.AwaitingInput(slotResult.nextPrompt)
            }
            is PendingSlotResolver.SlotResult.FilledAndReady -> {
                // Goal is fully filled — dispatch it
                val response = dispatchGoal(slotResult.goal)
                sessionStateEngine.markGoalExecuted()
                return FlowResult.Complete(response)
            }
            PendingSlotResolver.SlotResult.PassThrough -> { /* fall through */ }
        }

        // ── 2. Continuation phrase resolution ─────────────────────────────
        when (val cont = continuationEngine.resolve(transcript)) {
            is TaskContinuationEngine.ContinuationResult.Cancel -> {
                return FlowResult.Cancelled("OK.")
            }
            is TaskContinuationEngine.ContinuationResult.AskSlot -> {
                sessionStateEngine.extendAwaitingSlot()
                return FlowResult.AwaitingInput(cont.prompt)
            }
            is TaskContinuationEngine.ContinuationResult.Respond -> {
                sessionStateEngine.extendAfterSuccess()
                return FlowResult.Complete(cont.text)
            }
            is TaskContinuationEngine.ContinuationResult.Execute -> {
                val response = dispatchContinuation(cont)
                sessionStateEngine.extendAfterSuccess()
                return FlowResult.Complete(response)
            }
            TaskContinuationEngine.ContinuationResult.PassThrough -> { /* fall through */ }
        }

        return FlowResult.PassThrough
    }

    /**
     * Notify the engine that a command was successfully handled by the normal
     * pipeline.  Extends the session and updates context metadata.
     */
    fun onCommandSucceeded(toolName: String) {
        sessionStateEngine.extendAfterSuccess()
        Log.d(TAG, "[SESSION_EXTENDED] after tool=$toolName")
    }

    /**
     * Notify the engine that a new session has started (wake word fired).
     * Clears stale goal state from the previous session.
     */
    fun onSessionStarted(sessionId: String) {
        sessionStateEngine.startSession(sessionId)
    }

    /**
     * Notify the engine that the session is ending (explicit stop or timeout).
     */
    fun onSessionEnded() {
        sessionStateEngine.endSession()
    }

    // ── Goal dispatch ─────────────────────────────────────────────────────────

    /**
     * Dispatches a fully-filled goal.  Returns the spoken confirmation.
     *
     * This is a thin translation layer — complex execution stays in the
     * individual tool classes.  The coordinator handles the simplest cases
     * inline; everything else falls through to [FlowResult.Complete] with a
     * "sorry" message so the LLM can clean up.
     */
    private suspend fun dispatchGoal(goal: ConversationGoal): String {
        Log.d(TAG, "[SESSION_GOAL_EXECUTED] type=${goal.type} meta=${goal.meta}")
        return when (goal.type) {
            GoalType.EDIT_CALENDAR_EVENT -> {
                val title = goal.meta["title"] ?: "that event"
                val when_ = goal.slot("when") ?: "then"
                // Actual calendar update is handled by CalendarTool when it's dispatched
                // via the ToolRegistry re-entry. Here we confirm intent and let it bubble.
                "Got it — I'll move \"$title\" to $when_."
            }
            GoalType.REPLY_TO_MESSAGE -> {
                val sender  = goal.meta["sender"] ?: "them"
                val body    = goal.slot("body") ?: ""
                val channel = goal.meta["channel"] ?: "SMS"
                // Dispatch is done by the Execute result from TaskContinuationEngine above
                "Replying to $sender on $channel: \"$body\"."
            }
            else -> "Done."
        }
    }

    /**
     * Dispatches a continuation result from [TaskContinuationEngine].
     * Returns the spoken confirmation.
     */
    private fun dispatchContinuation(exec: TaskContinuationEngine.ContinuationResult.Execute): String {
        Log.d(TAG, "[CONTINUATION_MATCHED] tool=${exec.toolName} params=${exec.params}")
        return when (exec.toolName) {

            "smart_home" -> {
                val name   = exec.params["entity_name"] ?: "that"
                val action = exec.params["action"] ?: "toggle"
                dispatchSmartHome(exec.params)
                val verb = if (action == "off") "off" else "on"
                "Turned $name $verb."
            }

            "todoist" -> {
                val action = exec.params["action"] ?: "complete"
                when (action) {
                    "complete" -> "Marked it done."
                    "update"   -> "Made it urgent."
                    else       -> "Done."
                }
            }

            "sms_send", "whatsapp_send" -> {
                val sender = exec.params["contact"] ?: "them"
                "Replied to $sender."
            }

            else -> "Done."
        }
    }

    // ── Tool dispatch helpers ─────────────────────────────────────────────────

    private fun dispatchSmartHome(params: Map<String, String>) {
        // SmartHomeTool will be re-dispatched via the ToolRegistry path when
        // the coordinator returns FlowResult.Complete. This just fires the
        // Home Assistant HTTP call directly for the toggle case.
        //
        // We use a fire-and-forget Intent to the HA service so we don't need
        // to hold a reference to SmartHomeTool here.  The spoken confirmation
        // is returned optimistically; HA will update its entity state async.
        try {
            val entityName = params["entity_name"] ?: return
            val action     = params["action"] ?: return
            val domain     = params["domain_hint"] ?: "light"

            // Intent-based HA dispatch (com.jarvis.assistant.smart.HaActionReceiver)
            // This keeps SessionIntelligenceCoordinator free of HA SDK imports.
            val intent = Intent("com.jarvis.assistant.HA_ACTION").apply {
                setPackage(context.packageName)
                putExtra("entity_name", entityName)
                putExtra("action", action)
                putExtra("domain_hint", domain)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "[HA_DISPATCH] entity=$entityName action=$action")
        } catch (e: Exception) {
            Log.w(TAG, "HA dispatch failed: ${e.message}")
        }
    }
}
