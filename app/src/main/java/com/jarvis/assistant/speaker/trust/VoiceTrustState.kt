package com.jarvis.assistant.speaker.trust

/**
 * VoiceTrustState — the live "how confident are we about who's talking"
 * signal that gates command execution.
 *
 * Designed around one rule:
 *
 *   **Jarvis must never hard-lock the owner out of local phone commands
 *   because voice recognition is missing, stale, unloaded, or
 *   inconclusive.**
 *
 * Six states in increasing order of certainty:
 *
 *   - [OWNER_ASSUMED]    — startup default and the safe fallback when
 *                          voice identification is unavailable for ANY
 *                          reason (profile not loaded, no enrolment,
 *                          load failure, audio too short, …).
 *   - [OWNER_TRUSTED]    — the user pressed Start or completed a reauth
 *                          ("Who is this?" → "Chris").  Same permissions
 *                          as VOICE_MATCHED.
 *   - [VOICE_MATCHED]    — speaker recognition returned
 *                          HIGH_CONFIDENCE_MATCH against the owner
 *                          profile.  Strongest trust.
 *   - [VOICE_UNKNOWN]    — speaker recognition could not produce a
 *                          confident answer (no audio, no profile,
 *                          confidence below threshold).  Treated as
 *                          OWNER_ASSUMED unless strict mode demands a
 *                          reauth.
 *   - [VOICE_MISMATCH]   — a valid profile was loaded AND a real
 *                          sample was compared AND the confidence was
 *                          clearly below threshold.  Only this is
 *                          treated as "different speaker".
 *   - [REAUTH_REQUIRED]  — a sensitive command was requested in a
 *                          state with insufficient trust and strict
 *                          mode is on.  The runtime parks the pending
 *                          command, asks "Who is this?", and on a
 *                          satisfactory reply elevates to
 *                          OWNER_TRUSTED + executes the parked command.
 */
enum class VoiceTrustState {
    OWNER_ASSUMED,
    OWNER_TRUSTED,
    VOICE_MATCHED,
    VOICE_UNKNOWN,
    VOICE_MISMATCH,
    REAUTH_REQUIRED;

    /** True when this state grants the owner-level permission default. */
    val isOwnerLike: Boolean get() = when (this) {
        OWNER_ASSUMED, OWNER_TRUSTED, VOICE_MATCHED -> true
        VOICE_UNKNOWN, VOICE_MISMATCH, REAUTH_REQUIRED -> false
    }
}
