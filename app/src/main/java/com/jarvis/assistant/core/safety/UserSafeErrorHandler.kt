package com.jarvis.assistant.core.safety

import android.util.Log
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmRateLimitedException
import java.io.IOException

/**
 * UserSafeErrorHandler — translate Throwables to short, calm, friendly
 * messages AND classify their severity so the upstream reporter knows
 * whether to file a GitHub issue.
 *
 * Pure / Android-free.  Callers (TTS pipeline, OpenClaw fallback, the
 * Todoist router, …) hand in the Throwable + the [Area] that caught it
 * and get back a [SafeError] with the spoken text the user will hear.
 *
 * The spoken text is always one of the canonical strings — see
 * [Friendly] — never a string derived from the exception's own
 * `message` field.  Exception messages frequently contain stack info,
 * URLs, JSON, and tokens; we refuse to surface them.
 */
object UserSafeErrorHandler {

    private const val TAG = "UserSafeError"

    /** Functional area that caught the exception.  Drives the friendly
     *  copy AND the GitHub issue label. */
    enum class Area(val label: String) {
        LLM("area:llm"),
        OPENCLAW("area:openclaw"),
        TODOIST("area:todoist"),
        HOME_ASSISTANT("area:home-assistant"),
        SPEECH("area:speech"),
        TTS("area:tts"),
        NETWORK("area:network"),
        PERMISSION("area:permission"),
        LOCAL_TOOL("area:local-tool"),
        PROACTIVITY("area:proactivity"),
        GITHUB("area:github"),
        ROUTING("area:routing"),
        UNKNOWN("area:unknown"),
    }

    /** How serious the failure is — controls whether to file an issue. */
    enum class Severity { USER_FIXABLE, TRANSIENT, BUG, EXPECTED_NO_MATCH }

    data class SafeError(
        /** Canonical short friendly text — safe to speak directly. */
        val friendlyText: String,
        val area: Area,
        val severity: Severity,
        /** Subsystem string for the issue reporter, e.g. "llm.minimax". */
        val subsystem: String,
        /** Short category string for issue dedupe ("rate_limit", "auth"). */
        val category: String,
    )

    /** Canonical friendly copy lines.  Keep these short and calm. */
    object Friendly {
        const val NETWORK              = "Connection issue. I'll try again shortly."
        const val LLM_RATE_LIMITED     = "I'm at my limit for that right now."
        const val LLM_GENERIC          = "I couldn't answer that just now."
        const val OPENCLAW_UNAVAILABLE = "I can't reach OpenClaw right now."
        const val TODOIST_OFFLINE      = "I've saved that locally and I'll sync it later."
        const val TODOIST_AUTH         = "Todoist needs reconnecting."
        const val PERMISSION_MISSING   = "I need permission for that."
        const val CONTACT_NOT_FOUND    = "I couldn't find that contact."
        const val CONTACT_AMBIGUOUS    = "I found more than one. Which one?"
        const val TOOL_FAILED          = "That didn't work. I've logged it."
        const val GENERIC_BUG          = "Something went wrong. I've logged it."
        const val UNSUPPORTED          = "I can't do that yet."
    }

    /**
     * Translate [throwable] caught in [area] into a [SafeError].  The
     * caller can speak [SafeError.friendlyText] and report the rest via
     * the existing [com.jarvis.assistant.reporting.github.IssueReporter].
     *
     * Caller responsibility: PASS the original Throwable to the issue
     * reporter — we DO NOT include its message in the spoken text but
     * the reporter needs it for the sanitized stack trace.
     */
    fun handle(throwable: Throwable?, area: Area): SafeError {
        val t = throwable
        Log.d(TAG, "[USER_SAFE_HANDLE] area=$area type=${t?.javaClass?.simpleName ?: "null"}")

        // Order matters — most specific first.  Each branch picks a
        // category string that the GitHub issue reporter fingerprints
        // against, so the names should stay stable across releases.
        return when {
            // LLM-specific rate limit — has a dedicated exception type.
            t is LlmRateLimitedException -> SafeError(
                friendlyText = Friendly.LLM_RATE_LIMITED,
                area         = area,
                severity     = Severity.TRANSIENT,
                subsystem    = "llm",
                category     = "rate_limit",
            )

            // Other LLM errors that came up through providers.
            t is LlmException -> SafeError(
                friendlyText = Friendly.LLM_GENERIC,
                area         = Area.LLM,
                severity     = if (looksLikeAuth(t.message)) Severity.USER_FIXABLE
                               else                         Severity.TRANSIENT,
                subsystem    = "llm",
                category     = if (looksLikeAuth(t.message)) "auth" else "generic",
            )

            // SecurityException — Android permission denial.
            t is SecurityException -> SafeError(
                friendlyText = Friendly.PERMISSION_MISSING,
                area         = Area.PERMISSION,
                severity     = Severity.USER_FIXABLE,
                subsystem    = "permission",
                category     = "denied",
            )

            // Network / IO at any layer.
            t is IOException -> SafeError(
                friendlyText = Friendly.NETWORK,
                area         = area,
                severity     = Severity.TRANSIENT,
                subsystem    = area.name.lowercase(),
                category     = "network",
            )

            // Coroutine cancellation is NOT a bug — caller cancelled us.
            t is kotlinx.coroutines.CancellationException -> SafeError(
                friendlyText = "",
                area         = area,
                severity     = Severity.EXPECTED_NO_MATCH,
                subsystem    = area.name.lowercase(),
                category     = "cancelled",
            )

            // Fallback per area — explicit friendly mapping.
            area == Area.OPENCLAW -> SafeError(
                friendlyText = Friendly.OPENCLAW_UNAVAILABLE,
                area         = area,
                severity     = Severity.TRANSIENT,
                subsystem    = "openclaw",
                category     = "unavailable",
            )
            area == Area.TODOIST -> SafeError(
                friendlyText = Friendly.TODOIST_OFFLINE,
                area         = area,
                severity     = Severity.TRANSIENT,
                subsystem    = "todoist",
                category     = "unavailable",
            )

            // Truly unknown — generic bug message + BUG severity so the
            // reporter files an issue.
            else -> SafeError(
                friendlyText = Friendly.GENERIC_BUG,
                area         = area,
                severity     = Severity.BUG,
                subsystem    = area.name.lowercase(),
                category     = t?.javaClass?.simpleName ?: "unknown",
            )
        }
    }

    /**
     * Surface a "found nothing" outcome — the user asked for something
     * we don't support locally and routing chose not to escalate.  Not
     * an error, not reported.
     */
    fun noMatch(area: Area, hint: String = Friendly.UNSUPPORTED): SafeError =
        SafeError(
            friendlyText = hint,
            area         = area,
            severity     = Severity.EXPECTED_NO_MATCH,
            subsystem    = area.name.lowercase(),
            category     = "no_match",
        )

    private fun looksLikeAuth(msg: String?): Boolean {
        val m = msg.orEmpty()
        return m.contains("HTTP 401") || m.contains("HTTP 403") ||
            m.contains("unauthorized", ignoreCase = true) ||
            m.contains("forbidden", ignoreCase = true) ||
            m.contains("invalid_api_key", ignoreCase = true)
    }
}
