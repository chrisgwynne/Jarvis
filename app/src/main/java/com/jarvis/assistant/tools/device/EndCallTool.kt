package com.jarvis.assistant.tools.device

import android.util.Log
import com.jarvis.assistant.call.CallControlResult
import com.jarvis.assistant.call.OutgoingCallController
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

/**
 * EndCallTool — voice-triggered end-call / hang-up command.
 *
 * TRIGGER PHRASES (case-insensitive, partial match):
 *   "end call"         "end the call"
 *   "hang up"          "hang up the call"
 *   "cancel call"      "cancel the call"
 *   "stop calling"     "stop the call"
 *
 * WHEN IT WORKS:
 *   This tool is effective in two windows:
 *
 *   1. Brief pre-suspension window — in the ~200–600 ms between when
 *      CallTool fires the ACTION_CALL intent and when TelephonyCallMonitor
 *      receives CALL_STATE_OFFHOOK and suspends the assistant.  The user
 *      would need to speak "Jarvis, end call" extremely quickly.
 *
 *   2. Via ACTION_END_CALL service intent — JarvisService exposes this
 *      intent so the UI (notification action, overlay button) can invoke
 *      it directly without going through the voice pipeline.  The service
 *      calls JarvisRuntime.endActiveCall() which bypasses the tool system.
 *
 *   In both cases, OutgoingCallController.endCall() is the single path.
 *
 * HONEST FAILURE:
 *   If no call is active, or the device cannot end calls programmatically,
 *   a clear spoken message is returned — no silent no-op.
 */
class EndCallTool(
    private val controller: OutgoingCallController
) : Tool {

    override val name        = "end_call"
    override val description = "Ends or cancels an outgoing ringing or active call"

    companion object {
        private const val TAG = "EndCallTool"

        /**
         * Matches:
         *  "end call", "end the call", "end this call"
         *  "hang up", "hang up the call"
         *  "cancel call", "cancel the call"
         *  "stop calling", "stop the call", "stop call"
         *
         * NOT matched (handled by isStopCommand in runtime):
         *  "stop"     — bare "stop" is handled upstream as a stop command
         *  "cancel"   — bare "cancel" is handled upstream
         */
        private val TRIGGER_RE = Regex(
            """(?:^|\b)(?:end|hang\s+up|cancel|stop)\s+(?:(?:the|this|my)\s+)?call(?:ing)?\b""" +
            """|(?:^|\b)hang\s+up(?:\s+(?:the|this)\s+call)?\b""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        return if (TRIGGER_RE.containsMatchIn(t)) {
            Log.d(TAG, "Matched end-call intent in: \"${t.take(60)}\"")
            ToolInput(t)
        } else null
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        Log.d(TAG, "execute() — isOutgoingCallActive=${controller.isOutgoingCallActive}")
        return when (val result = controller.endCall()) {
            is CallControlResult.Success     -> {
                Log.i(TAG, "Call ended: ${result.debugReason}")
                ToolResult.Success(result.humanMessage)
            }
            is CallControlResult.NoActiveCall -> {
                Log.d(TAG, "No active call to end")
                ToolResult.Failure(result.humanMessage)
            }
            is CallControlResult.Unsupported  -> {
                Log.w(TAG, "End-call unsupported: ${result.debugReason}")
                ToolResult.Failure(result.humanMessage)
            }
            is CallControlResult.Failed       -> {
                Log.e(TAG, "End-call failed: ${result.debugReason}")
                ToolResult.Failure(result.humanMessage)
            }
        }
    }
}
