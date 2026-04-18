package com.jarvis.assistant.proactive

/**
 * InterruptLevel — describes how intrusively a proactive action should be
 * delivered to the user.
 *
 * Maps to the Jarvis proactivity policy (observe everything → act rarely):
 *
 *   NONE    = L0 silent awareness / L1 passive context.  No surfaced action.
 *             The engine still logs, learns, and adjusts defaults — it just
 *             does not emit a notification or speech.  Should be the common
 *             case (~80% of events).
 *   PASSIVE = L2 soft suggestion.  Surface quietly (notification / ambient)
 *             without spoken output.  Easy to ignore, low friction.
 *   ACTIVE  = L3 contextual assistance or L4 active intervention.  Spoken TTS
 *             output; reserved for moments the user will plausibly engage
 *             with (Bluetooth just connected, battery critical, imminent
 *             reminder, etc.).
 */
enum class InterruptLevel {
    /** Event score is below [ProactiveConfig.passiveThreshold]; discard. */
    NONE,

    /** Score is between [ProactiveConfig.passiveThreshold] and [ProactiveConfig.activeThreshold]. */
    PASSIVE,

    /** Score exceeds [ProactiveConfig.activeThreshold]; speak aloud. */
    ACTIVE
}
