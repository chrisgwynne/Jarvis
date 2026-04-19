package com.jarvis.assistant.reporting.github

/**
 * Internal value type produced by [IssueReporter.report*] and consumed by the
 * fingerprint / dedupe / rate-limit / submit pipeline.
 *
 * Everything the GitHub issue body needs is derived from these fields plus a
 * few environment lookups (app version, Android version, device model) made
 * lazily inside [CrashReportBuilder] — so an IssueReport object is cheap to
 * construct on a hot error path and safe to hold on the main thread.
 *
 * @property severity      Gate #1 — only FATAL / HIGH escalate; LOW / MEDIUM
 *                         are logged locally.
 * @property subsystem     Logical area that produced the error, e.g. "tts",
 *                         "wake_word", "service_startup".  Drives label
 *                         selection and fingerprint scoping.
 * @property category      Short stable category string inside the subsystem,
 *                         e.g. "TTS_INIT_FAILED", "STT_PIPELINE_BROKEN".
 *                         Different categories get different fingerprints.
 * @property message       One-sentence human-readable summary.  Used as the
 *                         issue title after normalisation.  Not a stack trace.
 * @property throwable     Optional — the exception, if any.  Used for the
 *                         stack trace in the issue body and for fingerprinting
 *                         on the top stack frame + exception class.
 * @property metadata      Optional free-form key/value context ("voice",
 *                         "route", "activeFlow", …).  Serialised into the
 *                         issue body.  Secrets MUST NOT be placed here.
 * @property timestampMs   [System.currentTimeMillis] at capture.
 * @property isCrash       True only for the uncaught-exception path.  Drives
 *                         the "crash" label and the issue-body preamble.
 */
data class IssueReport(
    val severity  : ErrorSeverity,
    val subsystem : String,
    val category  : String,
    val message   : String,
    val throwable : Throwable? = null,
    val metadata  : Map<String, String> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis(),
    val isCrash   : Boolean = false
)
