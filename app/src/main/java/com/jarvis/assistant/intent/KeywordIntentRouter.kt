package com.jarvis.assistant.intent

import android.util.Log

/**
 * KeywordIntentRouter — the spoken-keyword front door.
 *
 * PIPELINE:
 *   1. Normalise the transcript (trim, collapse whitespace, lower-case for matching).
 *   2. Run [KeywordDictionary.matchAll] for exact-match candidates.
 *   3. If any matched: apply [IntentConflictResolver.pick] to choose one primary.
 *      Else: fall back to [FuzzyIntentMatcher.match].
 *      Else: return null (general chat — caller routes elsewhere).
 *   4. Collect modifiers: intrinsic (from the matched entry) + standalone
 *      (from "and remember it" style suffixes).
 *   5. Snapshot [ContextResolver] for a [ResolvedContext].
 *   6. Evaluate risk with [RiskEvaluator] given the transcript + context.
 *   7. Wrap everything in a [CommandEnvelope] and return.
 *
 * THREAD-SAFETY:
 *   Stateless; every collaborator except [ContextSources] is immutable.
 *   Safe to call concurrently from any dispatcher.
 */
class KeywordIntentRouter(
    private val contextResolver:  ContextResolver,
    private val conflictResolver: IntentConflictResolver = IntentConflictResolver(),
    private val riskEvaluator:    RiskEvaluator          = RiskEvaluator(),
    private val fuzzyMatcher:     FuzzyIntentMatcher     = FuzzyIntentMatcher(),
) {

    companion object {
        private const val TAG = "KeywordIntentRouter"
    }

    /**
     * Route [transcript] to a [CommandEnvelope], or return null when nothing
     * matches (the caller should fall through to general-chat handling).
     */
    fun route(transcript: String): CommandEnvelope? {
        val raw = transcript.trim()
        if (raw.isBlank()) return null

        val normalised = raw.replace(Regex("\\s+"), " ")

        // Stage 1+2 — exact first, fuzzy fallback.
        val exactHits = KeywordDictionary.matchAll(normalised)
        val winner: Winner = when {
            exactHits.isNotEmpty() -> {
                val pick = conflictResolver.pick(exactHits)
                    ?: return null
                Winner(
                    intent     = pick.intent,
                    modifiers  = pick.modifiers,
                    label      = pick.label,
                    confidence = pick.confidence,
                )
            }
            else -> {
                val fuzzy = fuzzyMatcher.match(normalised)
                    ?: run {
                        Log.d(TAG, "No intent match for: \"${raw.take(60)}\"")
                        return null
                    }
                Winner(
                    intent     = fuzzy.intent,
                    modifiers  = emptyList(),
                    label      = null,
                    confidence = fuzzy.confidence,
                )
            }
        }

        // Stage 3 — compose-on modifiers ("and remember it", etc.).
        val compositeModifiers = (winner.modifiers + KeywordDictionary.standaloneModifiers(normalised))
            .distinct()

        // Stage 4 — resolve live context.
        val ctx = try {
            contextResolver.snapshot()
        } catch (e: Exception) {
            Log.w(TAG, "Context snapshot threw — proceeding with empty context", e)
            ResolvedContext.EMPTY
        }

        // Stage 5 — risk + confirmation.
        val (risk, requiresConfirmation) = riskEvaluator.evaluate(raw, winner.intent, ctx)

        val envelope = CommandEnvelope(
            rawText              = raw,
            primaryIntent        = winner.intent,
            modifiers            = compositeModifiers,
            label                = winner.label,
            confidence           = winner.confidence,
            resolvedContext      = ctx,
            riskLevel            = risk,
            requiresConfirmation = requiresConfirmation,
        )
        Log.d(TAG, "Routed \"${raw.take(60)}\" → ${winner.intent} " +
                   "(mods=${compositeModifiers}, risk=$risk, confirm=$requiresConfirmation)")
        return envelope
    }

    private data class Winner(
        val intent:     PrimaryIntent,
        val modifiers:  List<IntentModifier>,
        val label:      String?,
        val confidence: Double,
    )
}
