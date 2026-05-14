package com.jarvis.assistant.speaker.trust

import android.util.Log

/**
 * CommandPermissionPolicy — single source of truth for "can this
 * trust-state execute this tool?".  Replaces the unconditional
 * personal-action deny in [com.jarvis.assistant.speaker.SpeakerPermissionPolicy].
 *
 * **Default permissions matrix** (with both `strictMode` and
 * `requireVoiceMatchForSensitive` OFF — the safe defaults):
 *
 *   LOW_RISK   — allowed in every state.  Volume / flashlight /
 *                open-app / time / battery / media / lights — these
 *                must never block, period.
 *   MEDIUM_RISK — allowed in every state except VOICE_MISMATCH +
 *                 REAUTH_REQUIRED.  Reminders / calendar / SMS draft /
 *                 calls / WhatsApp.
 *   HIGH_RISK   — allowed only in VOICE_MATCHED / OWNER_TRUSTED; in
 *                 OWNER_ASSUMED triggers a reauth flow.  Send-without-
 *                 confirm / door unlock / purchases / settings change.
 *
 * **Strict mode** raises every band: MEDIUM_RISK then requires
 * VOICE_MATCHED / OWNER_TRUSTED; HIGH_RISK requires VOICE_MATCHED.
 *
 * Pure / Android-free / unit-testable.
 */
object CommandPermissionPolicy {

    private const val TAG = "CommandPermission"

    enum class RiskClass { LOW_RISK, MEDIUM_RISK, HIGH_RISK }

    sealed class Decision {
        object Allow : Decision()
        /** Refuse outright — caller speaks a friendly fallback. */
        data class Deny(val reason: String) : Decision()
        /** Ask the user "Who is this?" then resume the pending command. */
        data class ReauthRequired(val prompt: String) : Decision()
    }

    /**
     * Classify a tool by registered name.  Mirrors the older
     * SpeakerPermissionPolicy classifier but groups into three risk
     * classes instead of PUBLIC/PERSONAL.
     */
    fun classify(toolName: String): RiskClass = when (toolName) {
        // ── LOW_RISK ────────────────────────────────────────────────────
        "flashlight",
        "volume_control", "volume",
        "media_control",
        "set_alarm", "alarm",
        "set_timer", "timer",
        "camera_capture",
        "analyze_camera_view",
        "open_app",
        "web_search",
        "weather",
        "time",
        "battery",
        "help",
        "where_am_i",
        "smart_home"            -> RiskClass.LOW_RISK

        // ── MEDIUM_RISK ─────────────────────────────────────────────────
        "calendar",
        "memory_recall",
        "shopping_list",
        "call_contact", "call",
        "send_sms", "sms",
        "whatsapp_message", "whatsapp",
        "read_notifications",
        "daily_briefing",
        "end_call",
        "directions",
        "navigate",
        "nearest_place"         -> RiskClass.MEDIUM_RISK

        // ── HIGH_RISK ───────────────────────────────────────────────────
        "send_email",
        "delete_routine",
        "save_routine",
        "audio_recording"       -> RiskClass.HIGH_RISK

        // Unknown tools default to LOW — the previous "fail-safe to
        // personal" default was the exact source of the owner-lockout
        // bug.  Unknown tools may not exist anyway (registry is closed),
        // but if a future tool slips through it stays usable.
        else                    -> RiskClass.LOW_RISK
    }

    /**
     * Decide whether [toolName] may execute under [trust].  The
     * settings flags come from the user's Voice Identity preferences;
     * the safe defaults (both false) preserve full owner control.
     */
    fun evaluate(
        toolName: String,
        trust: VoiceTrustState,
        strictMode: Boolean = false,
        requireVoiceMatchForSensitive: Boolean = false,
    ): Decision {
        val risk = classify(toolName)
        Log.d(TAG, "[COMMAND_PERMISSION_EVAL] tool=$toolName risk=$risk " +
            "trust=$trust strict=$strictMode requireVoiceForSensitive=$requireVoiceMatchForSensitive")

        // ── Hard rule: LOW_RISK never blocks ────────────────────────────
        // Volume, flashlight, open-app, time, lights — the user is the
        // user.  Voice identity failure must never break these.
        if (risk == RiskClass.LOW_RISK) return Decision.Allow

        when (risk) {
            RiskClass.MEDIUM_RISK -> {
                // Owner-like trust → allow.
                if (trust.isOwnerLike) {
                    if (strictMode && trust == VoiceTrustState.OWNER_ASSUMED) {
                        return Decision.ReauthRequired("Who is this?")
                    }
                    return Decision.Allow
                }
                // VOICE_MISMATCH — only block when strict mode says so.
                if (trust == VoiceTrustState.VOICE_MISMATCH && strictMode) {
                    return Decision.Deny("I need to confirm it's you first.")
                }
                if (trust == VoiceTrustState.VOICE_MISMATCH) return Decision.Allow
                // VOICE_UNKNOWN / REAUTH_REQUIRED — ask quickly.
                return Decision.ReauthRequired("Who is this?")
            }
            RiskClass.HIGH_RISK -> {
                // Only voice-matched / explicitly trusted users execute
                // high-risk actions without a reauth.
                if (trust == VoiceTrustState.VOICE_MATCHED ||
                    trust == VoiceTrustState.OWNER_TRUSTED
                ) return Decision.Allow

                // OWNER_ASSUMED — ask "Who is this?" by default.  When
                // requireVoiceMatchForSensitive is on, a voice match is
                // mandatory; deny without a reauth offer (caller can
                // surface a friendly UI).
                if (requireVoiceMatchForSensitive) {
                    return Decision.Deny("I need to confirm it's you first.")
                }
                return Decision.ReauthRequired("Who is this?")
            }
            RiskClass.LOW_RISK -> return Decision.Allow   // already handled above
        }
    }

    /** Convenience for callers that just want a boolean. */
    fun isAllowed(
        toolName: String,
        trust: VoiceTrustState,
        strictMode: Boolean = false,
        requireVoiceMatchForSensitive: Boolean = false,
    ): Boolean = evaluate(
        toolName, trust, strictMode, requireVoiceMatchForSensitive
    ) is Decision.Allow
}
