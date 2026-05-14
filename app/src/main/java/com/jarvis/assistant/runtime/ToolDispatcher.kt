package com.jarvis.assistant.runtime

import android.content.Context
import android.util.Log
import com.jarvis.assistant.core.decisions.ActionLedger
import com.jarvis.assistant.core.safety.ConfirmationGate
import com.jarvis.assistant.core.store.DeviceStateStore
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
import com.jarvis.assistant.runtime.ResponseFormatter

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
     * Supplies the current "voice strict mode" setting.  Default false
     * — owner is assumed; voice identity is an upgrade signal, not a
     * blocker.  See [com.jarvis.assistant.speaker.trust.VoiceTrustState]
     * for the policy matrix backing this flag.
     */
    private val voiceStrictModeProvider: () -> Boolean = { false },
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
    /**
     * Optional buffer that captures every successful tool execution so the
     * user can later say "save that as a routine called X" and the
     * [com.jarvis.assistant.tools.device.SaveRoutineTool] finds a sequence
     * to persist.
     */
    private val recentToolCallBuffer: com.jarvis.assistant.core.routines.RecentToolCallBuffer? = null,
) {

    companion object {
        private const val TAG = "ToolDispatcher"
        private val ROUTINE_TOOL_NAMES = setOf(
            "save_routine", "run_routine", "list_routines", "delete_routine",
            "undo_last_action", "repeat_last_action", "report_issue",
        )
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
        /**
         * Execution failed — caller speaks the failure message then resumes.
         *
         * [hints] carries the tool name + parsed input so the caller can do
         * smart things on top of a generic "failure".  In particular, the
         * messaging tools return `ToolResult.Failure(spokenFeedback=
         * "What should the X to Y say?")` when the body slot is empty;
         * JarvisRuntime uses [hints] to recognise that path and park a
         * [com.jarvis.assistant.tools.device.messaging.PendingMessageIntent]
         * so the user's NEXT utterance ("Hello") becomes the body instead
         * of being routed as a fresh small-talk turn.  Without [hints] the
         * caller couldn't tell a "body missing" failure apart from any
         * other tool error.
         */
        data class Failed(
            val message: String,
            val hints: BrainHints? = null,
        ) : DispatchResult()
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
        /**
         * Confidence tier from [com.jarvis.assistant.audio.stt.TranscriptCorrector].
         * Used in the confirmation gate so:
         *   - HIGH-risk tools always confirm regardless of tier.
         *   - MEDIUM-risk tools confirm ONLY when tier is not HIGH.
         *   - LOW-risk tools never confirm.
         * Pass null to fall back to legacy "always confirm if not LOW" behaviour.
         */
        confidenceTier: com.jarvis.assistant.audio.stt.TranscriptCorrector.ConfidenceTier? = null,
    ): DispatchResult {
        val hints = BrainHints(tool.name, input)

        // ── Speaker permission gate ────────────────────────────────────────────
        // Honours the user's "voice strict mode" setting.  Default OFF
        // means low-confidence / unknown voices are treated as
        // OWNER_ASSUMED for LOW + MEDIUM risk commands — no lockouts.
        val speakerDecision = SpeakerPermissionPolicy.evaluate(
            sessionSpeaker.result, tool.name,
            strictMode = voiceStrictModeProvider(),
        )
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
        // Tier × Risk matrix:
        //   LOW risk       → never confirm (auto-execute)
        //   MEDIUM risk    → confirm only when confidence is NOT HIGH
        //   HIGH risk      → always confirm
        // Legacy callers (no tier passed in) fall back to the old behaviour:
        // confirm anything that isn't LOW.
        val risk = tool.riskClass
        val needsConfirmation = !skipConfirmation && confirmationGate != null && when (risk) {
            com.jarvis.assistant.tools.framework.RiskClass.LOW    -> false
            com.jarvis.assistant.tools.framework.RiskClass.HIGH   -> true
            com.jarvis.assistant.tools.framework.RiskClass.MEDIUM ->
                confidenceTier != com.jarvis.assistant.audio.stt.TranscriptCorrector.ConfidenceTier.HIGH
        }
        if (risk == com.jarvis.assistant.tools.framework.RiskClass.LOW && !skipConfirmation) {
            Log.d(TAG, "[LOW_RISK_AUTO_EXECUTE] tool=${tool.name}")
        }
        if (needsConfirmation) {
            val registered = confirmationGate!!.registerPending(tool, input, transcript)
            Log.d(TAG, "[CONFIRMATION_PENDING_CREATED] tool=${tool.name} risk=$risk " +
                "tier=$confidenceTier pending=${registered.pending.id}")
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
            // Don't buffer the routine tools themselves — recording a
            // save_routine call would make the next save capture save_routine.
            if (tool.name !in ROUTINE_TOOL_NAMES) {
                recentToolCallBuffer?.record(
                    toolName = tool.name,
                    shortLabel = tool.name.replace('_', ' '),
                    reversible = tool.isReversible,
                    input = input,
                )
            }
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
                DispatchResult.Failed(result.spokenFeedback, hints = hints)
            else ->
                DispatchResult.Done(spokenFeedback = "", hints = null)
        }
    }
}
