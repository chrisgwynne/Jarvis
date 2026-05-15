package com.jarvis.assistant.preferences

import android.util.Log

/**
 * Top-level coordinator for response preference learning.
 *
 * Sits between the conversation loop and [ResponsePreferenceRepository].
 * Call [tryDetectAndStore] on every user utterance before routing to
 * IntentClassifier — if it returns a non-null result, skip IntentClassifier
 * and reply with the confirmation.
 *
 * Call [formatWeather] / [applyToText] from tool formatters to apply any
 * stored preference without an LLM call.
 *
 * Call [buildPromptFragment] to inject active preferences into the system
 * prompt for LLM-handled domains.
 */
class ResponsePreferenceEngine(
    private val repo: ResponsePreferenceRepository,
) {
    /** Tracks the most recently active tool domain this session. */
    var lastActiveDomain: ResponseDomain = ResponseDomain.GENERAL

    // ── Detection + persistence ──────────────────────────────────────────────

    /**
     * Returns a [DetectionResult] if a preference was detected and stored,
     * null otherwise.  Never throws.
     */
    suspend fun tryDetectAndStore(utterance: String): DetectionResult? {
        return try {
            if (PreferenceDetector.isOverride(utterance)) return null
            if (!PreferenceDetector.isPreference(utterance)) return null

            val pref = PreferenceExtractor.extract(utterance, lastActiveDomain)
                ?: return null

            val saved = repo.save(pref)
            val confirmation = PreferenceExtractor.buildConfirmation(saved)
            Log.d(TAG, "Stored preference: domain=${saved.domain} rule=${saved.ruleType} id=${saved.id}")
            DetectionResult(preference = saved, confirmation = confirmation)
        } catch (e: Exception) {
            Log.e(TAG, "Preference detection failed", e)
            null
        }
    }

    /**
     * Returns true if [utterance] is a one-off override, meaning the current
     * response should ignore stored preferences for [domain].
     */
    fun isOneOffOverride(utterance: String): Boolean =
        PreferenceDetector.isOverride(utterance)

    // ── Application to local formatters ─────────────────────────────────────

    /**
     * Applies any active WEATHER preference to the given components.
     * Returns null if no active preference — caller should use its own
     * default formatting.
     */
    suspend fun formatWeather(components: WeatherComponents): String? {
        val pref = repo.getActive(ResponseDomain.WEATHER) ?: return null
        if (!pref.isActive()) return null
        return components.format(pref)
    }

    /**
     * Applies an active preference for [domain] to [text] where possible.
     * For domains without a field map this is a no-op (returns null).
     * Currently used for LENGTH preferences on any domain.
     */
    suspend fun applyLengthPreference(domain: ResponseDomain, text: String): String? {
        val pref = repo.getActive(domain) ?: return null
        if (!pref.isActive()) return null
        if (pref.ruleType != PreferenceRuleType.LENGTH) return null
        return when (pref.preferredLength) {
            PreferredLength.BRIEF    -> text.lines().firstOrNull()?.take(180) ?: text
            PreferredLength.DETAILED -> text
            else                     -> null
        }
    }

    // ── Prompt injection ─────────────────────────────────────────────────────

    /**
     * Builds a system-prompt fragment summarising all active preferences.
     * Returns null if there are no active preferences.
     *
     * Injected into [PromptAssembler] so the LLM respects preferences for
     * domains it handles directly (e.g. GENERAL, LLM_CHAT).
     */
    suspend fun buildPromptFragment(): String? {
        val active = repo.getAll().filter { it.isActive() }
        if (active.isEmpty()) return null

        val lines = active.joinToString("\n") { pref ->
            val rule = when (pref.ruleType) {
                PreferenceRuleType.INCLUDE_ONLY  -> "Only include: ${pref.includeFields.joinToString(", ")}"
                PreferenceRuleType.EXCLUDE       -> "Exclude: ${pref.excludeFields.joinToString(", ")}"
                PreferenceRuleType.LENGTH        -> "Length: ${pref.preferredLength.displayLabel}"
                PreferenceRuleType.FORMAT        -> "Format: ${pref.exampleFormat ?: "follow user template"}"
                PreferenceRuleType.ORDER         -> "Order: ${pref.preferredOrder.joinToString(", ")}"
                PreferenceRuleType.DETAIL_LEVEL  -> "Style: ${pref.sourceUtterance}"
            }
            "- ${pref.domain.displayName}: $rule"
        }

        return "USER RESPONSE PREFERENCES (apply these; user preference overrides personality defaults):\n$lines"
    }

    // ── Management ───────────────────────────────────────────────────────────

    suspend fun resetDomain(domain: ResponseDomain) = repo.resetDomain(domain)

    suspend fun resetAll() = repo.resetAll()

    suspend fun getAll(): List<ResponsePreference> = repo.getAll()

    suspend fun getActive(domain: ResponseDomain): ResponsePreference? =
        repo.getActive(domain)

    // ────────────────────────────────────────────────────────────────────────

    data class DetectionResult(
        val preference: ResponsePreference,
        val confirmation: String,
    )

    private companion object {
        const val TAG = "ResponsePreferenceEngine"
    }
}
