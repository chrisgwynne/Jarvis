package com.jarvis.assistant.tools.device

/**
 * MessageIntentParser — single shared parser for all messaging commands.
 *
 * Both [SmsTool] and [WhatsAppTool] route through this class so they cannot
 * disagree about what the user said.  Channel detection is **global** (it
 * scans the entire transcript), and the recipient/body extraction strips
 * channel words so contact lookup never receives noise like "Mike on WhatsApp"
 * or "to Mike".
 *
 * Channel priority:
 *   1. WhatsApp / explicit app mentioned anywhere in the utterance.
 *   2. SMS only when no channel is specified.
 *
 * The word "message" never forces SMS if "WhatsApp" is also present.
 */
object MessageIntentParser {

    enum class Channel { SMS, WHATSAPP }

    data class MessageIntent(
        val channel:   Channel,
        val recipient: String,
        val body:      String
    )

    // ── Patterns ──────────────────────────────────────────────────────────────

    /** WhatsApp anywhere in the utterance — accepts "whatsapp", "whats app", "whats-app", or standalone " wa ". */
    private val WHATSAPP_RX = Regex("""\bwhats[\s-]?app\b|\bwa\b""", RegexOption.IGNORE_CASE)

    /** Must contain at least one messaging verb to qualify as a messaging intent at all. */
    private val MESSAGING_VERB_RX = Regex(
        """\b(?:send|text|message|sms|whatsapp|whats\s+app|wa)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Cheap classifier for "is this utterance a messaging command at all?"
     *
     * Used by JarvisRuntime as a hard guard: when this returns true, the
     * utterance is treated as a LOCAL messaging command and is never
     * routed to OpenClaw.  Even if [parse] returns null (because the
     * slot extraction failed), the messaging tool should ask a clarifying
     * question locally rather than escalating to the remote LLM.
     */
    fun looksLikeMessagingCommand(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        val normalised = t.replace(
            Regex("""whats[\s-]+app""", RegexOption.IGNORE_CASE),
            "whatsapp"
        )
        return MESSAGING_VERB_RX.containsMatchIn(normalised)
    }

    /**
     * Body connector — anything matching this introduces the message text.
     * Order matters: longer phrases first so "and say" wins over "say".
     */
    private val CONNECTOR_RX = Regex(
        """\b(?:saying|and\s+say|to\s+say|that\s+says?|tell(?:ing)?\s+(?:him|her|them)(?:\s+that)?|just\s+say|message\s+saying)\b\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Shortcut form: `<channel-verb> <single-token-name> <body>` with no
     * "saying" connector.  e.g. "WhatsApp Mike hello", "text Mike I'm late".
     */
    private val SHORTCUT_RX = Regex(
        """^(?:text|message|sms|whatsapp|wa)\s+(\S+)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Tokens that surround the recipient name and must be stripped before the
     * name is passed to [com.jarvis.assistant.tools.ContactLookup].  All
     * lower-case, compared case-insensitively after trimming punctuation.
     */
    private val NOISE_TOKENS = setOf(
        "send", "please", "could", "can", "you",
        "to", "for",
        "text", "message", "sms",
        "whatsapp", "wa",
        "on", "via", "using", "through",
        "a", "an", "the"
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Parse [raw] into a [MessageIntent], or return null if it doesn't look
     * like a messaging command at all.  Never returns a blank recipient.
     */
    fun parse(raw: String): MessageIntent? {
        val transcript = raw.trim()
        if (transcript.isEmpty()) return null

        // Normalise "whats app" / "whats-app" → "whatsapp" so tokenisation is clean.
        val normalised = transcript.replace(
            Regex("""whats[\s-]+app""", RegexOption.IGNORE_CASE),
            "whatsapp"
        )

        // Must look like a messaging command.
        if (!MESSAGING_VERB_RX.containsMatchIn(normalised)) return null

        // Channel: WhatsApp anywhere wins, otherwise SMS.
        val channel = if (WHATSAPP_RX.containsMatchIn(normalised))
            Channel.WHATSAPP
        else
            Channel.SMS

        // Find body via connector word, else try shortcut form, else no body.
        val cm = CONNECTOR_RX.find(normalised)
        val (prefix, body) = when {
            cm != null -> normalised.substring(0, cm.range.first).trim() to
                cm.groupValues[1].trim()
            else       -> extractShortcut(normalised) ?: (normalised to "")
        }

        val recipient = extractRecipient(prefix) ?: return null
        return MessageIntent(channel, recipient, body)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Tries the "<verb> <name> <body>" shortcut form (no "saying" connector).
     * Returns null if the body would clearly continue the command grammar
     * (e.g. starts with "a", "on", "saying").
     */
    private fun extractShortcut(t: String): Pair<String, String>? {
        val m = SHORTCUT_RX.find(t) ?: return null
        val name = m.groupValues[1]
        val body = m.groupValues[2]
        // Bail if the "body" is really more command structure.
        if (Regex(
                """^(?:a\s|an\s|the\s|on\s|via\s|saying\b|tell\b|say\b|that\b)""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(body)
        ) return null
        val prefix = "${t.substringBefore(name)}$name".trim()
        return prefix to body
    }

    /**
     * Walk the prefix tokens, skipping leading noise (verbs, articles, channel
     * words) until a real word is found, then capture consecutive non-noise
     * tokens as the name.  Stops at the next noise token, so "send Mike a
     * message" yields "Mike" rather than "Mike a message".
     */
    private fun extractRecipient(prefix: String): String? {
        val tokens = prefix.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        fun clean(t: String) = t.lowercase().trim(',', '.', '!', '?', ':', ';')
        fun isNoise(t: String) = clean(t) in NOISE_TOKENS

        var i = 0
        while (i < tokens.size && isNoise(tokens[i])) i++
        if (i >= tokens.size) return null

        val nameTokens = mutableListOf<String>()
        while (i < tokens.size && !isNoise(tokens[i])) {
            nameTokens.add(tokens[i])
            i++
        }
        if (nameTokens.isEmpty()) return null

        // Strip trailing punctuation from the last token ("Mike," → "Mike").
        nameTokens[nameTokens.lastIndex] =
            nameTokens.last().trimEnd(',', '.', '!', '?', ':', ';')

        return nameTokens.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
