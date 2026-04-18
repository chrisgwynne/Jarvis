package com.jarvis.assistant.conversation

/**
 * How a mid-speech interruption should be treated.
 *
 * Default behaviour: DO NOT resume.  Only CONTINUE (explicit user signal) and
 * CLARIFICATION (user asks a question, then explicitly says "continue") lead
 * back to the interrupted reply.  Everything else — corrections, replacements,
 * new topics, urgent stops — discards the original response entirely.
 *
 *   CONTINUE       — user said "go on" / "keep going" / "carry on".  Resume by
 *                    re-invoking the LLM with what was actually spoken so the
 *                    model picks up naturally without replaying old words.
 *   CLARIFICATION  — user asked a quick same-topic question.  Answer it; keep
 *                    the interrupt state alive so a subsequent "go on" resumes.
 *                    Never auto-resume.
 *   CORRECTION     — user is fixing what they just said.  Discard the original
 *                    response and re-route from the new (corrected) transcript.
 *   REPLACEMENT    — user replaced their entire previous utterance.  Discard
 *                    original, treat new input as a fresh turn.
 *   URGENT         — hard stop ("wait", "stop", "hold on", "cancel").  Discard
 *                    original response completely; acknowledge briefly.
 *   UNRELATED      — new topic.  Discard original; treat new input as a fresh
 *                    turn.  (Alias in the spec: "new topic".)
 */
enum class InterruptionType {
    CONTINUE,
    CLARIFICATION,
    CORRECTION,
    REPLACEMENT,
    URGENT,
    UNRELATED
}
