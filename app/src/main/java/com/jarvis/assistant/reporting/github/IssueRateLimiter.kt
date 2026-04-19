package com.jarvis.assistant.reporting.github

import android.content.Context

/**
 * IssueRateLimiter — the combined dedupe + throttle gate.
 *
 * Policy (all configurable via [Config]):
 *
 *   1. Per-fingerprint cooldown — a fingerprint filed within [perFingerprintCooldownMs]
 *      never files again.  Default 24h.
 *   2. Repetition window        — HIGH-severity reports need [repetitionThreshold]
 *      identical fingerprints inside [repetitionWindowMs] before they escalate.
 *      FATAL reports skip this step and file on first occurrence.
 *   3. Global throttle          — no more than [maxIssuesPerDay] auto-issues in
 *      any rolling [globalWindowMs] window.  Stops a runaway error loop from
 *      firehosing the repo.
 *
 * The limiter is pure policy — it never touches the network.  Persistent
 * state (counts, timestamps) lives in [IssueDeduper].
 */
class IssueRateLimiter(
    context: Context,
    private val deduper: IssueDeduper = IssueDeduper(context),
    private val config: Config = Config()
) {

    data class Config(
        val perFingerprintCooldownMs: Long = 24L * 60 * 60 * 1000,
        val repetitionThreshold    : Int  = 3,
        val repetitionWindowMs     : Long = 60L * 60 * 1000,
        val maxIssuesPerDay        : Int  = 10,
        val globalWindowMs         : Long = 24L * 60 * 60 * 1000
    )

    /** Rolling timestamp log of recent successful filings — used for the global cap. */
    private val recentFilings = ArrayDeque<Long>()
    private val recentFilingsLock = Any()

    /** Reason a decision came out allow/deny — used for local log lines. */
    sealed class Decision {
        object AllowFile : Decision()
        data class DenyCooldown    (val msRemaining: Long) : Decision()
        data class DenyThreshold   (val count: Int, val needed: Int) : Decision()
        data class DenyGlobalCap   (val used: Int, val cap: Int) : Decision()
        object DenyLowSeverity : Decision()
    }

    /**
     * Evaluate a report against the dedupe + rate-limit + severity rules.
     *
     * Side effect: ALWAYS records the fingerprint in the deduper (so future
     * repetitions count) — whether the decision is Allow or Deny.
     */
    fun evaluate(report: IssueReport, fingerprint: String, nowMs: Long = System.currentTimeMillis()): Decision {
        if (!report.severity.eligibleForGitHub) return Decision.DenyLowSeverity

        val seen = deduper.recordSeen(fingerprint, nowMs, config.repetitionWindowMs)

        // 1. Per-fingerprint cooldown
        val msSinceFiled = nowMs - seen.lastFiledMs
        if (seen.lastFiledMs > 0 && msSinceFiled < config.perFingerprintCooldownMs) {
            return Decision.DenyCooldown(config.perFingerprintCooldownMs - msSinceFiled)
        }

        // 2. Repetition threshold — FATAL skips this; HIGH needs N hits in window.
        if (report.severity == ErrorSeverity.HIGH && seen.count < config.repetitionThreshold) {
            return Decision.DenyThreshold(seen.count, config.repetitionThreshold)
        }

        // 3. Global throttle — trim then check.
        val used = synchronized(recentFilingsLock) {
            val cutoff = nowMs - config.globalWindowMs
            while (recentFilings.isNotEmpty() && recentFilings.first() < cutoff) recentFilings.removeFirst()
            recentFilings.size
        }
        if (used >= config.maxIssuesPerDay) {
            return Decision.DenyGlobalCap(used, config.maxIssuesPerDay)
        }

        return Decision.AllowFile
    }

    /**
     * Record that an issue was actually filed.  Must be called after a successful
     * GitHub API response so the per-fingerprint cooldown and the global throttle
     * reflect reality.
     */
    fun recordFiled(fingerprint: String, nowMs: Long = System.currentTimeMillis()) {
        deduper.recordFiled(fingerprint, nowMs)
        synchronized(recentFilingsLock) { recentFilings.addLast(nowMs) }
    }
}
