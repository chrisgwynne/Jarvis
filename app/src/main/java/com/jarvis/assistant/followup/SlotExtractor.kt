package com.jarvis.assistant.followup

import com.jarvis.assistant.reminders.ReminderParser

/**
 * SlotExtractor — pulls individual slot values from a raw utterance string.
 *
 * All methods are pure functions; side effects go through [EntityTracker].
 * Pronoun resolution is delegated to [EntityTracker.resolvePronoun].
 */
object SlotExtractor {

    // ── Contact extraction ────────────────────────────────────────────────────

    private val MESSAGE_CONTACT_RE = Regex(
        """(?:message|text|send\s+(?:a\s+)?(?:text|message)(?:\s+to)?|whatsapp|wa)\s+([A-Za-z][A-Za-z\s'.\-]+?)(?:\s+(?:and\s+(?:say|tell)|saying|to\s+say|that)\b|$)""",
        RegexOption.IGNORE_CASE
    )

    private val CALL_CONTACT_RE = Regex(
        """(?:call|phone|ring|dial)\s+([A-Za-z][A-Za-z\s'.\-]+?)(?:\s+(?:on\s+(?:mobile|work|home)|for\s+me|please|now))?$""",
        RegexOption.IGNORE_CASE
    )

    fun extractContactName(utterance: String): String? {
        MESSAGE_CONTACT_RE.find(utterance)?.let {
            val raw = it.groupValues[1].trim()
            // Defensively strip a leading "to " in case the "to" keyword was captured
            // e.g. "send a message to wifey" can produce "to wifey" via regex backtracking
            return raw.removePrefix("to ").removePrefix("To ").trim().ifBlank { null }
        }
        CALL_CONTACT_RE.find(utterance)?.let { return it.groupValues[1].trim() }
        return null
    }

    // ── Message body extraction ───────────────────────────────────────────────

    private val TELL_RE   = Regex("""^tell\s+\S+\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val SAY_RE    = Regex("""^(?:just\s+)?say(?:ing)?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val THAT_RE   = Regex("""^(?:just\s+)?that\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val WRITE_RE  = Regex("""^(?:write|type)\s+(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * Extract a message body from an utterance, resolving pronouns via [entityTracker].
     * Returns null if the utterance looks like a command rather than a message body.
     */
    fun extractMessageBody(utterance: String, entityTracker: EntityTracker): String? {
        val resolved = resolvePronouns(utterance, entityTracker)
        val lower = resolved.lowercase().trim()

        TELL_RE.find(resolved)?.let  { return it.groupValues[1].trim() }
        SAY_RE.find(resolved)?.let   { return it.groupValues[1].trim() }
        THAT_RE.find(resolved)?.let  { return it.groupValues[1].trim() }
        WRITE_RE.find(resolved)?.let { return it.groupValues[1].trim() }

        // Accept the utterance as-is if it doesn't look like a Jarvis command
        return if (lower.length > 2 && !looksLikeCommand(lower)) resolved.trim() else null
    }

    // ── Phone type ────────────────────────────────────────────────────────────

    fun extractPhoneType(utterance: String): String? {
        val lower = utterance.lowercase().trim()
        return when {
            lower in setOf("mobile", "mobile number", "cell", "cell number", "cell phone") -> "mobile"
            lower in setOf("work", "work number", "office", "office number", "business")   -> "work"
            lower in setOf("home", "home number", "landline")                              -> "home"
            lower.contains("mobile") || lower.contains("cell")  -> "mobile"
            lower.contains("work")   || lower.contains("office") -> "work"
            lower.contains("home")   || lower.contains("land")   -> "home"
            else -> null
        }
    }

    // ── Message channel ───────────────────────────────────────────────────────

    fun extractMessageChannel(utterance: String): String? {
        val lower = utterance.lowercase()
        return when {
            lower.contains("whatsapp") || lower.contains("whats app") -> "whatsapp"
            lower.contains(" sms ")    || lower.contains("text")      -> "sms"
            else -> null
        }
    }

    // ── Trigger time ──────────────────────────────────────────────────────────

    /**
     * Parse a time expression from [utterance], optionally adjusting to
     * [dateHint] ("tomorrow") when the user has previously said a date without
     * a clock time.
     */
    fun extractTriggerTimeMs(utterance: String, dateHint: String? = null): Long? {
        val parsed = ReminderParser.parse(utterance)?.triggerAtMs ?: return null
        return if (dateHint?.lowercase() == "tomorrow") {
            adjustToTomorrow(parsed)
        } else parsed
    }

    private fun adjustToTomorrow(epochMs: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        val today = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    // ── Pronoun resolution ────────────────────────────────────────────────────

    private val PRONOUNS = setOf("him", "his", "he", "her", "she", "them", "they", "their", "it", "that")

    /**
     * Replace personal pronouns in [utterance] with the resolved entity label
     * from [tracker], where confidence is high enough to do so.
     */
    fun resolvePronouns(utterance: String, tracker: EntityTracker): String {
        var result = utterance
        for (pronoun in PRONOUNS) {
            if (result.lowercase().contains("\\b$pronoun\\b".toRegex())) {
                val entity = tracker.resolvePronoun(pronoun) ?: continue
                result = result.replace(
                    Regex("\\b$pronoun\\b", RegexOption.IGNORE_CASE),
                    entity.label
                )
            }
        }
        return result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val COMMAND_PREFIXES = setOf(
        "message", "text", "send", "call", "ring", "remind", "reminder", "set",
        "cancel", "open", "play", "search", "find", "show", "turn",
        "volume", "alarm", "timer", "flash", "whatsapp", "wa "
    )

    private fun looksLikeCommand(lower: String): Boolean =
        COMMAND_PREFIXES.any { lower.startsWith(it) }
}
