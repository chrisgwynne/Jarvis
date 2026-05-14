package com.jarvis.assistant.voice.routing

import android.util.Log

/**
 * PhoneCapableIntents — canonical list of intents that MUST execute
 * locally on the phone.  Reaching OpenClaw / Hermes / LLM with one of
 * these transcripts is a routing bug, surfaced via the
 * `[INVALID_REMOTE_ROUTE]` log marker AND auto-reported as a GitHub
 * issue so the regression is visible.
 *
 * The allowlist mirrors [InstantCommandRouter.INSTANT_TOOL_INTENTS]
 * plus a coarse keyword sweep for natural phrasings the parser may
 * not yet recognise but that are clearly phone-capable.
 *
 * **Sprint contract:** if Android can do it locally, it MUST.  This
 * class is the runtime tripwire that proves that contract — when
 * a phone-capable transcript reaches the remote routing entry point
 * we log loudly and refuse to escalate.
 */
object PhoneCapableIntents {

    private const val TAG = "PhoneCapableIntents"

    /**
     * Surface-level keywords that strongly suggest a phone-capable
     * intent.  Used by [looksPhoneCapable] as a coarse pre-check —
     * the authoritative answer comes from running the full parser
     * stack, but this sweep catches obvious cases without paying
     * for that.
     *
     * Word-boundary anchored, case-insensitive.  Each entry is a
     * single concept; phrase rewrites in [TranscriptNormalizer]
     * already collapse many variants ("turn the" → "turn", "whats"
     * → "what is").
     */
    private val PHONE_CAPABLE_KEYWORDS: List<Regex> = listOf(
        // Phone / device control
        Regex("""\b(?:volume(?:\s+up|\s+down)?|mute|unmute|flashlight|torch|brightness)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:bluetooth|wi-?fi|mobile\s+data|hotspot|do\s+not\s+disturb|dnd|airplane\s+mode)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:battery|charging|screen\s+rotation)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:pause|resume|skip|next\s+track|previous\s+track)\b""", RegexOption.IGNORE_CASE),

        // Apps
        Regex("""\bopen\s+\w""", RegexOption.IGNORE_CASE),
        Regex("""\btake\s+me\s+to\b""", RegexOption.IGNORE_CASE),

        // Calls / contacts
        Regex("""\b(?:call|phone|ring|dial|facetime|hang\s+up|end\s+call|redial)\b""", RegexOption.IGNORE_CASE),

        // Messaging
        Regex("""\b(?:send|text|message|sms|whatsapp|wa)\b""", RegexOption.IGNORE_CASE),

        // Todoist / reminders / tasks — create AND query forms.
        Regex("""\b(?:remind\s+me|set\s+(?:a\s+)?reminder|add\s+(?:a\s+)?reminder|todo|task)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:don'?t\s+let\s+me\s+forget|i\s+need\s+to\s+remember)\b""", RegexOption.IGNORE_CASE),
        // Read queries: "what are my tasks", "show my todoist",
        // "what's overdue", "today's tasks", "what's coming up".  These
        // are phone-capable because Todoist runs locally — never
        // escalate to OpenClaw / LLM.
        Regex("""\b(?:my\s+(?:tasks?|todos?|reminders?)|todoist|todo\s*list|task\s*list|reminders?\s*list)\b""", RegexOption.IGNORE_CASE),
        Regex("""\boverdue\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:today'?s\s+tasks?|tasks?\s+for\s+today|what(?:'?s|\s+is)\s+(?:on|due)\s+today)\b""", RegexOption.IGNORE_CASE),

        // Calendar
        Regex("""\b(?:calendar|next\s+event|today'?s\s+events|add\s+(?:an?\s+)?event)\b""", RegexOption.IGNORE_CASE),

        // Maps / location
        Regex("""\b(?:navigate|directions|where\s+am\s+i|how\s+(?:long|far)\s+to|take\s+me\s+(?:to|home))\b""", RegexOption.IGNORE_CASE),

        // Camera / media
        Regex("""\btake\s+(?:a\s+)?(?:photo|picture|selfie|video)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bstart\s+recording\b""", RegexOption.IGNORE_CASE),
        Regex("""\bscreenshot\b""", RegexOption.IGNORE_CASE),

        // Alarms / timers
        Regex("""\bset\s+(?:a|an|the)\s+(?:alarm|timer)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:wake\s+me|cancel\s+(?:my\s+)?(?:timer|alarm))\b""", RegexOption.IGNORE_CASE),

        // Notifications
        Regex("""\b(?:read|clear)\s+(?:my\s+)?notifications?\b""", RegexOption.IGNORE_CASE),

        // Home Assistant — allow optional room/area words between
        // "on/off" and the appliance ("turn on kitchen lights",
        // "turn off bedroom lamp").  Up to two qualifier tokens.
        Regex(
            """\bturn\s+(?:on|off)\s+(?:the\s+)?(?:[a-z][a-z'\-]{0,15}\s+){0,2}(?:lights?|lamp|fan|heater|kettle|tv|television|vacuum|hoover|kettle|plug|outlet|heating)\b""",
            RegexOption.IGNORE_CASE,
        ),

        // Time / date / battery quick queries
        Regex("""\b(?:what(?:'?s|\s+is)\s+the\s+time|what\s+time\s+is\s+it|what(?:'?s|\s+is)\s+(?:today'?s\s+|the\s+)?date)\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * True when [transcript] looks phone-capable — i.e. should resolve
     * locally without involving OpenClaw / Hermes / heavy LLM.  Pure /
     * Android-free.
     */
    fun looksPhoneCapable(transcript: String): Boolean {
        if (transcript.isBlank()) return false
        val normalised = TranscriptNormalizer.normalizeForMatching(transcript)
        return PHONE_CAPABLE_KEYWORDS.any { it.containsMatchIn(normalised) }
    }

    /**
     * Tripwire that runs at the entry to remote routing.  If
     * [transcript] is phone-capable but routing decided to escalate,
     * we log `[INVALID_REMOTE_ROUTE]`, file a GitHub issue via the
     * reporter, and the caller decides whether to abort the remote
     * call (recommended) or proceed.
     *
     * Returns true when the route is INVALID — caller should NOT
     * proceed to remote routing.  Returns false when the route is
     * permitted.
     */
    fun isInvalidRemoteRoute(
        transcript: String,
        remoteSubsystem: String,
    ): Boolean {
        if (!looksPhoneCapable(transcript)) return false
        Log.w(TAG, "[INVALID_REMOTE_ROUTE] subsystem=$remoteSubsystem " +
            "transcript=\"${transcript.take(80)}\"")
        try {
            com.jarvis.assistant.reporting.github.IssueReporter
                .reportFatalSafe(
                    subsystem = "routing",
                    category  = "invalid_remote_route",
                    message   = "Phone-capable transcript reached remote routing path",
                    snippet   = "subsystem=$remoteSubsystem transcript=\"${transcript.take(120)}\"",
                )
        } catch (_: Throwable) { /* never propagate */ }
        return true
    }
}
