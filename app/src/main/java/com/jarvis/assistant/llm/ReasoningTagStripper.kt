package com.jarvis.assistant.llm

/**
 * ReasoningTagStripper — filters chain-of-thought blocks out of a token stream
 * as it arrives, so the TTS never speaks `<think>…</think>` content.
 *
 * WHY A STATE MACHINE?
 *   The reasoning tag can be split across tokens: a stream may emit "<th",
 *   "ink>", " The user wants...", "</thi", "nk>", "actual reply".  A simple
 *   regex over the accumulated buffer does not work for streaming because by
 *   the time `</think>` arrives we've already emitted the prefix to TTS.
 *
 * ALGORITHM:
 *   OUT           — normal text; emit immediately, BUT when '<' arrives,
 *                   buffer it and switch to PENDING_TAG.
 *   PENDING_TAG   — buffering a potential "<think" / "<thinking" opener.  If
 *                   the buffer completes the tag → IN_THINK (drop buffer).
 *                   If it diverges from any known opener → emit the buffer
 *                   verbatim and return to OUT.  A trailing '<' inside
 *                   PENDING_TAG also resets the buffer to just that '<'.
 *   IN_THINK      — drop every character until we see the closing tag.
 *                   A rolling tail keeps the last ~12 chars so we can match
 *                   "</think>" / "</thinking>" when it lands across tokens.
 *
 *   [flush] handles stream end with a half-open tag or half-open close — we
 *   emit any held-back PENDING_TAG text to avoid losing real content.
 *
 *   Matching is case-insensitive to handle `<Think>` etc., mirroring the
 *   existing post-hoc regex in [LlmRouter.stripReasoningTags].
 */
class ReasoningTagStripper {

    private enum class Mode { OUT, PENDING_TAG, IN_THINK }

    private var mode             = Mode.OUT
    private val pendingOpen      = StringBuilder()  // buffered '<...' in PENDING_TAG
    private val closeTail        = StringBuilder()  // rolling tail in IN_THINK

    /** Streaming input: return the portion of [token] that is safe to emit. */
    fun process(token: String): String {
        val output = StringBuilder()
        var i = 0
        while (i < token.length) {
            val ch = token[i]
            when (mode) {
                Mode.OUT -> {
                    if (ch == '<') {
                        pendingOpen.setLength(0)
                        pendingOpen.append(ch)
                        mode = Mode.PENDING_TAG
                    } else {
                        output.append(ch)
                    }
                }
                Mode.PENDING_TAG -> {
                    pendingOpen.append(ch)
                    val s = pendingOpen.toString().lowercase()
                    when {
                        // Completed opening tag
                        s == "<think>" || s == "<thinking>" -> {
                            mode = Mode.IN_THINK
                            pendingOpen.setLength(0)
                            closeTail.setLength(0)
                        }
                        // Still a possible prefix of <think> or <thinking>
                        "<think>".startsWith(s) || "<thinking>".startsWith(s) -> {
                            // keep buffering
                        }
                        // Lost its prefix-ness — was a real '<' followed by other text.
                        // Emit what we have, but if a new '<' appears inside, reset
                        // the buffer to it so "a<think>b" doesn't miss the tag.
                        else -> {
                            val lastLt = pendingOpen.indexOf('<', 1)
                            if (lastLt > 0) {
                                output.append(pendingOpen, 0, lastLt)
                                val rest = pendingOpen.substring(lastLt)
                                pendingOpen.setLength(0)
                                pendingOpen.append(rest)
                                // Stay in PENDING_TAG — re-evaluate the new '<...'
                            } else {
                                output.append(pendingOpen)
                                pendingOpen.setLength(0)
                                mode = Mode.OUT
                            }
                        }
                    }
                }
                Mode.IN_THINK -> {
                    closeTail.append(ch)
                    // Keep only enough tail to match the longest close tag
                    val maxTail = "</thinking>".length
                    if (closeTail.length > maxTail) {
                        closeTail.delete(0, closeTail.length - maxTail)
                    }
                    val t = closeTail.toString().lowercase()
                    if (t.endsWith("</think>") || t.endsWith("</thinking>")) {
                        mode = Mode.OUT
                        closeTail.setLength(0)
                    }
                }
            }
            i++
        }
        return output.toString()
    }

    /**
     * Called after the stream ends.  If we were mid-partial-tag the buffered
     * text was held back — flush it so real content isn't lost.  If we were
     * inside an unclosed `<think>` block, drop it (it was chain-of-thought).
     */
    fun flush(): String {
        val out = when (mode) {
            Mode.PENDING_TAG -> pendingOpen.toString()
            Mode.OUT         -> ""
            Mode.IN_THINK    -> ""   // unclosed reasoning — drop
        }
        mode = Mode.OUT
        pendingOpen.setLength(0)
        closeTail.setLength(0)
        return out
    }

    /** Reset state for a new stream. */
    fun reset() {
        mode = Mode.OUT
        pendingOpen.setLength(0)
        closeTail.setLength(0)
    }
}
