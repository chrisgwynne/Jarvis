package com.jarvis.assistant.followup

import com.jarvis.assistant.reminders.ReminderParser

/**
 * CorrectionHandler — detects when the user is revising a previously filled
 * slot and returns the updated slot key + new value.
 *
 * Patterns handled:
 *   "not Chris, James"      → replace TARGET_CONTACT
 *   "actually James"        → replace most-recently-filled slot or TARGET_CONTACT
 *   "no, send it to Sarah"  → replace TARGET_CONTACT
 *   "make it 30 minutes"    → replace TRIGGER_TIME
 *   "change it to tomorrow" → replace TRIGGER_TIME / TRIGGER_DATE_HINT
 */
object CorrectionHandler {

    data class Correction(val slot: SlotKey, val newValue: String)

    // Patterns that signal an explicit correction
    private val NOT_X_Y = Regex(
        """^not\s+\S+[,;]?\s+(?:use\s+|send\s+(?:it\s+)?to\s+)?(.+)""",
        RegexOption.IGNORE_CASE
    )
    private val ACTUALLY = Regex(
        """^actually\s+(?:use\s+|make\s+it\s+|change\s+(?:it\s+)?to\s+|send\s+(?:it\s+)?to\s+)?(.+)""",
        RegexOption.IGNORE_CASE
    )
    private val NO_COMMA = Regex(
        """^no[,;]\s+(?:use\s+|send\s+(?:it\s+)?to\s+|make\s+it\s+)?(.+)""",
        RegexOption.IGNORE_CASE
    )
    private val CHANGE_TO = Regex(
        """^(?:change|make)\s+it\s+to\s+(.+)""",
        RegexOption.IGNORE_CASE
    )
    private val INSTEAD = Regex(
        """^(.+)\s+instead$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Try to detect a correction in [utterance] against the current [flow].
     * Returns null if this looks like a normal continuation rather than a fix.
     */
    fun detect(utterance: String, flow: ActiveFlow): Correction? {
        for (pattern in listOf(NOT_X_Y, ACTUALLY, NO_COMMA, CHANGE_TO, INSTEAD)) {
            pattern.find(utterance)?.let { m ->
                val value = m.groupValues[1].trim().trimEnd('.', ',')
                if (value.isBlank()) return@let
                guessSlot(value, flow)?.let { slot ->
                    return Correction(slot, value)
                }
            }
        }
        return null
    }

    // ── Slot inference from correction value ───────────────────────────────────

    private fun guessSlot(value: String, flow: ActiveFlow): SlotKey? {
        val lower = value.lowercase().trim()

        // Explicit phone type keywords
        if (lower in setOf("mobile", "work", "home", "cell", "landline")) {
            return SlotKey.PHONE_TYPE
        }

        // Time/date expressions
        if (ReminderParser.parse(value) != null ||
            lower.contains("tomorrow") || lower.contains("today")) {
            return SlotKey.TRIGGER_TIME
        }

        // Duration ("30 minutes", "an hour")
        if (Regex("""^\d+\s+(?:minute|hour|second)""", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
            return SlotKey.TRIGGER_TIME
        }

        // For message flows: if body was already filled, correction is the body
        if (flow.type == FlowType.MESSAGE_DRAFT && flow.hasSlot(SlotKey.MESSAGE_BODY)) {
            // Check if it looks like a message (not a name)
            return if (value.contains(" ")) SlotKey.MESSAGE_BODY else SlotKey.TARGET_CONTACT
        }

        // Default for messaging/call flows: assume contact correction
        return if (flow.type in setOf(FlowType.MESSAGE_DRAFT, FlowType.CALL_CONTACT)) {
            SlotKey.TARGET_CONTACT
        } else if (flow.type == FlowType.REMINDER_CREATION) {
            if (flow.hasSlot(SlotKey.REMINDER_CONTENT)) SlotKey.REMINDER_CONTENT
            else SlotKey.REMINDER_CONTENT
        } else {
            null
        }
    }
}
