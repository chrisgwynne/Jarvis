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
     * [result] should be the [SpeakerSessionContext.result] from the active session.
     */
    fun evaluate(result: SpeakerIdentityResult, toolName: String): PolicyDecision {
        if (classifyAction(toolName) == ActionClass.PUBLIC)
            return PolicyDecision(allowed = true)

        return when (result.band) {
            SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH ->
                PolicyDecision(allowed = true)

            SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS ->
                PolicyDecision(
                    allowed    = false,
                    denyReason = "I'm not sure who I'm talking to. " +
                                 "I can help with general questions, but for personal actions " +
                                 "I need to be sure of your identity."
                )

            SpeakerIdentityResult.ConfidenceBand.UNKNOWN ->
                // This branch is only reached when voice profiles ARE enrolled but the
                // current speaker wasn't recognised.  JarvisRuntime applies owner trust
                // mode (HIGH_CONFIDENCE synthetic result) when nobody is enrolled yet,
                // so this message is only ever spoken to a genuinely unrecognised voice.
                PolicyDecision(
                    allowed    = false,
                    denyReason = "I don't recognise your voice. " +
                                 "I can answer general questions, but personal actions like " +
                                 "calls or messages require voice identification."
                )
        }
    }

    /** Convenience: true if the tool is allowed for this result. */
    fun isAllowed(result: SpeakerIdentityResult, toolName: String): Boolean =
        evaluate(result, toolName).allowed
}
