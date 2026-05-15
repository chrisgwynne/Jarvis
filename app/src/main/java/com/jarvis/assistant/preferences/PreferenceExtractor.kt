package com.jarvis.assistant.preferences

/**
 * Extracts a structured [ResponsePreference] from a raw preference utterance.
 *
 * Uses keyword maps per domain to identify which fields the user wants
 * included, excluded, or reordered, and infers the rule type from phrase
 * structure (e.g. "just X and Y" → INCLUDE_ONLY, "don't tell me X" → EXCLUDE,
 * "keep it brief" → LENGTH).
 */
object PreferenceExtractor {

    // ── Per-domain field keywords ────────────────────────────────────────────

    private val WEATHER_FIELDS: Map<String, String> = mapOf(
        "condition"     to "condition",
        "conditions"    to "condition",
        "weather"       to "condition",
        "temperature"   to "temperature",
        "temp"          to "temperature",
        "degrees"       to "temperature",
        "feels like"    to "feels_like",
        "feels_like"    to "feels_like",
        "wind"          to "wind",
        "wind speed"    to "wind",
        "humidity"      to "humidity",
        "high"          to "high_low",
        "low"           to "high_low",
        "high and low"  to "high_low",
        "high/low"      to "high_low",
        "rain"          to "precipitation",
        "precipitation" to "precipitation",
        "chance of rain" to "precipitation",
        "uv"            to "uv_index",
        "uv index"      to "uv_index",
        "sunrise"       to "sunrise_sunset",
        "sunset"        to "sunrise_sunset",
    )

    private val CALENDAR_FIELDS: Map<String, String> = mapOf(
        "title"         to "title",
        "name"          to "title",
        "time"          to "time",
        "start time"    to "time",
        "location"      to "location",
        "where"         to "location",
        "description"   to "description",
        "details"       to "description",
        "attendees"     to "attendees",
        "who"           to "attendees",
        "duration"      to "duration",
        "how long"      to "duration",
        "organiser"     to "organizer",
        "organizer"     to "organizer",
    )

    private val TODOIST_FIELDS: Map<String, String> = mapOf(
        "task"          to "task",
        "title"         to "task",
        "due date"      to "due_date",
        "due"           to "due_date",
        "priority"      to "priority",
        "project"       to "project",
        "label"         to "labels",
        "labels"        to "labels",
        "description"   to "description",
        "details"       to "description",
    )

    private val MESSAGES_FIELDS: Map<String, String> = mapOf(
        "sender"        to "sender",
        "from"          to "sender",
        "who"           to "sender",
        "message"       to "content",
        "content"       to "content",
        "text"          to "content",
        "time"          to "time",
        "when"          to "time",
        "preview"       to "preview",
    )

    private val DOMAIN_FIELD_MAP: Map<ResponseDomain, Map<String, String>> = mapOf(
        ResponseDomain.WEATHER        to WEATHER_FIELDS,
        ResponseDomain.CALENDAR       to CALENDAR_FIELDS,
        ResponseDomain.TODOIST        to TODOIST_FIELDS,
        ResponseDomain.MESSAGES       to MESSAGES_FIELDS,
    )

    // ── Length keywords ──────────────────────────────────────────────────────

    private val BRIEF_PATTERNS = listOf("brief", "short", "concise", "quick", "simple", "one line",
                                        "one sentence", "just the basics", "bare minimum")
    private val DETAILED_PATTERNS = listOf("detailed", "full", "complete", "everything",
                                           "all of it", "the works", "more detail", "in full")

    // ── Exclusion trigger phrases ────────────────────────────────────────────

    private val EXCLUDE_PREFIXES = listOf(
        "don't tell me", "dont tell me", "don't include", "dont include",
        "don't mention", "dont mention", "skip", "leave out", "omit", "remove", "drop",
        "without", "no ", "not the ", "never include", "never mention",
    )

    // ── Include-only trigger phrases ─────────────────────────────────────────

    private val INCLUDE_ONLY_PREFIXES = listOf(
        "just ", "only ", "just tell me", "only tell me",
        "give me just", "give me only", "stick to", "focus on",
        "i only want", "i just want", "i only need",
    )

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Attempts to extract a [ResponsePreference] from [utterance].
     *
     * Returns null if no domain or rule type can be confidently inferred.
     *
     * @param utterance   the raw user utterance flagged as a preference
     * @param lastDomain  the most recently active domain (fallback if no
     *                    domain keyword is present in the utterance)
     */
    fun extract(
        utterance: String,
        lastDomain: ResponseDomain = ResponseDomain.GENERAL,
    ): ResponsePreference? {
        val lower = utterance.lowercase().trim()

        val domain = ResponseDomain.fromKeywords(lower) ?: lastDomain

        // LENGTH rule — check before field extraction
        val lengthRule = extractLength(lower)
        if (lengthRule != null) {
            return ResponsePreference(
                domain = domain,
                ruleType = PreferenceRuleType.LENGTH,
                preferredLength = lengthRule,
                sourceUtterance = utterance,
            )
        }

        // DETAIL_LEVEL — catch-all semantic instruction with no field mapping
        val fieldMap = DOMAIN_FIELD_MAP[domain]
        if (fieldMap == null) {
            return ResponsePreference(
                domain = domain,
                ruleType = PreferenceRuleType.DETAIL_LEVEL,
                sourceUtterance = utterance,
            )
        }

        // Identify which known fields are mentioned
        val mentionedFields = fieldMap.entries
            .filter { (keyword, _) -> lower.contains(keyword) }
            .map { it.value }
            .distinct()

        if (mentionedFields.isEmpty()) {
            // No recognisable fields — store as DETAIL_LEVEL (LLM will interpret)
            return ResponsePreference(
                domain = domain,
                ruleType = PreferenceRuleType.DETAIL_LEVEL,
                sourceUtterance = utterance,
            )
        }

        // Determine rule type from phrase structure
        return when {
            EXCLUDE_PREFIXES.any { lower.contains(it) } -> ResponsePreference(
                domain = domain,
                ruleType = PreferenceRuleType.EXCLUDE,
                excludeFields = mentionedFields,
                sourceUtterance = utterance,
            )
            INCLUDE_ONLY_PREFIXES.any { lower.contains(it) } -> ResponsePreference(
                domain = domain,
                ruleType = PreferenceRuleType.INCLUDE_ONLY,
                includeFields = mentionedFields,
                sourceUtterance = utterance,
            )
            else -> ResponsePreference(
                domain = domain,
                ruleType = PreferenceRuleType.INCLUDE_ONLY,
                includeFields = mentionedFields,
                sourceUtterance = utterance,
            )
        }
    }

    /**
     * Builds a one-line human-readable confirmation of the stored preference.
     * e.g. "Got it. Weather will be condition and temperature."
     */
    fun buildConfirmation(pref: ResponsePreference): String {
        return when (pref.ruleType) {
            PreferenceRuleType.INCLUDE_ONLY -> {
                val fields = pref.includeFields.joinToString(" and ")
                "Got it. ${pref.domain.displayName} will be $fields."
            }
            PreferenceRuleType.EXCLUDE -> {
                val fields = pref.excludeFields.joinToString(" and ")
                "Got it. I'll leave out $fields for ${pref.domain.displayName}."
            }
            PreferenceRuleType.LENGTH -> {
                val label = pref.preferredLength.displayLabel.lowercase()
                "Got it. ${pref.domain.displayName} responses will be $label."
            }
            PreferenceRuleType.FORMAT -> "Got it. I'll follow that format for ${pref.domain.displayName}."
            PreferenceRuleType.ORDER -> "Got it. I'll reorder ${pref.domain.displayName} fields as you prefer."
            PreferenceRuleType.DETAIL_LEVEL -> "Got it. I'll adjust ${pref.domain.displayName} responses."
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun extractLength(lower: String): PreferredLength? = when {
        BRIEF_PATTERNS.any { lower.contains(it) }    -> PreferredLength.BRIEF
        DETAILED_PATTERNS.any { lower.contains(it) } -> PreferredLength.DETAILED
        else                                          -> null
    }
}
