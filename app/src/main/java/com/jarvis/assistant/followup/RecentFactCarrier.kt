package com.jarvis.assistant.followup

import java.util.concurrent.atomic.AtomicReference

/**
 * RecentFactCarrier — single-slot, short-TTL holder for the last *fact-style*
 * reply Jarvis spoke (location, time, weather, battery, …).
 *
 * Why this exists.  When a tool answers a factual question ("Where am I?" →
 * "You're on High Street in Wrexham."), the user's next turn often refers
 * back to it implicitly ("what number?", "and the postcode?", "how far?").
 * The LLM doesn't see the tool's spoken reply as an assistant turn straight
 * away — its turn is appended *after* this reply — so without a carrier it
 * lacks the context to resolve those follow-ups and tends to misroute them.
 *
 * Scope is intentionally tiny:
 *   - One slot, last-writer-wins.
 *   - 60-second TTL — long enough for a natural conversational follow-up,
 *     short enough that it never bleeds into an unrelated next session.
 *   - Read returns null and clears the slot once consumed, so a stale fact
 *     can't keep showing up on every subsequent turn.
 *
 * Threading: backed by [AtomicReference] so the proactive dispatcher (writer)
 * and the prompt assembler (reader) can touch it from different coroutines
 * without a lock.
 */
class RecentFactCarrier(
    private val ttlMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Fact(
        /** Short topic tag — "location", "time", "battery", … */
        val topic:   String,
        /** The exact sentence Jarvis spoke. */
        val spoken:  String,
        val writtenAtMs: Long,
    )

    private val slot = AtomicReference<Fact?>(null)

    /** Record that Jarvis just spoke a fact of [topic]. */
    fun remember(topic: String, spoken: String) {
        if (topic.isBlank() || spoken.isBlank()) return
        slot.set(Fact(topic.trim(), spoken.trim(), clock()))
    }

    /**
     * Peek the active fact without clearing it.  Returns null if the slot is
     * empty or the entry has expired.  An expired entry is cleared as a side
     * effect so the next reader doesn't keep paying the TTL check.
     */
    fun peek(): Fact? {
        val current = slot.get() ?: return null
        return if (clock() - current.writtenAtMs > ttlMs) {
            slot.compareAndSet(current, null)
            null
        } else current
    }

    /** Explicit clear — call after the follow-up has been used or the topic shifts. */
    fun clear() { slot.set(null) }

    /**
     * Render a one-line prompt fragment the LLM can use as background context
     * for the *next* user turn.  Format mirrors the existing presence /
     * profile fragments — single line, framed as background context, never
     * cited explicitly.
     */
    fun toPromptFragment(): String {
        val f = peek() ?: return ""
        return "[Last factual reply you gave (${f.topic}) — use this if the user's next turn refers back to it (\"what number\", \"and the postcode\", \"how far\" …): ${f.spoken}]"
    }
}
