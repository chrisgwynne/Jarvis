package com.jarvis.assistant.reporting.github

/**
 * Severity of a reported error.  Drives whether the [IssueReporter] escalates
 * to a GitHub issue or keeps the report local.
 *
 * Mapping to reporting behaviour:
 *
 *   * [LOW]    — debug / warning level.  Never creates an issue; counted locally.
 *   * [MEDIUM] — recoverable failure.  Never creates an issue; counted locally so
 *                escalation is possible if the same fingerprint repeats often.
 *   * [HIGH]   — serious non-fatal (e.g. TTS re-init failed 3× in a row).  Creates
 *                an issue only after [IssueRateLimiter.repetitionThreshold] hits
 *                inside the repetition window.
 *   * [FATAL]  — uncaught crash or an explicitly-fatal subsystem assertion.
 *                Creates an issue on the first occurrence (still deduped against
 *                recent identical reports).
 *
 * The severity field is the first gate; dedupe and rate-limit come after.
 */
enum class ErrorSeverity {
    LOW,
    MEDIUM,
    HIGH,
    FATAL,
    /** Explicitly requested by the user via voice — always files immediately, no threshold. */
    USER_FEEDBACK;

    val createsIssueImmediately: Boolean get() = this == FATAL || this == USER_FEEDBACK
    val createsIssueAfterRepeats: Boolean get() = this == HIGH || this == MEDIUM
    /** LOW never files. Everything else is eligible. */
    val eligibleForGitHub: Boolean get() = this != LOW
}
