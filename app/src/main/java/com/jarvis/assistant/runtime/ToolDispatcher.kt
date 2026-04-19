package com.jarvis.assistant.runtime

import android.content.Context
import android.util.Log
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
    private val lastActionStore: LastActionStore? = null
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
    }

    /** Carries tool name + input so JarvisRuntime can log brain events. */
    data class BrainHints(val toolName: String, val input: ToolInput)

    private fun argsToJson(params: Map<String, String>): String = try {
        com.jarvis.assistant.llm.NetworkClient.gson.toJson(params)
    } catch (_: Exception) {
        ""
    }

    /**
     * Run the full dispatch pipeline for [tool] / [input] given [sessionSpeaker].
     * Does NOT call speakAndRecord — the caller owns TTS.
     */
    suspend fun dispatch(
        tool: com.jarvis.assistant.tools.framework.Tool,
        input: ToolInput,
        sessionSpeaker: SpeakerSessionContext,
        transcript: String
    ): DispatchResult {
        val hints = BrainHints(tool.name, input)

        // ── Speaker permission gate ────────────────────────────────────────────
        val speakerDecision = SpeakerPermissionPolicy.evaluate(sessionSpeaker.result, tool.name)
        if (!speakerDecision.allowed) {
            return DispatchResult.Denied(speakerDecision.denyReason ?: "I can't do that right now.")
        }

        // ── Policy gate ───────────────────────────────────────────────────────
        val policyResult = ActionPolicyGate.evaluate(tool.name, transcript)
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
