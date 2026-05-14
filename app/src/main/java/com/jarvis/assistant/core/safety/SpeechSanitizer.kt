package com.jarvis.assistant.core.safety

import android.util.Log

/**
 * SpeechSanitizer — the last safety net before any text reaches TTS.
 *
 * **Core rule.**  User-facing speech must always be short, calm, and
 * friendly.  Technical detail — stack traces, HTTP bodies, exception
 * class names, JSON payloads, internal package paths, file:line refs,
 * API tokens — must NEVER be spoken.  When this scanner detects any of
 * those it returns the canonical friendly fallback and logs
 * `[TTS_SANITIZED_ERROR_LEAK]` for downstream issue reporting.
 *
 * Pure / no Android dependency.  Cheap to call on every utterance — a
 * single regex pass on text the model produced.  Designed to fail
 * CLOSED: any uncertainty leans toward the friendly message.
 *
 * The friendly fallback is intentionally vague:
 *
 *     "Something went wrong. I've logged it."
 *
 * Callers that want a category-specific message (network, rate limit,
 * Todoist, …) should map via [UserSafeErrorHandler] first; this class
 * only catches what slipped through.
 */
object SpeechSanitizer {

    private const val TAG = "SpeechSanitizer"

    /** Canonical friendly fallback when a leak is detected. */
    const val FRIENDLY_FALLBACK = "Something went wrong. I've logged it."

    /**
     * Result of a sanitisation pass.  The caller can use [hadLeak] to
     * trigger downstream reporting; [text] is always safe to feed TTS.
     */
    data class Result(
        val text: String,
        val hadLeak: Boolean,
        /** First matched leak pattern — for issue dedupe fingerprinting. */
        val leakKind: String? = null,
        /** First ~120 chars of the offending input, sanitised — for the
         *  GitHub issue body (NOT for speech).  Tokens / Bearer headers
         *  are masked. */
        val redactedSnippet: String? = null,
    )

    /**
     * Patterns that mean "this text is a technical error that escaped
     * the friendly mapping".  Each pattern is anchored to be a strong
     * signal — generic words ("error", "failed") DO NOT trigger because
     * they routinely appear in friendly copy ("That didn't work.").
     *
     * Order matters: more specific patterns first so [leakKind] is the
     * tightest available category.
     */
    private val LEAK_PATTERNS: List<Pair<String, Regex>> = listOf(
        // Auth / secret leakage — must always blow up.
        "auth_header"      to Regex("""\bAuthorization\s*:\s*Bearer\b""", RegexOption.IGNORE_CASE),
        "bearer_token"     to Regex("""\bBearer\s+[A-Za-z0-9_\-]{16,}\b"""),
        "api_key_word"     to Regex("""\b(?:api[_\s-]?key|access[_\s-]?token|secret)\b\s*[:=]\s*\S+""", RegexOption.IGNORE_CASE),

        // Stack-trace shape — "\s+at com.X.Y(File.kt:123)" / "\s+at java.X".
        "stack_frame"      to Regex("""\bat\s+(?:com\.|java\.|kotlin\.|sun\.|androidx?\.|org\.)\S+\([^)]*:\d+\)"""),
        "file_line_ref"    to Regex("""\b[A-Z][A-Za-z0-9]+\.kt:\d+\b"""),

        // Exception class names + raw "Exception" word.
        "exception_class"  to Regex("""\b(?:[A-Z][A-Za-z0-9]+)?Exception(?::\s|\s+at\s)"""),
        "exception_token"  to Regex("""\bException\b""", RegexOption.IGNORE_CASE),
        "stacktrace_word"  to Regex("""\bstack\s*trace\b""", RegexOption.IGNORE_CASE),

        // Internal package paths.
        "package_com_jarvis" to Regex("""\bcom\.jarvis\.""", RegexOption.IGNORE_CASE),
        "package_kotlin"     to Regex("""\bkotlin\.\w"""),
        "package_java"       to Regex("""\bjava\.(?:lang|util|io|net)\."""),
        "package_androidx"   to Regex("""\b(?:android|androidx)\."""),

        // HTTP status codes spoken as "HTTP 4xx/5xx".
        "http_status"      to Regex("""\bHTTP\s+[45]\d\d\b"""),

        // Raw JSON error bodies.
        "json_error_body"  to Regex("""\{[^{}]*?"(?:error|message|status_code|errorMessage)"\s*:""", RegexOption.IGNORE_CASE),

        // URL with credentials embedded.
        "url_with_secret"  to Regex("""\bhttps?://[^\s/]+:\S+@""", RegexOption.IGNORE_CASE),
    )

    /**
     * Run [raw] through the sanitiser.  Returns the original text if
     * clean, the friendly fallback if any leak pattern matched.
     */
    fun sanitizeForSpeech(raw: String): Result {
        if (raw.isBlank()) return Result(raw, hadLeak = false)
        for ((kind, rx) in LEAK_PATTERNS) {
            val m = rx.find(raw) ?: continue
            val snippet = redact(raw).take(120)
            Log.w(TAG, "[TTS_SANITIZED_ERROR_LEAK] kind=$kind " +
                "matchedIndex=${m.range.first} snippet=\"$snippet\"")
            return Result(
                text             = FRIENDLY_FALLBACK,
                hadLeak          = true,
                leakKind         = kind,
                redactedSnippet  = snippet,
            )
        }
        return Result(raw, hadLeak = false)
    }

    /**
     * Redact obvious secrets from arbitrary text — used when forwarding
     * a leaked utterance into a GitHub issue body.  Public so the
     * issue-reporter pipeline can re-use the same masking rules.
     */
    fun redact(raw: String): String =
        raw
            .replace(Regex("""(?i)\bBearer\s+[A-Za-z0-9_\-]{6,}\b"""), "Bearer ***")
            .replace(Regex("""(?i)\bAuthorization\s*:\s*[^\s]+"""), "Authorization: ***")
            .replace(Regex("""(?i)\b(api[_\s-]?key|access[_\s-]?token|secret)\s*[:=]\s*\S+"""),
                "$1: ***")
            .replace(Regex("""\bhttps?://[^\s/]+:[^\s@]+@"""), "https://***@")
}
