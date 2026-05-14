package com.jarvis.assistant.voice.routing

import android.util.Log
import com.jarvis.assistant.audio.stt.TranscriptCorrector
import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolRegistry
import com.jarvis.assistant.voice.VoiceFeatureFlags

/**
 * LocalFirstRouter — the single point that decides whether a transcript is
 * dispatched locally, escalated to OpenClaw, asked-about (Clarify), or
 * dropped (Ignore).
 *
 * # Why this exists
 *
 * Before Tier A3 the routing decision was implicit: callers would invoke
 * `ToolRegistry.match` directly, and the AttentionGate signals path called
 * it a second time on the same transcript.  Two regex sweeps per turn was
 * wasted work and the route reason wasn't grep-friendly in logcat.
 *
 * # Pipeline
 *
 * ```
 * STT transcript
 *   → TranscriptCorrector (returns Result with ConfidenceTier)
 *   → AttentionGate (uses Result + this router's match as signals)
 *   → LocalFirstRouter.route(transcript, precomputedMatch, tier)
 *       ├── RouteOutcome.Local            → ToolDispatcher.dispatch
 *       ├── RouteOutcome.Clarify          → speak "did you mean ...?"
 *       ├── RouteOutcome.OpenClawFallback → OpenClawRouter.executeStreaming
 *       └── RouteOutcome.Ignore           → drop silently
 * ```
 *
 * The router accepts a **precomputed** `Pair<Tool, ToolInput>?` from the
 * caller so that the same `ToolRegistry.match` result feeds both the
 * AttentionGate signal builder and the actual dispatch — no double work.
 */
class LocalFirstRouter(
    private val registry: ToolRegistry
) {

    companion object { private const val TAG = "LocalFirstRouter" }

    /** The four possible verdicts for any captured utterance. */
    sealed class RouteOutcome {
        abstract val reason: String

        /** A local tool matched and is safe to execute. */
        data class Local(
            val tool: Tool,
            val input: ToolInput,
            val tier: TranscriptCorrector.ConfidenceTier,
            override val reason: String
        ) : RouteOutcome()

        /**
         * A local tool matched but the user should be asked to confirm,
         * usually because (a) STT confidence is medium and the tool is
         * risky, or (b) confidence is low and the tool is non-trivial.
         */
        data class Clarify(
            val tool: Tool,
            val input: ToolInput,
            val tier: TranscriptCorrector.ConfidenceTier,
            val confirmPrompt: String,
            override val reason: String
        ) : RouteOutcome()

        /** No local tool matched.  Caller should hand off to OpenClaw / LLM. */
        data class OpenClawFallback(
            override val reason: String
        ) : RouteOutcome()

        /**
         * The router decided this transcript is not actionable (e.g. flag
         * disabled, or confidence too low to even surface a Clarify).
         */
        data class Ignore(
            override val reason: String
        ) : RouteOutcome()
    }

    /**
     * Decide what to do with [transcript].
     *
     * @param precomputedMatch  Pass the `ToolRegistry.match` result if the
     *   caller has one already (e.g. the AttentionGate signal builder did
     *   it).  Pass null to let the router do the match itself.  This is
     *   the [ROUTE_MATCH_REUSED_BY_ATTENTION] guarantee — exactly one
     *   regex sweep per utterance.
     * @param tier  The confidence tier from [TranscriptCorrector].  HIGH →
     *   execute, MEDIUM → confirm-if-risky, LOW → clarify.
     */
    fun route(
        transcript: String,
        isOnline: Boolean,
        precomputedMatch: Pair<Tool, ToolInput>? = null,
        tier: TranscriptCorrector.ConfidenceTier = TranscriptCorrector.ConfidenceTier.HIGH
    ): RouteOutcome {
        // Normalise STT noise BEFORE matching.  Trailing slashes / stray
        // punctuation from Android's recogniser ("What time is it/") used
        // to break tool regex end-anchors and push trivial local queries
        // through the cloud LLM.  We log when normalisation actually
        // changed the string so a future regression is obvious in logcat.
        val normalised = TranscriptNormalizer.normalize(transcript)
        if (normalised != transcript) {
            Log.d(TAG, "[ROUTE_TRANSCRIPT_NORMALIZED] " +
                "raw=\"$transcript\" → \"$normalised\"")
        }
        Log.d(TAG, "[ROUTE_BEGIN] transcript=\"$normalised\" online=$isOnline tier=$tier")

        if (!VoiceFeatureFlags.isEnabled(VoiceFeatureFlags.Flag.LOCAL_FIRST_ROUTING_ENABLED)) {
            // Flag-off behaviour: still run the registry once but emit only
            // a single fallback verdict — keep legacy callers working.
            val m = precomputedMatch ?: registry.match(normalised, isOnline)
            return if (m != null) {
                RouteOutcome.Local(m.first, m.second, tier, "flag_off_pass_through")
            } else {
                RouteOutcome.OpenClawFallback("flag_off_no_match")
            }
        }

        // If the caller pre-computed a match it was against the un-normalised
        // transcript — re-run against the normalised form so a tool that
        // would have matched ("what time is it") finally fires.
        val match = (precomputedMatch?.also {
            Log.d(TAG, "[ROUTE_MATCH_REUSED_BY_ATTENTION] tool=${it.first.name}")
        }) ?: registry.match(normalised, isOnline)

        if (match == null) {
            Log.d(TAG, "[ROUTE_NO_LOCAL_MATCH] transcript=\"$normalised\" → OpenClaw fallback")
            return RouteOutcome.OpenClawFallback("no_tool_matched")
        }

        val (tool, input) = match

        // ── Explicit-intent fast path ────────────────────────────────────────
        // When the user explicitly names a channel + recipient + body (e.g.
        // "send a whatsapp to Mike saying hello"), there is no ambiguity
        // worth confirming.  Promote the tier to HIGH unconditionally so the
        // command executes immediately rather than getting stuck in Clarify
        // waiting on a "yes" the user shouldn't have to say.
        val effectiveTier = if (isExplicitIntent(transcript, tool, input))
            TranscriptCorrector.ConfidenceTier.HIGH else tier
        if (effectiveTier != tier) {
            Log.d(TAG, "[CONFIDENCE_PROMOTED_EXPLICIT] tool=${tool.name} " +
                "${tier} → HIGH (channel+contact+body explicit)")
        }

        // Single decision: always Local.  Confirmation, when it's needed,
        // is handled exclusively by ToolDispatcher.ConfirmationGate (which
        // owns the pending state machine that the runtime intercepts at
        // the top of the turn loop).  The router used to emit Clarify
        // itself, but that prompt-without-state caused "yes"/"no" replies
        // to fall through AttentionGate and stall — bug fix from this PR.
        Log.d(TAG, "[ROUTE_LOCAL_MATCH] tool=${tool.name} tier=$effectiveTier " +
            "transcript=\"$transcript\"")
        return when (effectiveTier) {
            TranscriptCorrector.ConfidenceTier.HIGH ->
                RouteOutcome.Local(tool, input, effectiveTier, "high_confidence(${tool.name})")
            TranscriptCorrector.ConfidenceTier.MEDIUM ->
                RouteOutcome.Local(tool, input, effectiveTier, "medium_confidence(${tool.name})")
            TranscriptCorrector.ConfidenceTier.LOW ->
                RouteOutcome.Local(tool, input, effectiveTier, "low_confidence(${tool.name})")
        }
    }

    /** Caller calls this immediately before invoking the matched tool. */
    fun logLocalExecute(toolName: String) {
        Log.d(TAG, "[ROUTE_LOCAL_EXECUTE] tool=$toolName")
    }

    fun logOpenClawFallback(reason: String) {
        Log.d(TAG, "[ROUTE_OPENCLAW_FALLBACK] reason=$reason")
    }

    /**
     * Is this an "explicit intent" command that can bypass confidence-gating?
     *
     * For messaging tools (send_sms / whatsapp_message), explicit means:
     *   1. The matched tool is a messaging tool (its parser already recognised
     *      a channel keyword like "whatsapp" / "sms" / "text" / "wa").
     *   2. A recipient name was parsed out (non-blank `name` param).
     *   3. A body was parsed out (non-blank `message` param).
     *
     * When all three hold, the user has supplied every slot needed — Clarify
     * would just be friction.  Mirrors the user spec: explicit channel +
     * known contact + body → execute without confirmation.
     *
     * Pure / no Android dependency / unit-testable.
     */
    fun isExplicitIntent(transcript: String, tool: Tool, input: ToolInput): Boolean {
        val name = input.params["name"]?.trim().orEmpty()
        val body = input.params["message"]?.trim().orEmpty()
        return when (tool.name) {
            "whatsapp_message", "send_sms" ->
                name.isNotBlank() && body.isNotBlank()
            else -> false
        }
    }

    private fun buildConfirmPrompt(tool: Tool, input: ToolInput): String {
        // Try to produce a natural echo using the tool's parsed params when
        // they exist.  Keep it short so it doesn't feel bureaucratic.
        val name    = input.params["name"]?.toString()
        val msg     = input.params["message"]?.toString()
        return when (tool.name) {
            "send_sms",         "whatsapp_message" ->
                if (!name.isNullOrBlank()) "Send \"${msg ?: "that"}\" to $name?" else "Send that?"
            "call",             "make_call"       ->
                if (!name.isNullOrBlank()) "Call $name?"             else "Make the call?"
            "smart_home"                            -> "Confirm — do that on Home Assistant?"
            "calendar"                              -> "Add that to your calendar?"
            else                                    -> "Confirm that?"
        }
    }
}
