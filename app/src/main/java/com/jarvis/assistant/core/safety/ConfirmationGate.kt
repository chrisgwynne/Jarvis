package com.jarvis.assistant.core.safety

import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ConfirmationGate — holds in-flight "are you sure?" handshakes for any
 * tool with [RiskClass] MEDIUM or HIGH.
 *
 * Flow:
 *   1. Dispatcher sees a risky tool, calls [registerPending]. Gets back a
 *      [Pending] with an id + prompt text. Speaks the prompt. Bails out
 *      of the current tool run, returns NeedsConfirmation to runtime.
 *   2. User's next utterance arrives. Runtime calls [consume] with the
 *      raw transcript. If affirmative, returns the [Pending] it matched;
 *      runtime re-dispatches the stashed tool with [bypass]=true.
 *   3. If the utterance is negative, or if TTL elapses, [consume] clears
 *      the pending and the original action is dropped.
 *
 * Stateless per-tool: the gate remembers tool name + input + the prompt
 * that was spoken. It does not hold any tool instance reference — that's
 * the caller's responsibility via a lookup by tool name.
 *
 * Thread-safe via [ConcurrentHashMap]. TTL expiry happens lazily on
 * every [consume] / [registerPending] call; there's no background timer.
 */
class ConfirmationGate(
    private val mediumTtlMs: Long = 15_000L,
    private val highTtlMs: Long = 30_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Pending(
        val id: String,
        val toolName: String,
        val input: ToolInput,
        val risk: RiskClass,
        val prompt: String,
        val originatingTranscript: String,
        val expiresAtMs: Long,
    )

    data class Registered(val pending: Pending, val prompt: String)

    private val pendings = ConcurrentHashMap<String, Pending>()

    /** Active pending for a given tool, if any. Latest wins per tool. */
    private fun findPending(toolName: String, now: Long): Pending? =
        pendings.values
            .filter { it.toolName == toolName && it.expiresAtMs > now }
            .maxByOrNull { it.expiresAtMs }

    fun registerPending(
        tool: Tool,
        input: ToolInput,
        originatingTranscript: String,
        customPrompt: String? = null,
    ): Registered {
        expireStale(nowMs())
        val ttl = if (tool.riskClass == RiskClass.HIGH) highTtlMs else mediumTtlMs
        val prompt = customPrompt?.takeIf { it.isNotBlank() } ?: buildPrompt(tool, input)
        val pending = Pending(
            id = UUID.randomUUID().toString(),
            toolName = tool.name,
            input = input,
            risk = tool.riskClass,
            prompt = prompt,
            originatingTranscript = originatingTranscript,
            expiresAtMs = nowMs() + ttl,
        )
        pendings.values.removeAll { it.toolName == tool.name }
        pendings[pending.id] = pending
        return Registered(pending, prompt)
    }

    sealed class Verdict {
        data class Affirmed(val pending: Pending) : Verdict()
        data class Declined(val pending: Pending) : Verdict()
        object None : Verdict()
    }

    /**
     * Inspect [utterance] against any pending confirmation. If it matches
     * an affirmative token returns Affirmed + clears that pending. If it
     * matches a negative token returns Declined + clears. Otherwise None.
     *
     * "Soft" path: if there is a single pending and the utterance is a
     * brand-new request (longer than 4 words or contains an imperative
     * verb), the pending is also dropped as implicitly declined so the
     * user doesn't get stuck in a confirmation they've moved on from.
     */
    fun consume(utterance: String): Verdict {
        val now = nowMs()
        expireStale(now)
        val text = utterance.trim().lowercase()
        if (text.isEmpty()) return Verdict.None
        val pending = pendings.values.maxByOrNull { it.expiresAtMs } ?: return Verdict.None
        if (pending.expiresAtMs <= now) return Verdict.None

        if (isAffirmative(text)) {
            pendings.remove(pending.id)
            return Verdict.Affirmed(pending)
        }
        if (isNegative(text)) {
            pendings.remove(pending.id)
            return Verdict.Declined(pending)
        }
        if (looksLikeNewRequest(text)) {
            pendings.remove(pending.id)
            return Verdict.Declined(pending)
        }
        return Verdict.None
    }

    fun cancel(toolName: String): Int {
        val before = pendings.size
        pendings.values.removeAll { it.toolName == toolName }
        return before - pendings.size
    }

    fun clear() = pendings.clear()

    fun snapshot(): List<Pending> = pendings.values.sortedByDescending { it.expiresAtMs }

    private fun expireStale(now: Long) {
        pendings.values.removeAll { it.expiresAtMs <= now }
    }

    private fun buildPrompt(tool: Tool, input: ToolInput): String {
        val subject = input.transcript.trim().takeIf { it.isNotEmpty() }
            ?: tool.name.replace('_', ' ')
        return when (tool.riskClass) {
            RiskClass.HIGH -> "Just to confirm — $subject. Yes or no?"
            RiskClass.MEDIUM -> "$subject — go ahead?"
            RiskClass.LOW -> ""
        }
    }

    private fun isAffirmative(text: String): Boolean = AFFIRM.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }
    private fun isNegative(text: String): Boolean = DECLINE.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }
    private fun looksLikeNewRequest(text: String): Boolean {
        if (text.split(' ').size > 4) return true
        return NEW_REQUEST_VERBS.any { text.startsWith("$it ") || text.contains(" $it ") }
    }

    companion object {
        private val AFFIRM = setOf(
            "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "go ahead",
            "do it", "confirm", "affirmative", "please do", "go on",
        )
        private val DECLINE = setOf(
            "no", "nope", "nah", "cancel", "stop", "don't", "dont", "never mind",
            "forget it", "skip it", "not now",
        )
        private val NEW_REQUEST_VERBS = setOf(
            "call", "text", "message", "email", "remind", "set", "open", "play",
            "turn", "start", "stop", "navigate", "search",
        )
    }
}
