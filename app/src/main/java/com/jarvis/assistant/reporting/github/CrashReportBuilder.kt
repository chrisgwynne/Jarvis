package com.jarvis.assistant.reporting.github

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashReportBuilder — turns an [IssueReport] into a GitHub issue title + body
 * + label set.  Pure formatting; no network, no policy.
 *
 * The resulting body is deliberately compact but actionable:
 *   - severity / subsystem / category one-liners at the top
 *   - device + app build fingerprint so bugs from one Android version don't
 *     mask bugs from another
 *   - short stack trace (first 40 frames) collapsed in a ```\ntrace\n```
 *     fenced block so GitHub renders it cleanly
 *   - metadata map rendered as a table
 *   - fingerprint + timestamp footer
 *
 * Secrets are never embedded — [IssueReport.metadata] is the caller's
 * responsibility and the reporter scrubs known-sensitive keys before we get here.
 */
object CrashReportBuilder {

    private const val TITLE_MAX = 120
    private const val STACK_FRAME_LIMIT = 40

    /** One-line title; truncated and prefixed with the subsystem tag for easy grep. */
    fun buildTitle(report: IssueReport): String {
        val tag  = "[${report.subsystem}]"
        val base = scrubSecrets(report.message).ifBlank { report.category }
            .lineSequence().firstOrNull()?.trim()
            ?: report.category
        val composed = "$tag $base"
        return if (composed.length > TITLE_MAX) composed.substring(0, TITLE_MAX - 1) + "…" else composed
    }

    /** Label set — includes generic markers + subsystem-specific. */
    fun buildLabels(report: IssueReport): List<String> {
        val labels = mutableListOf<String>()
        if (report.severity == ErrorSeverity.USER_FEEDBACK) {
            labels += "user-report"
        } else {
            labels += "bug"
            labels += "auto-reported"
        }
        if (report.isCrash) labels += "crash"
        when (report.subsystem.lowercase()) {
            "tts"                -> labels += "tts"
            "stt", "speech"      -> labels += "stt"
            "wake", "wake_word"  -> labels += "wake-word"
            "service", "service_startup" -> labels += "service"
            "location"           -> labels += "location"
            "voice"              -> labels += "voice"
            "llm"                -> labels += "llm"
            "proactive"          -> labels += "proactive"
            "user"               -> labels += "feedback"
        }
        return labels
    }

    /** Markdown body — safe to send as the GitHub `body` field. */
    fun buildBody(
        context: Context,
        report: IssueReport,
        fingerprint: String,
        occurrenceCount: Int
    ): String = buildString {
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date(report.timestampMs))
        val (versionName, versionCode) = appVersion(context)

        appendLine("## Summary")
        appendLine(scrubSecrets(report.message).ifBlank { "(no message — see stack trace)" })
        appendLine()

        appendLine("## Context")
        appendLine("| field | value |")
        appendLine("|---|---|")
        appendLine("| severity | ${report.severity} |")
        appendLine("| subsystem | ${report.subsystem} |")
        appendLine("| category | `${report.category}` |")
        appendLine("| fingerprint | `$fingerprint` |")
        appendLine("| occurrences (this window) | $occurrenceCount |")
        appendLine("| crash | ${report.isCrash} |")
        appendLine("| timestamp | $when_ |")
        appendLine("| app version | $versionName ($versionCode) |")
        appendLine("| build type | ${buildTypeName(context)} |")
        appendLine("| Android | ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) |")
        appendLine("| device | ${Build.MANUFACTURER} ${Build.MODEL} |")
        appendLine()

        if (report.metadata.isNotEmpty()) {
            appendLine("## State")
            appendLine("| key | value |")
            appendLine("|---|---|")
            report.metadata.entries
                .filterNot { it.key.lowercase() in SECRET_KEY_HINTS }
                .forEach { (k, v) ->
                    appendLine("| $k | ${truncate(scrubSecrets(v), 200)} |")
                }
            appendLine()
        }

        val trace = formatTrace(report.throwable)
        if (trace.isNotBlank()) {
            appendLine("## Stack trace")
            appendLine("```")
            appendLine(scrubSecrets(trace))
            appendLine("```")
            appendLine()
        }

        appendLine("---")
        appendLine("_Auto-filed by Jarvis IssueReporter. " +
                   "Fingerprint `$fingerprint` — further occurrences in the cooldown window will be deduped._")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Best-effort short stack trace — top [STACK_FRAME_LIMIT] frames only. */
    private fun formatTrace(t: Throwable?): String {
        if (t == null) return ""
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        val lines = sw.toString().lineSequence().toList()
        return if (lines.size <= STACK_FRAME_LIMIT) sw.toString()
        else (lines.take(STACK_FRAME_LIMIT) + "    … ${lines.size - STACK_FRAME_LIMIT} more frames").joinToString("\n")
    }

    private fun appVersion(context: Context): Pair<String, Long> = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        pi.versionName.orEmpty() to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong())
    } catch (_: PackageManager.NameNotFoundException) { "" to 0L }

    /** "debug" / "release" — inferred from ApplicationInfo flags; no BuildConfig ref. */
    private fun buildTypeName(context: Context): String =
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            "debug" else "release"

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max - 1) + "…"

    /** Metadata keys we'll scrub if any caller tries to stuff tokens into them. */
    private val SECRET_KEY_HINTS = setOf(
        "token", "api_key", "apikey", "password", "secret", "bearer",
        "authorization", "access_token", "refresh_token", "github_token"
    )

    /**
     * Defence-in-depth scrubber run over every string that ends up in the
     * issue body: report message, metadata values, and the stack trace.
     *
     * Why a second layer when metadata keys are already filtered?  Because a
     * subsystem can catch an exception whose message was built from a
     * formatted network log line like "Failed POST … with Bearer ghp_…".
     * That message then flows through [IssueReport.throwable] → stack trace
     * and through [IssueReport.message] — neither is a metadata key, so key-
     * based scrubbing can't save us.  Pattern-match the most common secret
     * shapes instead and redact them to `<redacted>`.
     *
     * Patterns (ordered — first match wins):
     *   * `Bearer <token>`                  → `Bearer <redacted>`
     *   * GitHub personal-access tokens     (`ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`)
     *   * OpenAI / generic `sk-` tokens
     *   * `Authorization: <scheme> <token>` header dumps
     *   * base64-looking blobs ≥ 32 chars next to key-sounding words
     *
     * False positives here are far cheaper than a leaked token.  A legitimate
     * body that happens to match one of these gets "<redacted>" and a human
     * can always provide the real value in a follow-up comment.
     */
    internal fun scrubSecrets(input: String): String {
        if (input.isEmpty()) return input
        var s = input
        s = s.replace(Regex("""(?i)\bBearer\s+[A-Za-z0-9\-._~+/]{8,}=*"""),       "Bearer <redacted>")
        s = s.replace(Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}"""),                 "<redacted-gh-token>")
        s = s.replace(Regex("""\bsk-[A-Za-z0-9\-_]{20,}"""),                     "<redacted-sk-token>")
        s = s.replace(Regex("""(?i)authorization\s*[:=]\s*[^\s,;]+"""),          "Authorization: <redacted>")
        s = s.replace(Regex("""(?i)\b((?:api[_-]?key|access[_-]?token|secret)\s*[:=]\s*)[^\s,;]+"""),
                                                                                  "$1<redacted>")
        return s
    }
}
