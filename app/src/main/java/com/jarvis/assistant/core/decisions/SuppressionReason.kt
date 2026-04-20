package com.jarvis.assistant.core.decisions

/**
 * Why a decision landed on "do nothing" or deferred output.
 *
 * Attached to [com.jarvis.assistant.proactive.ProactiveAction.NoAction] and to
 * [Decision.Ignore] / [Decision.Defer] so the telemetry layer can answer the
 * "why didn't Jarvis speak?" question without heuristics. Replaces the
 * previous implicit behaviour where suppression sites returned a bare
 * `NoAction` singleton with no trace.
 */
enum class SuppressionReason {
    UNSPECIFIED,
    STALE,
    EMPTY_CANDIDATES,
    GLOBAL_GAP,
    QUIET_HOURS,
    PRESENCE_BLOCKS,
    DRIVING,
    USER_DISLIKE,
    REPEATED_IGNORE,
    COOLDOWN,
    BELOW_THRESHOLD,
    LEVEL_NONE,
}
