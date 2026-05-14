package com.jarvis.assistant.tools.device.messaging

import android.util.Log
import com.jarvis.assistant.tools.device.MessageIntentParser

/**
 * PendingMessageIntent — a half-built messaging command stashed by the
 * runtime when slots are missing ("send a WhatsApp" → channel known,
 * recipient + body still missing).
 *
 * The runtime parks one of these whenever a messaging utterance is
 * recognised but cannot be executed yet, speaks a local clarifying
 * question, and intercepts the NEXT user utterance before AttentionGate
 * can filter it.  The next-turn intercept merges the new tokens into the
 * stash and runs [MessagePipeline] as soon as both slots are full.
 *
 * Why it lives here (not in JarvisRuntime):
 *   - Pure data + a small static merger.  Unit-testable without Android.
 *   - Holds both the channel and the partial slots so the runtime doesn't
 *     have to re-parse the original utterance.
 *
 * Lifetime: a single instance lives at most [TTL_MS] before being
 * dropped.  The runtime is the sole owner; this class never schedules
 * its own expiry.
 */
data class PendingMessageIntent(
    val channel:    MessageIntentParser.Channel,
    val recipient:  String,
    val body:       String,
    val createdMs:  Long,
    /** Wall-clock ms when this pending intent should be discarded. */
    val expiresAtMs: Long
) {
    companion object {
        private const val TAG    = "PendingMsgIntent"
        /** 20-second window — long enough for the user to think, short enough not to stale. */
        const val TTL_MS: Long   = 20_000L

        /**
         * Build a fresh pending intent.  Either slot may be blank.
         */
        fun create(
            channel:   MessageIntentParser.Channel,
            recipient: String = "",
            body:      String = "",
            nowMs:     Long = System.currentTimeMillis()
        ) = PendingMessageIntent(
            channel     = channel,
            recipient   = recipient.trim(),
            body        = body.trim(),
            createdMs   = nowMs,
            expiresAtMs = nowMs + TTL_MS
        )

        /**
         * Merge a follow-up utterance into [pending], returning the new
         * intent.  Strategy:
         *   1. Re-run [MessageIntentParser.parse] on the follow-up to pick
         *      up any explicit "saying" connector → fills body cleanly.
         *   2. If that succeeds, take the new recipient/body wherever the
         *      pending was blank.
         *   3. Otherwise treat the whole follow-up as either recipient (if
         *      it's a single token) or as a recipient + body split on a
         *      "saying"-style connector hidden in the partial.
         *
         * Pure / no Android dependency.
         */
        fun merge(pending: PendingMessageIntent, followUp: String): PendingMessageIntent {
            val t = followUp.trim()
            if (t.isEmpty()) return pending

            // Body-only follow-up shortcut: the runtime parks a pending
            // intent with the recipient already filled when the tool said
            // "What should the WhatsApp to Mike say?" — in that case the
            // user's whole reply is the body, even if it's a single token
            // like "Hello".  Re-running MessageIntentParser here would
            // mis-parse "Hello" as a new recipient and overwrite Mike,
            // which is exactly the regression this branch defends against.
            // We still strip a leading "saying" / "that says" / "telling
            // them" connector so legacy phrasings like "saying I'm
            // running late" keep producing body="I'm running late".
            if (pending.recipient.isNotBlank() && pending.body.isBlank()) {
                val stripped = t.replaceFirst(
                    Regex("""^(?:saying|that\s+says|telling\s+them|telling\s+him|telling\s+her|tell\s+them|tell\s+him|tell\s+her)\s+""",
                        RegexOption.IGNORE_CASE),
                    "",
                ).trim()
                Log.d(TAG, "[FOLLOWUP_MERGE_BODY_ONLY] channel=${pending.channel} " +
                    "recipient=\"${pending.recipient}\" body=\"$stripped\"")
                return pending.copy(body = stripped)
            }

            // Inject the channel verb so MessageIntentParser still classifies it.
            val verbForChannel = when (pending.channel) {
                MessageIntentParser.Channel.WHATSAPP -> "whatsapp"
                MessageIntentParser.Channel.SMS      -> "text"
            }
            val synthesised   = "$verbForChannel $t"
            val parsed        = MessageIntentParser.parse(synthesised)

            val mergedRecipient = when {
                pending.recipient.isNotBlank()                  -> pending.recipient
                parsed != null && parsed.recipient.isNotBlank() -> parsed.recipient
                // No "saying" — first token of the follow-up is the recipient.
                else -> t.split(Regex("\\s+")).firstOrNull().orEmpty()
            }
            val mergedBody = when {
                pending.body.isNotBlank()                       -> pending.body
                parsed != null && parsed.body.isNotBlank()      -> parsed.body
                else -> {
                    // Strip the leading recipient token and treat the rest as body.
                    val tokens = t.split(Regex("\\s+"))
                    if (tokens.size > 1) tokens.drop(1).joinToString(" ") else ""
                }
            }

            Log.d(TAG, "[FOLLOWUP_MERGE_DONE] channel=${pending.channel} " +
                "recipient=\"$mergedRecipient\" body=\"$mergedBody\"")
            return pending.copy(
                recipient = mergedRecipient,
                body      = mergedBody
            )
        }
    }

    val isReady: Boolean get() = recipient.isNotBlank() && body.isNotBlank()
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}
