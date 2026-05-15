package com.jarvis.assistant.preferences

/**
 * Detects whether a user utterance is expressing a formatting preference
 * or requesting a one-off override to the full response format.
 *
 * Preference phrases are stored; override phrases apply only to the
 * current response and are not persisted.
 */
object PreferenceDetector {

    private val PREFERENCE_PATTERNS = listOf(
        Regex("""^i (?:just )?prefer\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:next time|from now on|in (?:the )?future)[,\s]""", RegexOption.IGNORE_CASE),
        Regex("""^(?:please )?(?:just |only )?(?:tell|give|show) me\b""", RegexOption.IGNORE_CASE),
        Regex("""^don'?t (?:tell|give|show|include|mention|read out)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:stop|quit) (?:telling|giving|showing|including|mentioning)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:always |never )?(?:keep it|make it|be)\b.{0,30}(?:brief|short|concise|quick|simple)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:i )?(?:always|never) want\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:i want|i need|i'd (?:like|prefer))\b.{0,30}(?:just|only|brief|short|less|more|no)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:please )?(?:skip|leave out|omit|remove|drop)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:can you )?(?:just )?(?:stick to|focus on|only (?:mention|say|include))\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:whenever|every time|each time)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:i )?(?:don'?t (?:care about|need|want))\b""", RegexOption.IGNORE_CASE),
        Regex("""^i only (?:want|need)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:i )?(?:hate|dislike) (?:it )?when\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:please )?(?:keep|make)\b.{0,20}(?:brief|short|simple|concise)\b""", RegexOption.IGNORE_CASE),
        Regex("""^set (?:the )?(?:weather|calendar|response|format)\b""", RegexOption.IGNORE_CASE),
    )

    private val OVERRIDE_PATTERNS = listOf(
        Regex("""^(?:actually[,\s])?(?:give|show|tell) me (?:the )?(?:full|complete|detailed|everything|all)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:full|complete|detailed|everything)\b.{0,20}(?:this time|now|please)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:this time[,\s])""", RegexOption.IGNORE_CASE),
        Regex("""^(?:just this once[,\s])""", RegexOption.IGNORE_CASE),
        Regex("""^(?:for (?:this|now)[,\s])""", RegexOption.IGNORE_CASE),
        Regex("""^(?:can you )?give me (?:the )?(?:full|complete|detailed) (?:report|details|breakdown|rundown)\b""", RegexOption.IGNORE_CASE),
        Regex("""^(?:expand|elaborate|more detail|tell me (?:more|everything))\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * Returns true if the utterance is expressing a persistent preference.
     * Does not fire for override phrases.
     */
    fun isPreference(utterance: String): Boolean {
        val lower = utterance.trim()
        if (isOverride(lower)) return false
        return PREFERENCE_PATTERNS.any { it.containsMatchIn(lower) }
    }

    /**
     * Returns true if the utterance is a one-off override (e.g. "give me
     * the full report this time"), which should NOT be stored as a preference.
     */
    fun isOverride(utterance: String): Boolean {
        val lower = utterance.trim()
        return OVERRIDE_PATTERNS.any { it.containsMatchIn(lower) }
    }
}
