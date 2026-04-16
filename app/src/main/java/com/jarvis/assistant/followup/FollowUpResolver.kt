package com.jarvis.assistant.followup

import com.jarvis.assistant.reminders.ReminderParser

/**
 * FollowUpResolver — classifies a user utterance relative to an [ActiveFlow].
 *
 * Classification hierarchy (highest priority first):
 *   1. Cancellation  — "cancel that", "never mind"
 *   2. Confirmation  — "yes", "send it", "go ahead"  (when flow has nothing missing)
 *   3. Denial        — "no", "wait"
 *   4. Correction    — "actually James", "not Chris, Sarah", "make it 30 minutes"
 *   5. SlotFill      — utterance fills the next expected (or any missing) slot
 *   6. Unrelated     — could not be mapped onto the active flow
 */
object FollowUpResolver {

    sealed class Classification {
        object Cancellation : Classification()
        object Confirmation : Classification()
        object Denial : Classification()
        data class Correction(val slot: SlotKey, val newValue: String) : Classification()
        data class SlotFill(val slot: SlotKey, val value: String, val confidence: Float) : Classification()
        object Unrelated : Classification()
    }

    fun classify(
        utterance: String,
        flow: ActiveFlow,
        entityTracker: EntityTracker
    ): Classification {
        val lower = utterance.lowercase().trim()

        // ── 1. Cancellation ────────────────────────────────────────────────
        if (isCancellation(lower)) return Classification.Cancellation

        // ── 2. Confirmation ────────────────────────────────────────────────
        if (isConfirmation(lower) && flow.allSlotsCollected()) {
            return Classification.Confirmation
        }

        // ── 3. Denial ──────────────────────────────────────────────────────
        if (isDenial(lower)) return Classification.Denial

        // ── 4. Correction ──────────────────────────────────────────────────
        CorrectionHandler.detect(utterance, flow)?.let { c ->
            return Classification.Correction(c.slot, c.newValue)
        }

        // ── 5. Slot fill — try expected slot first, then any missing slot ──
        val candidates: List<SlotKey> = buildList {
            flow.expectedSlot?.let { add(it) }
            addAll(flow.missingSlots.filter { it != flow.expectedSlot })
        }

        for (slot in candidates) {
            val value = tryExtractSlot(utterance, slot, flow, entityTracker)
            if (value != null) {
                val confidence = if (slot == flow.expectedSlot) 0.95f else 0.75f
                return Classification.SlotFill(slot, value, confidence)
            }
        }

        // ── 6. Unrelated ───────────────────────────────────────────────────
        // Only mark as unrelated if the utterance is clearly a new/different request.
        // Short utterances (≤ 4 words) get benefit-of-the-doubt — they're
        // almost certainly a slot answer even if we couldn't extract it.
        return if (looksLikeNewRequest(lower) && lower.split(" ").size > 4) {
            Classification.Unrelated
        } else {
            // Can't fill a slot but not clearly unrelated — treat as slot answer
            // the coordinator will ask again
            Classification.Unrelated
        }
    }

    // ── Slot extraction per key ────────────────────────────────────────────────

    private fun tryExtractSlot(
        utterance: String,
        slot: SlotKey,
        flow: ActiveFlow,
        entityTracker: EntityTracker
    ): String? = when (slot) {

        SlotKey.TARGET_CONTACT -> {
            // Try pronoun resolution first
            val resolved = entityTracker.resolvePronoun(utterance.lowercase().trim())
            resolved?.label
                ?: run {
                    // Strip filler prefix "to " — users naturally say "to wifey" when prompted
                    val trimmed = utterance.trim()
                        .removePrefix("to ").removePrefix("To ").trim()
                    if (trimmed.matches(Regex("""[A-Za-z][A-Za-z'\-]+(?:\s+[A-Za-z][A-Za-z'\-]+)?"""))
                        && SlotExtractor.extractPhoneType(trimmed) == null
                    ) trimmed else null
                }
        }

        SlotKey.MESSAGE_BODY ->
            SlotExtractor.extractMessageBody(utterance, entityTracker)

        SlotKey.PHONE_TYPE ->
            SlotExtractor.extractPhoneType(utterance)

        SlotKey.MESSAGE_CHANNEL ->
            SlotExtractor.extractMessageChannel(utterance)

        SlotKey.REMINDER_CONTENT -> {
            val lower = utterance.lowercase().trim()
            // Accept as content only if it doesn't look like a time expression
            if (lower.length > 2 && !isTimeExpression(lower)) utterance.trim() else null
        }

        SlotKey.TRIGGER_TIME -> {
            val dateHint = flow.slot(SlotKey.TRIGGER_DATE_HINT)
            SlotExtractor.extractTriggerTimeMs(utterance, dateHint)?.toString()
        }

        SlotKey.TRIGGER_DATE_HINT ->
            if (utterance.lowercase().contains("tomorrow")) "tomorrow" else null

        SlotKey.EMAIL_ADDRESS -> {
            val trimmed = utterance.trim()
            // Accept a raw email address or any non-blank text (contact name)
            trimmed.takeIf { it.isNotBlank() }
        }

        SlotKey.EMAIL_SUBJECT ->
            utterance.trim().takeIf { it.isNotBlank() }

        SlotKey.APP_NAME ->
            utterance.trim().takeIf { it.isNotBlank() && !isTimeExpression(it.lowercase()) }

        SlotKey.CONFIRMATION ->
            if (isConfirmation(utterance.lowercase())) "yes"
            else if (isDenial(utterance.lowercase())) "no"
            else null

        else -> null
    }

    // ── Pattern helpers ────────────────────────────────────────────────────────

    private val CANCELLATION_SET = setOf(
        "cancel", "cancel that", "cancel this", "never mind", "nevermind",
        "forget it", "forget that", "stop", "leave it", "don't", "don't do that",
        "don't send that", "don't send it", "actually no", "no thanks", "abort"
    )

    private val CONFIRMATION_SET = setOf(
        "yes", "yeah", "yep", "yup", "sure", "okay", "ok", "do it", "go ahead",
        "send it", "send that", "confirm", "that's right", "correct", "right",
        "sounds good", "perfect", "please", "yes please"
    )

    private val DENIAL_SET = setOf(
        "no", "nope", "not that", "not yet", "wait", "hold on", "not now"
    )

    fun isCancellation(lower: String): Boolean = lower in CANCELLATION_SET

    fun isConfirmation(lower: String): Boolean = lower in CONFIRMATION_SET

    fun isDenial(lower: String): Boolean = lower in DENIAL_SET

    private fun isTimeExpression(lower: String): Boolean =
        lower.contains("minute") || lower.contains("hour") || lower.contains("second") ||
        lower.contains(" am") || lower.contains(" pm") ||
        lower.contains("tomorrow") || lower.contains("today") || lower.contains("tonight") ||
        lower.matches(Regex(""".*\d{1,2}:\d{2}.*""")) ||
        lower.matches(Regex("""in \d+.*"""))

    private fun looksLikeNewRequest(lower: String): Boolean {
        // Clearly off-topic questions
        val offTopic = listOf(
            Regex("""^what(?:'s| is) the (?:time|date|weather|temperature)"""),
            Regex("""^what time is it"""),
            Regex("""^how (?:are you|do you do)"""),
        )
        return offTopic.any { it.containsMatchIn(lower) }
    }
}
