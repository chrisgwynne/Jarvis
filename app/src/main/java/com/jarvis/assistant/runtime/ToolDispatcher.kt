package com.jarvis.assistant.runtime

import android.content.Context
import android.util.Log
import com.jarvis.assistant.core.decisions.ActionLedger
import com.jarvis.assistant.core.safety.ConfirmationGate
import com.jarvis.assistant.core.state.DeviceStateStore
import com.jarvis.assistant.core.state.JarvisState
import com.jarvis.assistant.core.state.JarvisStateMachine
import com.jarvis.assistant.security.ActionPolicyGate
import com.jarvis.assistant.security.PolicyResult
import com.jarvis.assistant.speaker.SpeakerPermissionPolicy
import com.jarvis.assistant.speaker.SpeakerSessionContext
import com.jarvis.assistant.runtime.reference.LastActionStore
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolRegistry
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.util.LatencyTracker
import com.jarvis.assistant.util.ResponseFormatter

/**
 * ToolDispatcher — decouples the tool-match → permission-check → policy-gate → execute
 * sequence from the main conversation loop in JarvisRuntime.
 *
 * Caller (JarvisRuntime) matches a tool, then calls [dispatch]. The returned
 * [DispatchResult] tells the caller exactly what to do next, so the loop logic
 * stays in JarvisRuntime while the dispatch logic is independently testable.
 */
class ToolDispatcher(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val machine: JarvisStateMachine,
    private val lastActionStore: LastActionStore? = null,
    /**
     * Supplies the current kill-switch state at dispatch time.  Evaluated on
     * every call so flipping the toggle in Settings takes effect on the next
     * tool invocation with no runtime restart.  Defaults to "never denied" so
     * tests and legacy callers don't need to plumb SettingsStore through.
     */
    private val killSwitchProvider: () -> Boolean = { false },
    /**
     * Supplies the shared action ledger at dispatch time. Lambda rather
     * than direct injection so JarvisRuntime can construct the dispatcher
     * before ProactiveEngine (which currently owns the ledger). Returns
     * null until the ledger is wired — dispatcher then records nothing,
     * which is the safe legacy behaviour.
     */
    private val actionLedgerProvider: () -> ActionLedger? = { null },
    /**
     * Optional confirmation gate. When supplied, destructive tools
     * (RiskClass MEDIUM / HIGH) are intercepted before execute() — the
     * dispatcher registers a pending confirmation with the gate and
     * returns [DispatchResult.NeedsConfirmation] so the runtime can
     * speak the prompt and await the user's next utterance.
     */
    private val confirmationGate: ConfirmationGate? = null,
) {

    companion object {
        private const val TAG = "ToolDispatcher"
    }

    /**
     * Outcome of running the dispatch pipeline for one matched tool.
     */
    sealed class DispatchResult {
        /** Tool denied by speaker/policy gate — caller should resume listening loop. */
        data class Denied(val message: String) : DispatchResult()
        /** Tool ran silently — caller should exit the session without TTS. */
        object SilentExit : DispatchResult()
        /** Tool completed — [spokenFeedback] already formatted; caller speaks it then resumes. */
        data class Done(val spokenFeedback: String, val hints: BrainHints?) : DispatchResult()
        /** Tool result requires LLM follow-up; caller sends [augmentedTranscript] to LLM. */
        data class AugmentedLlm(val augmentedTranscript: String, val hints: BrainHints?) : DispatchResult()
        /** Tool ran OK but needs an LLM follow-up for the spoken response. */
        data class LlmFollowUp(val spokenFeedback: String, val hints: BrainHints?) : DispatchResult()
        /** Execution failed — caller speaks the failure message then resumes. */
        data class Failed(val message: String) : DispatchResult()
        /**
         * Tool is risk-gated. Caller MUST speak [prompt] and transition
         * back to listening so the user's next utterance reaches the
         * [ConfirmationGate]. The runtime re-dispatches via
         * [dispatch] with `skipConfirmation=true` on affirmative.
         */
        data class NeedsConfirmation(
            val prompt: String,
            val pendingId: String,
            val toolName: String,
        ) : DispatchResult()
    }

    /** Carries tool name + input so JarvisRuntime can log brain events. */
    data class BrainHints(val toolName: String, val input: ToolInput)

    private fun argsToJson(params: Map<String, String>): String = try {
        com.jarvis.assistant.llm.NetworkClient.gson.toJson(params)
    } catch (_: Exception) {
        ""
    }

    /**
     * Map a tool name to the ledger's action-class label. Must match the
     * labels the proactive triggers use so a voice action suppresses a
     * proactive nudge in the same domain. Unknown tools return null.
     */
    private fun toolActionClass(toolName: String): String? = when (toolName) {
        "call_contact", "end_call" -> "CALL"
        "send_sms", "whatsapp_message", "email_send" -> "MESSAGE"
        "read_notifications", "clear_notifications" -> "NOTIFICATION"
        "set_alarm" -> "ALARM"
        "set_timer" -> "TIMER"
        "set_reminder", "create_reminder", "location_reminder",
        "list_reminders", "cancel_reminder" -> "REMINDER"
        "calendar_read", "calendar_create", "calendar_accept" -> "CALENDAR"
        "smart_home" -> "SMART_HOME"
        "volume_control" -> "VOLUME"
        "flashlight" -> "FLASHLIGHT"
        "media_control", "music_search" -> "MEDIA"
        "shopping_list" -> "SHOPPING"
        "daily_briefing" -> "BRIEFING"
        else -> null
    }

    /**
     * Run the full dispatch pipeline for [tool] / [input] given [sessionSpeaker].
     * Does NOT call speakAndRecord — the caller owns TTS.
     */
    suspend fun dispatch(
        tool: com.jarvis.assistant.tools.framework.Tool,
        input: ToolInput,
        sessionSpeaker: SpeakerSessionContext,
        transcript: String,
        skipConfirmation: Boolean = false,
    ): DispatchResult {
        val hints = BrainHints(tool.name, input)

        // ── Speaker permission gate ────────────────────────────────────────────
        val speakerDecision = SpeakerPermissionPolicy.evaluate(sessionSpeaker.result, tool.name)
        if (!speakerDecision.allowed) {
            return DispatchResult.Denied(speakerDecision.denyReason ?: "I can't do that right now.")
        }

        // ── Policy gate ───────────────────────────────────────────────────────
        val policyResult = ActionPolicyGate.evaluate(
            toolName         = tool.name,
            transcript       = transcript,
            killSwitchActive = killSwitchProvider()
        )
        LatencyTracker.mark("POLICY_EVALUATED")
        if (policyResult !is PolicyResult.ActionApproved) {
            val message = when (policyResult) {
                is PolicyResult.ActionUnsupported -> policyResult.humanMessage
                is PolicyResult.ActionDenied      -> policyResult.humanMessage
                is PolicyResult.ActionUnsafe      -> policyResult.humanMessage
                is PolicyResult.ActionMalformed   -> policyResult.humanMessage
                else                              -> "I can't do that right now."
            }
            return DispatchResult.Denied(message)
        }

        // ── Confirmation gate ─────────────────────────────────────────────────
        if (!skipConfirmation && confirmationGate != null &&
            tool.riskClass != com.jarvis.assistant.tools.framework.RiskClass.LOW
        ) {
            val registered = confirmationGate.registerPending(tool, input, transcript)
            Log.d(TAG, "Confirmation required for ${tool.name} (risk=${tool.riskClass}) pending=${registered.pending.id}")
            return DispatchResult.NeedsConfirmation(
                prompt = registered.prompt,
                pendingId = registered.pending.id,
                toolName = tool.name,
            )
        }

        // ── Execute ───────────────────────────────────────────────────────────
        machine.transition(JarvisState.ToolRunning(tool.name))
        DeviceStateStore.update { copy(currentToolName = tool.name) }

        val result = toolRegistry.execute(context, tool, input)
        LatencyTracker.mark("TOOL_EXECUTED")
        DeviceStateStore.update { copy(currentToolName = null) }

        Log.d(TAG, "Tool '${tool.name}' result: ${result::class.simpleName}")

        // Record referential entry so the user can say "undo that" / "do the
        // same for X".  Only record on Success — failures don't represent a
        // reversible side-effect worth remembering.  Referential tools
        // (undo/repeat) are excluded to avoid self-referential loops.
        if (result is ToolResult.Success &&
            tool.name != "undo_last_action" &&
            tool.name != "repeat_last_action"
        ) {
            lastActionStore?.recordToolCall(
                toolName              = tool.name,
                argsJson              = argsToJson(input.params),
                originatingTranscript = transcript,
                shortLabel            = tool.name.replace('_', ' '),
                reversible            = tool.isReversible,
                rawData               = result.rawData
            )
            actionLedgerProvider()?.recordToolExecution(
                toolName = tool.name,
                actionClass = toolActionClass(tool.name),
            )
        }

        return when (result) {
            is ToolResult.Success -> when {
                result.silent -> DispatchResult.SilentExit
                result.requiresLlmFollowUp ->
                    DispatchResult.LlmFollowUp(
                        spokenFeedback = ResponseFormatter.formatToolFeedback(result.spokenFeedback),
                        hints = hints
                    )
                else ->
                    DispatchResult.Done(
                        spokenFeedback = ResponseFormatter.formatToolFeedback(result.spokenFeedback),
                        hints = hints
                    )
            }
            is ToolResult.Augmented ->
                DispatchResult.AugmentedLlm(augmentedTranscript = result.augmentedTranscript, hints = hints)
            is ToolResult.Failure ->
                DispatchResult.Failed(result.spokenFeedback)
            else ->
                DispatchResult.Done(spokenFeedback = "", hints = null)
        }
    }
}
