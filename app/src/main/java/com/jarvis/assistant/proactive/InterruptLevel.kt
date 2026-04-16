package com.jarvis.assistant.proactive

/**
 * InterruptLevel — describes how intrusively a proactive action should be
 * delivered to the user.
 *
 * The [EventScorer] maps a [ProactiveEvent]'s finalScore to one of these
 * three levels using the thresholds in [ProactiveConfig].
 *
 * - [NONE]    — The event does not meet the minimum threshold; suppress it.
 * - [PASSIVE] — Surface quietly (e.g. a notification or ambient indicator)
 *               without interrupting the user with synthesised speech.
 * - [ACTIVE]  — Interrupt the user with spoken TTS output; reserved for
 *               high-urgency events that require immediate awareness.
 */
enum class InterruptLevel {
    /** Event score is below [ProactiveConfig.passiveThreshold]; discard. */
    NONE,

    /** Score is between [ProactiveConfig.passiveThreshold] and [ProactiveConfig.activeThreshold]. */
    PASSIVE,

    /** Score exceeds [ProactiveConfig.activeThreshold]; speak aloud. */
    ACTIVE
}
