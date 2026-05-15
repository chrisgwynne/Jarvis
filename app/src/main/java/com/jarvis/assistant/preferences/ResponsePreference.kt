package com.jarvis.assistant.preferences

/**
 * ResponsePreference — a single stored user preference for how Jarvis
 * should format responses in a given domain.
 *
 * A preference has exactly one [ruleType]:
 *  - INCLUDE_ONLY: only surface the fields in [includeFields]
 *  - EXCLUDE: surface everything except [excludeFields]
 *  - LENGTH: use [preferredLength] to govern verbosity
 *  - FORMAT: follow [exampleFormat] literally as a template
 *  - ORDER: reorder fields per [preferredOrder]
 *  - DETAIL_LEVEL: a semantic instruction captured in [sourceUtterance]
 *
 * For each domain only one active (enabled=true) preference is kept.
 * A new preference for the same domain overwrites the previous one.
 */
data class ResponsePreference(
    val id: Long = 0L,
    val domain: ResponseDomain,
    val ruleType: PreferenceRuleType,
    /** For INCLUDE_ONLY — the only fields to include. */
    val includeFields: List<String> = emptyList(),
    /** For EXCLUDE — the fields to drop from the response. */
    val excludeFields: List<String> = emptyList(),
    val preferredLength: PreferredLength = PreferredLength.DEFAULT,
    /** Ordered field names for ORDER rule type. */
    val preferredOrder: List<String> = emptyList(),
    /** Human-readable example of what the output should look like. */
    val exampleFormat: String? = null,
    val appliesToVoice: Boolean = true,
    val appliesToText: Boolean = true,
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** The raw utterance that triggered this preference. */
    val sourceUtterance: String,
    val enabled: Boolean = true,
) {
    fun isActive(): Boolean = enabled && confidence >= 0.50f
}

enum class PreferenceRuleType {
    INCLUDE_ONLY,
    EXCLUDE,
    LENGTH,
    FORMAT,
    ORDER,
    DETAIL_LEVEL,
}

enum class PreferredLength(val displayLabel: String) {
    BRIEF("Brief"),
    MEDIUM("Medium"),
    DETAILED("Detailed"),
    DEFAULT("Default"),
}
