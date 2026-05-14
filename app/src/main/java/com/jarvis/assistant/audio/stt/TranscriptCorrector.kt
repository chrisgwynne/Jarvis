package com.jarvis.assistant.audio.stt

import android.util.Log
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.voice.VoiceFeatureFlags
import com.jarvis.assistant.voice.learning.AliasLearningStore
import kotlin.math.max
import kotlin.math.min

/**
 * TranscriptCorrector — picks the best transcript from Android's N-best STT
 * output, applies phonetic mishear corrections from [VocabularyBiaser], and
 * fixes recipient names against the real contact list.
 *
 * Pipeline for [correct]:
 *   1.  Score every candidate against known vocabulary + command patterns.
 *   2.  Pick the highest scoring candidate (ties broken by STT confidence,
 *       then by original list order — i.e. the recogniser's own pick).
 *   3.  Apply [VocabularyBiaser.PHONETIC_RULES] to the winner, gated by
 *       context predicates so "cat" only becomes "Cath" in a messaging
 *       command.
 *   4.  When the winner looks like a messaging / call command, run the
 *       parsed-recipient through [ContactLookup.fuzzyLookup] to repair
 *       mishears like "mic" → "Mike" using the user's actual address book.
 *   5.  Return [Result] with the corrected transcript, the score, and a
 *       list of correction steps applied so callers can decide whether
 *       to ask for confirmation.
 *
 * Cheap: O(N × W) over candidates × known-vocab-size, no I/O on the hot path
 * except the contact-fuzzy step (which itself caches via ContactLookup).
 */
class TranscriptCorrector(
    private val contacts: ContactLookup,
    /**
     * Optional persistent alias store.  When present and
     * [VoiceFeatureFlags.Flag.ALIAS_LEARNING_ENABLED] is on, learned aliases
     * are applied **before** the static [VocabularyBiaser.PHONETIC_RULES] so
     * the user's own corrections always win.
     */
    private val aliases: AliasLearningStore? = null
) {

    companion object {
        private const val TAG = "TranscriptCorrector"

        /**
         * Derive a [ConfidenceTier] from a raw composite score.  Public so
         * JarvisRuntime can map the corrector's last score back to a tier
         * without re-running the corrector pipeline.
         */
        @JvmStatic
        fun scoreToTier(score: Int): ConfidenceTier = when {
            score >= SCORE_HIGH_THRESHOLD   -> ConfidenceTier.HIGH
            score >= SCORE_MEDIUM_THRESHOLD -> ConfidenceTier.MEDIUM
            else                            -> ConfidenceTier.LOW
        }

        // Confidence buckets used by [Result.confidenceTier].
        // Calibrated to the Phase-4 weights:
        //   strong verb (+8) + app/channel (+7) + contact (+6) + grammar (+5) ≈ 26
        //   plain "what's the weather"                                          ≈ 8
        //   nonsense                                                            ≤ -5
        const val SCORE_HIGH_THRESHOLD   = 14      // execute immediately
        const val SCORE_MEDIUM_THRESHOLD = 6       // brief echo before executing
        // anything lower → ask "did you mean ...?"
    }

    enum class ConfidenceTier { HIGH, MEDIUM, LOW }

    data class Result(
        /** Final transcript after all corrections — feed this into intent parsing. */
        val text: String,
        /** Aggregate score that won the N-best selection. */
        val score: Int,
        /** Tier derived from [score] for confirmation gating. */
        val confidenceTier: ConfidenceTier,
        /** Human-readable correction trail, for logging / debug surfaces. */
        val corrections: List<String>,
        /** The raw candidate that was chosen, before any corrections. */
        val rawWinner: String,
        /** Every candidate the recogniser returned, paired with its score. */
        val rankedCandidates: List<Pair<String, Int>>
    )

    /**
     * Pick + correct.  [candidates] is the N-best list straight from
     * `RecognizerIntent` — ordered by the recogniser's own confidence.
     * [confidences], when supplied, is the parallel FloatArray from
     * `SpeechRecognizer.CONFIDENCE_SCORES`.
     */
    fun correct(
        candidates: List<String>,
        confidences: FloatArray? = null
    ): Result? {
        if (candidates.isEmpty()) return null

        // ── 1. Score every candidate ──────────────────────────────────────────
        val scored = candidates.mapIndexed { idx, c ->
            val s = scoreCandidate(c)
            Triple(c, s, idx)
        }

        // ── 2. Pick the winner ────────────────────────────────────────────────
        val winner = scored.maxWithOrNull(
            compareBy<Triple<String, Int, Int>>({ it.second })
                .thenByDescending { confidences?.getOrNull(it.third) ?: 0f }
                .thenBy { it.third }
        )!!
        val (rawWinner, score, _) = winner
        val ranked = scored
            .sortedWith(
                compareByDescending<Triple<String, Int, Int>> { it.second }
                    .thenBy { it.third }
            )
            .map { it.first to it.second }

        val corrections = mutableListOf<String>()
        // ── Guard the nbest swap ─────────────────────────────────────────────
        // Two conditions decline an apparent winner:
        //   1. The "winner" differs from the raw first candidate ONLY by
        //      edge punctuation — that's no improvement, it's STT noise
        //      (e.g. "What time is it" vs "What time is it/").
        //   2. The winner's score is LOW or negative — we have no evidence
        //      it's actually better than the recogniser's own first pick.
        // In either case we keep the raw first.
        val (effectiveWinner, effectiveScore) =
            if (rawWinner != candidates.first()) {
                val rawFirst = candidates.first()
                val onlyPunctuation = com.jarvis.assistant.voice.routing.TranscriptNormalizer
                    .differsOnlyByPunctuation(rawFirst, rawWinner)
                val noEvidence = score <= 0
                when {
                    onlyPunctuation -> {
                        Log.d(TAG, "[NBEST_SWAP_REJECTED_PUNCTUATION_ONLY] " +
                            "raw=\"$rawFirst\" winner=\"$rawWinner\" score=$score")
                        candidates.first() to scoreCandidate(candidates.first())
                    }
                    noEvidence -> {
                        Log.d(TAG, "[NBEST_SWAP_REJECTED_LOW_SCORE] " +
                            "raw=\"$rawFirst\" winner=\"$rawWinner\" score=$score")
                        candidates.first() to scoreCandidate(candidates.first())
                    }
                    else -> {
                        corrections.add(
                            "nbest_swap(\"$rawFirst\" → \"$rawWinner\" score=$score)"
                        )
                        rawWinner to score
                    }
                }
            } else {
                rawWinner to score
            }

        // ── 2b. Learned aliases (user-taught corrections) ─────────────────────
        // Applied first so explicit "no, I meant X" corrections beat any
        // static phonetic rule.  Each lookup is context-gated: messaging /
        // contact / device aliases only fire when the surrounding command
        // grammar supports them.
        var working = effectiveWinner
        val aliasStore = aliases
        if (aliasStore != null &&
            VoiceFeatureFlags.isEnabled(VoiceFeatureFlags.Flag.ALIAS_LEARNING_ENABLED)
        ) {
            val tokens = working.split(Regex("\\s+")).filter { it.isNotBlank() }
            val ctx = inferAliasContext(working.lowercase())
            for ((idx, tok) in tokens.withIndex()) {
                val clean = tok.lowercase().trim(',', '.', '!', '?', ':', ';')
                if (clean.isBlank()) continue
                Log.d(TAG, "[ALIAS_LOOKUP] token=\"$clean\" ctx=${ctx?.tag ?: "none"}")
                val matchedCtx = ctx ?: continue
                val hit = aliasStore.lookup(clean, matchedCtx) ?: continue
                if (hit.intended.equals(tok, ignoreCase = true)) continue
                // Rewrite token in-place, preserving leading punctuation only.
                val before = working
                working = working.replaceRange(
                    findTokenRange(working, idx) ?: continue,
                    hit.intended
                )
                if (before != working) {
                    corrections.add("alias(\"$clean\" → \"${hit.intended}\" ctx=${matchedCtx.tag})")
                    Log.d(TAG, "[ALIAS_USED] \"$clean\" → \"${hit.intended}\" ctx=${matchedCtx.tag}")
                }
            }
        }

        // ── 3. Phonetic corrections ───────────────────────────────────────────
        for (rule in VocabularyBiaser.PHONETIC_RULES) {
            val lower = working.lowercase()
            if (!rule.pattern.containsMatchIn(working)) continue
            if (!rule.contextOk(lower)) continue
            val before = working
            working = rule.pattern.replace(working, rule.replacement)
            if (before != working) {
                corrections.add("phonetic(\"${rule.pattern.pattern}\" → \"${rule.replacement}\")")
            }
        }

        // ── 4. Contact-aware recipient repair ─────────────────────────────────
        val repaired = repairRecipient(working)
        if (repaired != null && repaired.first != working) {
            corrections.add("contact_fuzzy(\"${repaired.second}\" → \"${repaired.third}\")")
            working = repaired.first
        }

        // ── 4b. Final edge-punctuation strip ──────────────────────────────────
        // After all corrections, ensure the text we ship to the router is
        // free of edge punctuation noise.  Internal punctuation (apostrophes,
        // hyphens, mid-word symbols) is preserved.
        working = com.jarvis.assistant.voice.routing.TranscriptNormalizer.normalize(working)

        // ── 5. Pack the result ────────────────────────────────────────────────
        val tier = when {
            effectiveScore >= SCORE_HIGH_THRESHOLD   -> ConfidenceTier.HIGH
            effectiveScore >= SCORE_MEDIUM_THRESHOLD -> ConfidenceTier.MEDIUM
            else                                     -> ConfidenceTier.LOW
        }

        Log.d(TAG, "[STT_CORRECT] raw_first=\"${candidates.first()}\" winner=\"$effectiveWinner\" " +
            "final=\"$working\" score=$effectiveScore tier=$tier candidates=${candidates.size} " +
            "corrections=${corrections}")

        return Result(
            text             = working,
            score            = effectiveScore,
            confidenceTier   = tier,
            corrections      = corrections,
            rawWinner        = effectiveWinner,
            rankedCandidates = ranked
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scoring
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Scoring weights aligned with the Phase-4 spec.
     *   +8  explicit local action verb (call/send/open/turn/play…)
     *   +7  known app / channel word (WhatsApp, SMS, call, camera…)
     *   +6  known contact (static + runtime)
     *   +6  known HA entity / room / device
     *   +5  command grammar match (verb + object skeleton)
     *   +3  known installed-app label (runtime vocab)
     *   +2  known assistant/project term (Jarvis, OpenClaw, Tailscale)
     *   -3  ambiguous recipient / device (multiple plausible matches)
     *   -4  missing required slot (verb without object)
     *   -5  impossible route (no recognisable word at all)
     */
    private fun scoreCandidate(candidate: String): Int {
        val lower = candidate.lowercase()
        var score = 0

        val hasActionVerb  = STRONG_VERBS.containsMatchIn(lower)
        val hasAnyVerb     = matchesCommandPattern(lower)
        val hasApp         = containsAny(lower, VocabularyBiaser.knownApps())
        val hasChannel     = containsAny(lower, VocabularyBiaser.CHANNEL_WORDS)
        val hasContact     = containsAny(lower, VocabularyBiaser.knownContacts())
        val hasRoom        = containsAny(lower, VocabularyBiaser.knownRooms())
        val hasDevice      = containsAny(lower, VocabularyBiaser.knownDevices())
        val hasAssistant   = containsAny(lower, VocabularyBiaser.ASSISTANT_TERMS)

        if (hasActionVerb)             score += 8
        if (hasApp || hasChannel)      score += 7
        if (hasContact)                score += 6
        if (hasRoom || hasDevice)      score += 6
        if (hasAnyVerb && (hasApp || hasContact || hasRoom || hasDevice)) score += 5
        if (hasAssistant)              score += 2

        // -4 if a verb is present but no object at all could be matched.
        if (hasAnyVerb && !hasApp && !hasContact && !hasRoom && !hasDevice && !hasAssistant) {
            score -= 4
        }

        // -5 if nothing recognisable was found (no verb, no noun, no project term).
        if (!hasAnyVerb && !hasApp && !hasChannel && !hasContact &&
            !hasRoom && !hasDevice && !hasAssistant) {
            score -= 5
        }

        // -3 trailing punctuation / symbol garbage.  Punctuation noise at the
        // edge of a candidate is a strong signal that the recogniser hedged
        // (alternate hypothesis with a closing token like '/' or '?!'), not
        // that the alternative is actually a better transcription.  Without
        // this penalty the corrector would happily swap "What time is it"
        // for "What time is it/" and break downstream tool matching.
        if (TRAILING_PUNCT_RX.containsMatchIn(candidate)) {
            score -= 3
        }

        return score
    }

    /** Stray edge punctuation/symbols a clean utterance shouldn't end with. */
    private val TRAILING_PUNCT_RX = Regex("""[/\\|*#~<>(){}\[\]]+\s*$""")

    /** Strong action verbs — count for the +8 bonus. */
    private val STRONG_VERBS = Regex(
        """\b(send|text|message|whatsapp|wa|call|ring|phone|email|open|launch|play|pause|stop|turn|switch|set|dim|brighten|lock|unlock|take|capture|remind|note|remember|cancel|skip|next|previous)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun containsAny(lowerHaystack: String, vocab: Set<String>): Boolean =
        vocab.any { token ->
            val t = token.lowercase()
            // Cheap word-boundary check that doesn't over-match across spaces.
            Regex("\\b${Regex.escape(t)}\\b").containsMatchIn(lowerHaystack)
        }

    /** Cheap regex shortlist for command verbs we care about. */
    private val COMMAND_VERBS = Regex(
        """\b(send|text|message|whatsapp|wa|call|ring|email|open|launch|play|pause|stop|turn|switch|set|dim|brighten|lock|unlock|remind|note|remember|what['']?s|when|where|who|tell|ask)\b""",
        RegexOption.IGNORE_CASE
    )
    private fun matchesCommandPattern(lower: String): Boolean =
        COMMAND_VERBS.containsMatchIn(lower)

    // ──────────────────────────────────────────────────────────────────────────
    // Contact-aware recipient repair
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns Triple(newTranscript, originalRecipient, correctedRecipient)
     * when the transcript looks like a messaging / call command and the
     * spoken recipient name does not exist in the contact book but a
     * fuzzy contact match is found.  Returns null otherwise.
     *
     * Uses [ContactLookup.fuzzyLookup] (already implemented with Jaro-Winkler)
     * combined with Levenshtein distance as a tiebreaker.
     */
    private fun repairRecipient(transcript: String): Triple<String, String, String>? {
        val lower = transcript.lowercase()
        if (!Regex("""\b(send|text|message|whatsapp|wa|call|ring)\b""").containsMatchIn(lower)) {
            return null
        }

        // Pull the recipient with the same family of patterns the parsers use.
        // We deliberately stay slightly lenient so we catch the "to" / "a"
        // article forms.
        val recipMatch = Regex(
            """\b(?:to|call|ring|text|message|whatsapp|wa)\s+([A-Za-z][A-Za-z'\- ]{1,30}?)(?=\s+(?:on|via|using|a\s|an\s|saying|tell|say|that|and|$)|$)""",
            RegexOption.IGNORE_CASE
        ).find(transcript) ?: return null

        val spoken = recipMatch.groupValues[1].trim()
        if (spoken.isBlank()) return null
        if (spoken.length < 2) return null

        // If it already matches an existing contact (exact substring) leave alone.
        val direct = contacts.find(spoken)
        if (direct != null && direct.displayName.lowercase().contains(spoken.lowercase())) {
            return null
        }

        // Fuzzy lookup against the address book.
        val fuzzy = contacts.fuzzyLookup(spoken)
        if (fuzzy.isEmpty()) return null

        // Combine the contact lookup's similarity score with a Levenshtein
        // sanity check — Jaro-Winkler over-rewards prefix matches, so we
        // require the edit distance to also be plausible (≤ 3 for short
        // names, ≤ 5 for longer).
        val best = fuzzy.maxByOrNull { it.similarity } ?: return null
        val firstName = best.name.split(' ').firstOrNull() ?: best.name
        val dist = levenshtein(spoken.lowercase(), firstName.lowercase())
        val maxAllowed = if (firstName.length <= 5) 2 else 3
        if (dist > maxAllowed) return null

        val replaced = transcript.replaceRange(
            recipMatch.groups[1]!!.range,
            firstName
        )
        return Triple(replaced, spoken, firstName)
    }

    /**
     * Map the lower-cased transcript to the [AliasLearningStore.Context_]
     * whose patterns it satisfies.  Returns null when no context applies —
     * the alias lookup is skipped entirely in that case, which is exactly
     * what we want for generic conversation.
     */
    private fun inferAliasContext(lower: String): AliasLearningStore.Context_? {
        val isMessaging = Regex("""\b(send|text|message|whatsapp|wa|email)\b""").containsMatchIn(lower)
        val isContact   = Regex("""\b(call|ring|phone|to)\s+[a-z]""").containsMatchIn(lower)
        val isApp       = Regex("""\b(open|launch|start)\b""").containsMatchIn(lower)
        val isDevice    = Regex("""\b(turn|switch|set|dim|brighten|lock|unlock)\b""").containsMatchIn(lower)
        return when {
            isMessaging         -> AliasLearningStore.Context_.MESSAGING
            isContact           -> AliasLearningStore.Context_.CONTACT
            isApp               -> AliasLearningStore.Context_.APP
            isDevice            -> AliasLearningStore.Context_.DEVICE
            else                -> {
                Log.v(TAG, "[ALIAS_SKIPPED_CONTEXT_MISMATCH] no command context in \"$lower\"")
                null
            }
        }
    }

    /** Find the character range of the [tokenIndex]-th whitespace-split token. */
    private fun findTokenRange(text: String, tokenIndex: Int): IntRange? {
        val tokens = Regex("\\S+").findAll(text).toList()
        return tokens.getOrNull(tokenIndex)?.range
    }

    /** Plain Levenshtein, two-row rolling DP. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    @Suppress("unused")  // kept for potential future use
    private fun maxLen(a: String, b: String) = max(a.length, b.length)
}
