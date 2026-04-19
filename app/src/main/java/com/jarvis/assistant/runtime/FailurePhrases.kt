package com.jarvis.assistant.runtime

/**
 * FailurePhrases — single source for the short, voice-friendly strings
 * the various tools and error paths surface when something goes wrong.
 *
 * Keeping these together matters because the Jarvis voice is consistent
 * by policy (see `CLAUDE.md`): every error should sound like the same
 * person speaking.  When new tools land they should pick an entry here
 * rather than invent a new fallback string.
 */
object FailurePhrases {

    // ── Generic ────────────────────────────────────────────────────────────

    /** All-purpose failure when we don't have anything more specific. */
    const val GENERIC = "That didn't work."

    /** Internal / unexpected error; matches LlmRouter and subagents. */
    const val SOMETHING_WENT_WRONG = "Something went wrong."

    // ── Connectivity / system ──────────────────────────────────────────────

    const val AUDIO_SERVICE_UNAVAILABLE  = "Audio service unavailable."
    const val CAMERA_SERVICE_UNAVAILABLE = "Camera service unavailable."
    const val NO_FLASH                   = "No flash on this phone."

    // ── Tools ──────────────────────────────────────────────────────────────

    const val MEDIA_CONTROL_FAILED = "I couldn't control the media."
    const val FLASHLIGHT_DIDNT_RESPOND = "Flashlight didn't respond."
    const val RECORDING_FAILED = "Recording failed to start."
    const val IMAGE_ANALYSIS_FAILED = "Image was captured but I couldn't analyse it."

    // ── App launching ──────────────────────────────────────────────────────

    fun appNotInstalled(appNameCapitalised: String) =
        "$appNameCapitalised isn't installed on this phone."
}
