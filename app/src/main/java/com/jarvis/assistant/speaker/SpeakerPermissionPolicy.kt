package com.jarvis.assistant.speaker

/**
 * SpeakerPermissionPolicy — gates tool actions as PUBLIC or PERSONAL based on
 * the active session's speaker confidence.
 *
 * PUBLIC actions are available to any speaker regardless of identity confidence.
 * PERSONAL actions require HIGH_CONFIDENCE_MATCH (or explicit confirmation, which
 * is not yet implemented — currently gated hard).
 *
 * This is a soft advisory gate.  It returns [PolicyDecision] which the caller
 * can choose to act on; it never throws.
 *
 * Adding a new tool: edit [classifyAction].
 * Relaxing personal action gating: add a confirmation flow in [JarvisRuntime].
 */
object SpeakerPermissionPolicy {

    enum class ActionClass { PUBLIC, PERSONAL }

    data class PolicyDecision(
        val allowed: Boolean,
        /** Speak this to the user when [allowed] is false. Null when [allowed] is true. */
        val denyReason: String? = null
    )

    /** Classify a tool by its registered name. */
    fun classifyAction(toolName: String): ActionClass = when (toolName) {
        // ── PUBLIC ────────────────────────────────────────────────────────────
        "flashlight",
        "volume",
        "media_control",
        "alarm",
        "timer",
        "camera_capture",
        "analyze_camera_view",
        "audio_recording",
        "image_generation",
        "open_app",
        "web_search",
        "help"              -> ActionClass.PUBLIC

        // ── PERSONAL ─────────────────────────────────────────────────────────
        "memory_recall",
        "shopping_list",
        "call",
        "sms",
        "whatsapp",
        "calendar",
        "read_notifications",
        "daily_briefing",
        "end_call"          -> ActionClass.PERSONAL

        // Unknown tools are treated as personal (fail-safe default).
        else                -> ActionClass.PERSONAL
    }

    /**
     * Decide whether the current session's speaker may execute [toolName].
     *
     * **Owner-lockout fix.**  Previously this method denied every
     * PERSONAL action unless the speaker scored HIGH_CONFIDENCE_MATCH.
     * On app restart with a saved profile that hadn't loaded yet, or
     * any low-audio / no-enrolment / corrupt-profile scenario, the
     * owner was hard-locked out of basic commands like "send WhatsApp"
     * or "remind me".  This was the user-reported regression.
     *
     * The fix:
     *   - LOW_CONFIDENCE / UNKNOWN are now treated as OWNER_ASSUMED.
     *   - Deny only runs when [strictMode] is on AND the command is
     *     HIGH_RISK in the richer policy.  Strict mode defaults to OFF.
     *   - Even then we prefer asking "Who is this?" over speaking a
     *     hostile denial — wired in JarvisRuntime via
     *     [com.jarvis.assistant.speaker.trust.CommandPermissionPolicy].
     *
     * Kept for back-compat with existing call sites that still pass
     * SpeakerIdentityResult + toolName.  New code should call
     * CommandPermissionPolicy directly.
     */
    fun evaluate(
        result: SpeakerIdentityResult,
        toolName: String,
        strictMode: Boolean = false,
    ): PolicyDecision {
        // PUBLIC tools always allowed regardless of identity.
        if (classifyAction(toolName) == ActionClass.PUBLIC)
            return PolicyDecision(allowed = true)

        // Map the recogniser's three-band result to the richer trust
        // state.  HIGH_CONFIDENCE → VOICE_MATCHED, the two ambiguous
        // bands → OWNER_ASSUMED (safe fallback that does NOT lock out).
        val trust = when (result.band) {
            SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH ->
                com.jarvis.assistant.speaker.trust.VoiceTrustState.VOICE_MATCHED
            SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS,
            SpeakerIdentityResult.ConfidenceBand.UNKNOWN ->
                com.jarvis.assistant.speaker.trust.VoiceTrustState.OWNER_ASSUMED
        }

        val decision = com.jarvis.assistant.speaker.trust
            .CommandPermissionPolicy.evaluate(
                toolName   = toolName,
                trust      = trust,
                strictMode = strictMode,
            )
        return when (decision) {
            is com.jarvis.assistant.speaker.trust
                .CommandPermissionPolicy.Decision.Allow ->
                PolicyDecision(allowed = true)
            is com.jarvis.assistant.speaker.trust
                .CommandPermissionPolicy.Decision.Deny ->
                PolicyDecision(allowed = false, denyReason = decision.reason)
            is com.jarvis.assistant.speaker.trust
                .CommandPermissionPolicy.Decision.ReauthRequired ->
                // Old contract has no "reauth" verdict; surface as
                // not-allowed-yet so legacy callers speak the friendly
                // prompt.  New callers use CommandPermissionPolicy
                // directly for the full pending-command resume flow.
                PolicyDecision(allowed = false, denyReason = decision.prompt)
        }
    }

    /** Convenience: true if the tool is allowed for this result. */
    fun isAllowed(
        result: SpeakerIdentityResult,
        toolName: String,
        strictMode: Boolean = false,
    ): Boolean = evaluate(result, toolName, strictMode).allowed
}
