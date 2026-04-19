package com.jarvis.assistant.security

object ActionPolicyGate {

    /**
     * Evaluate whether [toolName] is approved for execution.
     *
     * Every call is recorded in [PolicyAuditLog].
     *
     * Returns [PolicyResult.ActionApproved] if the tool maps to a known ActionType.
     * Returns [PolicyResult.ActionUnsupported] otherwise — the request is NOT silently dropped;
     * it is captured with full context for debugging and bug tracking.
     */
    fun evaluate(
        toolName: String,
        transcript: String,
        /**
         * Runtime kill switch.  When true, every tool — even allowlisted ones —
         * is denied with [DenialReason.DISABLED_BY_POLICY].  Conversation with
         * the LLM is unaffected; only tool execution is blocked.
         */
        killSwitchActive: Boolean = false
    ): PolicyResult {
        val actionType = ActionType.fromToolName(toolName)
        val result = when {
            killSwitchActive -> PolicyResult.ActionDenied(
                requestedActionType = actionType,
                rawRequestedAction  = transcript,
                reasonCode          = DenialReason.DISABLED_BY_POLICY,
                humanMessage        = "Tool execution is paused. You can re-enable it in Settings \u2192 Privacy.",
                debugDetails        = "Kill switch active (SettingsStore.toolExecutionDisabled=true)"
            )
            actionType != null -> PolicyResult.ActionApproved(
                requestedActionType = actionType,
                rawRequestedAction  = transcript,
                toolName            = toolName
            )
            else -> PolicyResult.ActionUnsupported(
                rawRequestedAction = transcript,
                toolNameAttempted  = toolName,
                debugDetails       = "Tool '$toolName' has no entry in ActionType.APPROVED_TOOL_MAP. " +
                                     "If this is a new tool, add it to the allowlist before it can execute."
            )
        }
        PolicyAuditLog.record(result)
        return result
    }

    /**
     * Pre-validate a raw transcript before tool matching.
     *
     * Returns a [PolicyResult] describing the problem if the transcript is suspicious,
     * or null if the transcript passes all checks and may proceed.
     */
    fun validateTranscript(transcript: String): PolicyResult? {
        if (transcript.isBlank()) {
            val result = PolicyResult.ActionMalformed(
                rawRequestedAction = transcript,
                reasonCode         = "EMPTY_TRANSCRIPT",
                debugDetails       = "Empty or blank transcript received by policy gate"
            )
            PolicyAuditLog.record(result)
            return result
        }
        if (transcript.length > 1000) {
            val result = PolicyResult.ActionUnsafe(
                rawRequestedAction = transcript.take(120) + "\u2026[truncated, total=${transcript.length}]",
                reasonCode         = "TRANSCRIPT_EXCEEDS_SAFE_LENGTH",
                debugDetails       = "Transcript length ${transcript.length} exceeds safety limit of 1000 chars. " +
                                     "Possible prompt injection or STT runaway."
            )
            PolicyAuditLog.record(result)
            return result
        }
        return null
    }

    /** Fast allowlist check — no logging side effect. Use evaluate() in the real pipeline. */
    fun isApproved(toolName: String): Boolean = ActionType.fromToolName(toolName) != null

    /** Returns the full approved tool -> action type map (read-only copy for diagnostics). */
    fun approvedTools(): Map<String, ActionType> = ActionType.APPROVED_TOOL_MAP
}
