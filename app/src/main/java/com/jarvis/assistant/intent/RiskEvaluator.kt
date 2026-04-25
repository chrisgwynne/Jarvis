package com.jarvis.assistant.intent

/**
 * RiskEvaluator — decides [RiskLevel] and whether an envelope must be held
 * for user confirmation before its handler executes.
 *
 * HIGH-RISK VERBS (explicitly enumerated in the spec):
 *   send, delete, post, purchase — plus close synonyms that expand to the
 *   same user-visible consequence (buy, transfer money, cancel order,
 *   remove, wipe). These trigger [RiskLevel.HIGH] and always require
 *   confirmation, no matter which primary intent fires.
 *
 * MEDIUM-RISK DEFAULTS:
 *   * ACT_ON_CONTEXT without a high-risk verb — still requires confirmation
 *     because "do this" is open-ended and may fire-and-forget state changes.
 *   * DRAFT_REPLY — medium risk, but does NOT require confirmation by
 *     default: drafting ≠ sending. Upgrade to HIGH if the raw text carries
 *     a send verb ("reply to this and send it").
 *
 * LOW-RISK: everything else — observe, recall, store, control signals.
 */
class RiskEvaluator {

    companion object {
        /** Verbs that always trigger HIGH + confirmation. */
        private val HIGH_RISK = Regex(
            """\b(send|deliver|deletes?|remove|removed?|wipe|purchas(?:e|ing)|buy|""" +
            """transfer|pay|post|publish|share|broadcast|forward)\b""",
            RegexOption.IGNORE_CASE
        )

        /** Verbs that bump risk to MEDIUM without necessarily requiring confirmation. */
        private val MEDIUM_RISK = Regex(
            """\b(archive|mute|block|unsubscribe|reschedule|cancel)\b""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * @return pair of (risk, requiresConfirmation).
     */
    fun evaluate(
        rawText:          String,
        intent:           PrimaryIntent,
        resolvedContext:  ResolvedContext,
    ): Pair<RiskLevel, Boolean> {

        // Scan the transcript + any text the user has already typed / copied.
        val scanned = buildString {
            append(rawText)
            append(' ')
            resolvedContext.currentInputText?.let { append(it); append(' ') }
            resolvedContext.selectedText?.let    { append(it); append(' ') }
        }

        if (HIGH_RISK.containsMatchIn(scanned)) {
            return RiskLevel.HIGH to true
        }

        // Control signals are always safe.
        if (intent in CONTROL_SIGNALS) return RiskLevel.LOW to false

        // Bare "do this" / "handle this" is an open-ended action → confirm.
        if (intent == PrimaryIntent.ACT_ON_CONTEXT) {
            return RiskLevel.MEDIUM to true
        }

        if (MEDIUM_RISK.containsMatchIn(scanned)) {
            // DRAFT_REPLY + a medium verb = "reply to this and archive it" → confirm.
            val confirm = intent == PrimaryIntent.DRAFT_REPLY
            return RiskLevel.MEDIUM to confirm
        }

        return RiskLevel.LOW to false
    }

    private val CONTROL_SIGNALS = setOf(
        PrimaryIntent.INTERRUPT_ASSISTANT,
        PrimaryIntent.PAUSE_ASSISTANT,
        PrimaryIntent.RESUME_ASSISTANT,
        PrimaryIntent.CHANGE_RESPONSE_STYLE,
    )
}
