package com.jarvis.assistant.conversation

/**
 * How a mid-speech interruption should be treated.
 *
 * Spec source: the Jarvis core operating-order document, INTERRUPT HANDLING /
 * RESPONSE RESUME LOGIC sections.
 *
 *   CONTINUE       — user signalled "carry on" / "keep going" / silence after
 *                    interrupt.  Resume the original response if still relevant.
 *   CLARIFICATION  — user asked a quick same-topic question.  Answer it; may
 *                    optionally continue the original reply afterwards.
 *   CORRECTION     — user is fixing what they just said.  Discard the original
 *                    response and re-route from the new (corrected) transcript.
 *   REPLACEMENT    — user replaced their entire previous utterance.  Discard
 *                    original, treat new input as a fresh turn.
 *   URGENT         — hard stop ("wait", "stop", "hold on", "cancel").  Discard
 *                    original response completely; do not resume.
 *   UNRELATED      — user pivoted to a new topic.  Discard original; treat new
 *                    input as a fresh turn.
 */
enum class InterruptionType {
    CONTINUE,
    CLARIFICATION,
    CORRECTION,
    REPLACEMENT,
    URGENT,
    UNRELATED
}
