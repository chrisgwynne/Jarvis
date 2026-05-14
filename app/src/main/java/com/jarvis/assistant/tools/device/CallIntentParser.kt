package com.jarvis.assistant.tools.device

/**
 * CallIntentParser — parses "call X" / "ring X on WhatsApp" / "WhatsApp
 * call X" / "video call X" utterances into a [CallIntent] with channel and
 * cleaned recipient name.
 *
 * Mirrors [MessageIntentParser] in spirit: channel detection is **global**
 * (scan the whole utterance), and the recipient name is cleaned of channel
 * noise tokens before contact lookup so "Mike on WhatsApp" reaches
 * [com.jarvis.assistant.tools.ContactLookup] as just "Mike".
 *
 * Channel priority:
 *   1. WhatsApp anywhere in the utterance (voice or video, see [Mode])
 *   2. Native phone otherwise.
 */
object CallIntentParser {

    enum class Channel { PHONE, WHATSAPP }
    enum class Mode    { VOICE, VIDEO }

    data class CallIntent(
        val channel:   Channel,
        val mode:      Mode,
        val recipient: String,
    )

    /** Any verb that initiates a call. */
    private val CALL_VERB_RX = Regex(
        """\b(?:call|phone|ring|dial|facetime|video[\s-]?call)\b""",
        RegexOption.IGNORE_CASE
    )

    /** WhatsApp anywhere — accepts "whatsapp", "whats app", "whats-app", " wa ". */
    private val WHATSAPP_RX = Regex("""\bwhats[\s-]?app\b|\bwa\b""", RegexOption.IGNORE_CASE)

    /** Video-call hints — "video call", "facetime", "video chat". */
    private val VIDEO_RX = Regex(
        """\b(?:video[\s-]?call|video[\s-]?chat|facetime)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Noise tokens to skip when extracting the recipient.  Shared spirit
     * with MessageIntentParser, plus call-verbs and channel words.
     *
     * Note "video" / "facetime" are noise tokens too — they're part of the
     * channel/mode, never part of a contact name.
     */
    private val NOISE_TOKENS = setOf(
        "call", "phone", "ring", "dial",
        "video", "videocall", "video-call", "facetime",
        "please", "could", "can", "you",
        "to", "for",
        "whatsapp", "wa",
        "on", "via", "using", "through", "over",
        "a", "an", "the",
        "for", "me", "now",
    )

    /**
     * Trailing courtesy fragments we should strip from the recipient name.
     * "call mum for me please now" → "mum".  Tightened so we don't trim
     * substrings inside a real name (e.g. "Mr Now").
     */
    private val TRAILING_RX = Regex(
        """\s+(?:for\s+me|please|right\s+now|now)\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeCallCommand(raw: String): Boolean =
        CALL_VERB_RX.containsMatchIn(raw.trim())

    fun parse(raw: String): CallIntent? {
        val transcript = raw.trim()
        if (transcript.isEmpty()) return null

        val normalised = transcript.replace(
            Regex("""whats[\s-]+app""", RegexOption.IGNORE_CASE),
            "whatsapp"
        )

        if (!CALL_VERB_RX.containsMatchIn(normalised)) return null

        val channel = if (WHATSAPP_RX.containsMatchIn(normalised))
            Channel.WHATSAPP
        else
            Channel.PHONE

        val mode = if (VIDEO_RX.containsMatchIn(normalised))
            Mode.VIDEO
        else
            Mode.VOICE

        // Trim trailing courtesy fragments before token extraction.
        val trimmed = normalised.replace(TRAILING_RX, "").trim()

        val recipient = extractRecipient(trimmed) ?: return null
        return CallIntent(channel, mode, recipient)
    }

    /**
     * Walk tokens, skipping noise (call verbs, articles, channel words)
     * until a real word is found, then capture consecutive non-noise tokens
     * as the name.  Stops at the next noise token, so "call Mike on
     * WhatsApp" yields "Mike" not "Mike on WhatsApp".
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

        nameTokens[nameTokens.lastIndex] =
            nameTokens.last().trimEnd(',', '.', '!', '?', ':', ';')

        return nameTokens.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
