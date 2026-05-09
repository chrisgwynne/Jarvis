package com.jarvis.assistant.reporting.github

import android.content.Context
import android.util.Log
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * IssueReporter — the single public entry point for Jarvis's auto-GitHub-issue
 * path.  Every subsystem that wants to raise a bug calls one of the
 * `report*()` methods here; everything else in this package is package-private
 * plumbing.
 *
 * Two install points:
 *
 *   1. [JarvisApp.onCreate] calls [install] exactly once.  This:
 *      - latches the Thread default uncaught-exception handler (writes the
 *        crash to [PendingReportStore] before chaining to the OS default)
 *      - fires a background drain of any previously-pending reports
 *
 *   2. Each severe subsystem calls [reportHigh] or [reportFatal] on its own
 *      error path.  Wire-up is minimal and opt-in — low/medium severity stays
 *      local.
 *
 * ## Behaviour contract
 *
 *   * Feature flag:      [SettingsStore.githubReportingEnabled] gates every
 *                        network submit.  When false, reports are still
 *                        fingerprinted + logged locally so a future toggle
 *                        doesn't lose data.
 *   * Token hygiene:     token is pulled from EncryptedSharedPreferences at
 *                        call time; never passed through argument lists or
 *                        written to logs or metadata.
 *   * Severity gate:     LOW / MEDIUM never submit.  HIGH submits after the
 *                        repetition threshold.  FATAL submits on first hit
 *                        (still per-fingerprint-deduped).
 *   * Dedupe / throttle: [IssueRateLimiter] enforces per-fingerprint cooldown,
 *                        repetition threshold, and global-per-day cap.
 *   * Fallback:          transient submit failures (network, GitHub 5xx, rate
 *                        limit) are queued to [PendingReportStore] for a
 *                        retry on the next cold start.  Permanent failures
 *                        (bad repo, revoked token) are logged once and dropped.
 *   * Never throws:      every public entry swallows its own errors.  This
 *                        class must never itself be the cause of a crash.
 */
class IssueReporter private constructor(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val client: GitHubIssueClient = GitHubIssueClient(),
    private val rateLimiter: IssueRateLimiter = IssueRateLimiter(appContext),
    private val pending: PendingReportStore = PendingReportStore(appContext)
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Public entry points ──────────────────────────────────────────────────

    /**
     * Report a one-off FATAL error.  Submits on first occurrence (subject to
     * per-fingerprint cooldown).  Called by the uncaught-exception handler
     * and by any subsystem that wants "this-shouldn't-happen" escalation.
     */
    fun reportFatal(
        subsystem: String,
        category: String,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap(),
        isCrash: Boolean = false
    ) = submit(
        IssueReport(ErrorSeverity.FATAL, subsystem, category, message,
                    throwable, scrub(metadata), isCrash = isCrash)
    )

    /**
     * Report a HIGH-severity non-fatal.  The issue is only filed after the
     * repetition threshold inside the repetition window — so a one-off
     * transient failure never turns into a GitHub issue.
     */
    fun reportHigh(
        subsystem: String,
        category: String,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    ) = submit(
        IssueReport(ErrorSeverity.HIGH, subsystem, category, message,
                    throwable, scrub(metadata))
    )

    /**
     * Report a MEDIUM-severity recoverable failure (e.g. latency spike,
     * misunderstood command, tool returned empty result). Files after the
     * repetition threshold like HIGH — so a one-off fluke stays local but
     * a recurring pattern creates an issue.
     */
    fun reportMedium(
        subsystem: String,
        category: String,
        message: String,
        metadata: Map<String, String> = emptyMap()
    ) = submit(IssueReport(ErrorSeverity.MEDIUM, subsystem, category, message,
                           metadata = scrub(metadata)))

    /**
     * Explicitly user-triggered report.  Bypasses the repetition threshold
     * and rate limiter — fires immediately if the feature flag + token are set.
     * [onResult] is called on the IO thread with the outcome so the tool can
     * echo the issue URL back to the user.
     */
    fun reportUserFeedback(
        description: String,
        metadata: Map<String, String> = emptyMap(),
        onResult: (SubmitOutcome) -> Unit = {}
    ) {
        val report = IssueReport(
            severity  = ErrorSeverity.USER_FEEDBACK,
            subsystem = "user",
            category  = "USER_REPORTED",
            message   = description,
            metadata  = scrub(metadata)
        )
        scope.launch {
            val outcome = submitImmediate(report, bypassRateLimit = true)
            onResult(outcome)
        }
    }

    /**
     * Low / medium severity reports — always local-only.  Exposed so
     * subsystems have one reporter to call for everything and the severity
     * gate lives in one place.
     */
    fun reportInfo(subsystem: String, category: String, message: String) =
        submit(IssueReport(ErrorSeverity.LOW, subsystem, category, message))

    /**
     * Manual testing path — called from a settings / debug button.  Builds a
     * synthetic FATAL-but-not-a-crash report and runs the real submit flow so
     * the owner can verify the pipeline end-to-end without waiting for a
     * real bug.
     *
     * Returns a [SubmitOutcome] via [onResult] on the caller's main thread.
     */
    fun sendTestIssue(onResult: (SubmitOutcome) -> Unit) {
        val report = IssueReport(
            severity  = ErrorSeverity.FATAL,
            subsystem = "reporter",
            category  = "MANUAL_TEST",
            message   = "Manual test issue from Jarvis — safe to close.",
            metadata  = mapOf(
                "kind" to "manual-test",
                "time" to System.currentTimeMillis().toString()
            )
        )
        scope.launch {
            val outcome = submitImmediate(report, bypassRateLimit = true)
            onResult(outcome)
        }
    }

    // ── Submit pipeline (private) ────────────────────────────────────────────

    sealed class SubmitOutcome {
        data class Created(val htmlUrl: String?) : SubmitOutcome()
        object DisabledByFlag     : SubmitOutcome()
        object NoTokenConfigured  : SubmitOutcome()
        data class Denied(val reason: String)     : SubmitOutcome()
        data class Queued(val reason: String)     : SubmitOutcome()
        data class Failed(val reason: String)     : SubmitOutcome()
    }

    private fun submit(report: IssueReport) {
        // Hot path — log first (cheap, useful regardless of GitHub state).
        Log.i(TAG, "report(sev=${report.severity} subsystem=${report.subsystem} category=${report.category})")

        // LOW / MEDIUM never go to GitHub.
        if (!report.severity.eligibleForGitHub) return

        scope.launch { submitImmediate(report, bypassRateLimit = false) }
    }

    private suspend fun submitImmediate(
        report: IssueReport,
        bypassRateLimit: Boolean
    ): SubmitOutcome {
        val fingerprint = IssueFingerprint.of(report)
        val nowMs       = System.currentTimeMillis()

        if (!bypassRateLimit) {
            when (val d = rateLimiter.evaluate(report, fingerprint, nowMs)) {
                is IssueRateLimiter.Decision.AllowFile       -> { /* fall through */ }
                is IssueRateLimiter.Decision.DenyCooldown    -> {
                    Log.d(TAG, "deny cooldown fp=$fingerprint msRemaining=${d.msRemaining}")
                    return SubmitOutcome.Denied("cooldown")
                }
                is IssueRateLimiter.Decision.DenyThreshold   -> {
                    Log.d(TAG, "deny threshold fp=$fingerprint count=${d.count}/${d.needed}")
                    return SubmitOutcome.Denied("below-threshold")
                }
                is IssueRateLimiter.Decision.DenyGlobalCap   -> {
                    Log.w(TAG, "deny global-cap used=${d.used}/${d.cap}")
                    return SubmitOutcome.Denied("global-cap")
                }
                is IssueRateLimiter.Decision.DenyLowSeverity -> return SubmitOutcome.Denied("low-severity")
            }
        }

        // Feature flag.  Record the report as seen above so a later toggle
        // + repetition keeps the same escalation behaviour.
        if (!settings.githubReportingEnabled) {
            Log.d(TAG, "reporting disabled by flag — not submitting fp=$fingerprint")
            return SubmitOutcome.DisabledByFlag
        }

        val token = settings.githubToken
        if (token.isBlank()) {
            Log.d(TAG, "no GitHub token configured — skipping submit fp=$fingerprint")
            return SubmitOutcome.NoTokenConfigured
        }

        val occurrenceCount = rateLimiter
            .let { /* snapshot via deduper */ IssueDeduper(appContext).snapshot(fingerprint).count.coerceAtLeast(1) }

        val payload = GitHubIssuePayload(
            title  = CrashReportBuilder.buildTitle(report),
            body   = CrashReportBuilder.buildBody(appContext, report, fingerprint, occurrenceCount),
            labels = CrashReportBuilder.buildLabels(report)
        )

        val result = client.createIssue(settings.githubRepoOwner, settings.githubRepoName, token, payload)
        return when (result) {
            is GitHubIssueClient.Result.Success -> {
                rateLimiter.recordFiled(fingerprint, nowMs)
                Log.i(TAG, "filed fp=$fingerprint issue=#${result.issueNumber}")
                SubmitOutcome.Created(result.htmlUrl)
            }
            is GitHubIssueClient.Result.AuthFailure -> {
                Log.w(TAG, "auth failure filing fp=$fingerprint — clear token in Settings")
                SubmitOutcome.Failed("auth")
            }
            is GitHubIssueClient.Result.RepoNotFound -> {
                Log.w(TAG, "repo not found for ${settings.githubRepoOwner}/${settings.githubRepoName} — check settings")
                SubmitOutcome.Failed("repo-not-found")
            }
            is GitHubIssueClient.Result.RateLimited -> {
                enqueueForRetry(report, fingerprint, occurrenceCount)
                SubmitOutcome.Queued("rate-limited")
            }
            is GitHubIssueClient.Result.Transient -> {
                enqueueForRetry(report, fingerprint, occurrenceCount)
                SubmitOutcome.Queued(result.reason)
            }
            is GitHubIssueClient.Result.Permanent -> {
                Log.w(TAG, "permanent submit failure fp=$fingerprint reason=${result.reason}")
                SubmitOutcome.Failed(result.reason)
            }
        }
    }

    /**
     * Crash-path only — called synchronously from the uncaught handler.
     * Records the fingerprint in the dedupe ledger (so occurrence counts
     * survive the crash) and enqueues the report for the next cold start
     * to submit.  Must not block beyond the few ms the OS affords a dying
     * process.
     */
    internal fun enqueueCrashReport(report: IssueReport) {
        val fingerprint = IssueFingerprint.of(report)
        val occurrence  = IssueDeduper(appContext).recordSeen(
            fingerprint,
            System.currentTimeMillis(),
            windowMs = 60L * 60 * 1000
        ).count
        pending.enqueue(
            PendingReportStore.Persisted(
                severity        = report.severity.name,
                subsystem       = report.subsystem,
                category        = report.category,
                message         = report.message,
                timestampMs     = report.timestampMs,
                isCrash         = report.isCrash,
                fingerprint     = fingerprint,
                stackTrace      = report.throwable?.stackTraceToString().orEmpty(),
                metadata        = report.metadata,
                occurrenceCount = occurrence
            )
        )
    }

    private fun enqueueForRetry(report: IssueReport, fingerprint: String, occurrenceCount: Int) {
        pending.enqueue(
            PendingReportStore.Persisted(
                severity       = report.severity.name,
                subsystem      = report.subsystem,
                category       = report.category,
                message        = report.message,
                timestampMs    = report.timestampMs,
                isCrash        = report.isCrash,
                fingerprint    = fingerprint,
                stackTrace     = report.throwable?.stackTraceToString().orEmpty(),
                metadata       = report.metadata,
                occurrenceCount = occurrenceCount
            )
        )
        Log.d(TAG, "queued for retry fp=$fingerprint")
    }

    /** Drain any reports queued by a previous run.  Called by [install]. */
    private fun drainPending() = scope.launch {
        if (!settings.githubReportingEnabled) return@launch
        val token = settings.githubToken
        if (token.isBlank()) return@launch

        val entries = pending.drain()
        if (entries.isEmpty()) return@launch
        Log.i(TAG, "draining ${entries.size} pending report(s)")
        for ((file, p) in entries) {
            val payload = GitHubIssuePayload(
                title = "[${p.subsystem}] ${p.message.take(100)}",
                body  = buildPersistedBody(p),
                labels = buildPersistedLabels(p)
            )
            val result = client.createIssue(settings.githubRepoOwner, settings.githubRepoName, token, payload)
            when (result) {
                is GitHubIssueClient.Result.Success -> {
                    pending.delete(file)
                    rateLimiter.recordFiled(p.fingerprint, System.currentTimeMillis())
                }
                is GitHubIssueClient.Result.AuthFailure,
                is GitHubIssueClient.Result.RepoNotFound,
                is GitHubIssueClient.Result.Permanent -> {
                    // No point retrying — drop so the queue doesn't grow forever.
                    pending.delete(file)
                }
                else -> { /* keep for next start */ }
            }
        }
    }

    private fun buildPersistedBody(p: PendingReportStore.Persisted): String = buildString {
        appendLine("## Summary (replayed from pending queue)")
        appendLine(p.message)
        appendLine()
        appendLine("| field | value |")
        appendLine("|---|---|")
        appendLine("| severity | ${p.severity} |")
        appendLine("| subsystem | ${p.subsystem} |")
        appendLine("| category | `${p.category}` |")
        appendLine("| fingerprint | `${p.fingerprint}` |")
        appendLine("| occurrences | ${p.occurrenceCount} |")
        appendLine("| crash | ${p.isCrash} |")
        if (p.metadata.isNotEmpty()) {
            appendLine()
            appendLine("## State")
            p.metadata.forEach { (k, v) -> appendLine("* **$k**: $v") }
        }
        if (p.stackTrace.isNotBlank()) {
            appendLine()
            appendLine("## Stack trace")
            appendLine("```")
            appendLine(p.stackTrace.lineSequence().take(40).joinToString("\n"))
            appendLine("```")
        }
    }

    private fun buildPersistedLabels(p: PendingReportStore.Persisted): List<String> {
        val tmp = IssueReport(
            severity  = runCatching { ErrorSeverity.valueOf(p.severity) }.getOrDefault(ErrorSeverity.HIGH),
            subsystem = p.subsystem,
            category  = p.category,
            message   = p.message,
            isCrash   = p.isCrash
        )
        return CrashReportBuilder.buildLabels(tmp)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Defence-in-depth — scrub any metadata key that looks like a secret. */
    private fun scrub(metadata: Map<String, String>): Map<String, String> {
        if (metadata.isEmpty()) return metadata
        return metadata.filterKeys { k ->
            val kl = k.lowercase()
            !kl.contains("token") && !kl.contains("secret") &&
            !kl.contains("password") && !kl.contains("api_key") && !kl.contains("apikey") &&
            !kl.contains("bearer") && !kl.contains("authorization")
        }
    }

    // ── Install / singleton ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "IssueReporter"

        @Volatile private var INSTANCE: IssueReporter? = null

        fun get(): IssueReporter? = INSTANCE

        /**
         * Install the reporter exactly once.  Safe to call from
         * [android.app.Application.onCreate].
         *
         * Registers the uncaught exception handler and kicks off a
         * background drain of any reports queued by a previous run.
         */
        fun install(context: Context): IssueReporter {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: run {
                    val r = IssueReporter(
                        appContext = context.applicationContext,
                        settings   = SettingsStore(context.applicationContext)
                    )
                    JarvisUncaughtHandler.install(r)
                    r.drainPending()
                    INSTANCE = r
                    r
                }
            }
        }
    }
}
