package com.jarvis.assistant.runtime

/**
 * ResponseFormatter — post-processes LLM output for spoken delivery.
 *
 * RULES (applied in order, each building on the previous step):
 *   1. Markdown strip — fenced/inline code, headings, bullets, bold/italic,
 *      underline, dividers, then collapse whitespace.  Done BEFORE sentence
 *      counting so `**Hello.** **World.**` becomes `Hello. World.` and is
 *      correctly seen as two sentences.
 *   2. Cap at MAX_SENTENCES.  Voice output should never exceed 3 sentences by
 *      default.  The user can ask "tell me more" for the rest.
 *   3. Hard-cap at MAX_CHARS as a safety net, preferring the last sentence
 *      boundary inside the limit when one is available past MAX_CHARS / 2.
 *
 * The whitespace collapse on step 1 matters: stripping a divider line leaves a
 * bare newline, and a bare `\n` between two sentences would otherwise get
 * normalised but is already handled here.  Tests exercise the order — if you
 * move the steps around, move the tests first.
 *
 * These are enforced in the runtime, not just in the system prompt, so they
 * hold even if the LLM ignores the instructions.
 */
object ResponseFormatter {

    private const val MAX_SENTENCES = 3
    private const val MAX_CHARS = 320

    fun format(raw: String): String {
        var text = raw.trim()

        // Strip markdown
        text = text
            .replace(Regex("""```[\s\S]*?```"""), "")   // fenced code blocks
            .replace(Regex("""`[^`]+`"""), "")          // inline code
            .replace(Regex("""^\s*#{1,6}\s+""", RegexOption.MULTILINE), "")  // headings
            .replace(Regex("""^\s*[-*•]\s+""", RegexOption.MULTILINE), "")   // bullets
            .replace(Regex("""\*{1,2}([^*]+)\*{1,2}"""), "$1")   // bold/italic
            .replace(Regex("""_{1,2}([^_]+)_{1,2}"""), "$1")     // underline
            .replace(Regex("""^[-=]{3,}$""", RegexOption.MULTILINE), "")     // dividers
            .replace(Regex("""\s+"""), " ")   // collapse whitespace
            .trim()

        if (text.isBlank()) return raw.trim()   // fallback if stripping emptied it

        // Sentence cap
        val sentences = splitSentences(text)
        if (sentences.size > MAX_SENTENCES) {
            text = sentences.take(MAX_SENTENCES).joinToString(" ").trimEnd()
        }

        // Hard char cap — find last sentence boundary within limit
        if (text.length > MAX_CHARS) {
            val truncated = text.take(MAX_CHARS)
            val lastBoundary = maxOf(
                truncated.lastIndexOf(". "),
                truncated.lastIndexOf("! "),
                truncated.lastIndexOf("? ")
            )
            text = if (lastBoundary > MAX_CHARS / 2) {
                truncated.substring(0, lastBoundary + 1).trim()
            } else {
                truncated.trimEnd()
            }
        }

        return text
    }

    /** Format a tool result for voice — usually just the feedback string. */
    fun formatToolFeedback(feedback: String): String = format(feedback)

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("""(?<=[.!?])\s+""")).filter { it.isNotBlank() }
}
