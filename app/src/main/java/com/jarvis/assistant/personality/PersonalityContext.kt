package com.jarvis.assistant.personality

/**
 * PersonalityContext — the loaded markdown content of every
 * [PersonalitySection], indexed by enum.  Built by
 * [PersonalityProfileLoader] and read by
 * [PersonalityPromptSelector] when assembling prompts.
 *
 * Missing sections (file deleted, asset stripped) are surfaced as
 * empty strings rather than null so call sites stay simple — the
 * selector concatenates `text(section)` and a missing section
 * contributes nothing.
 *
 * Note: this is a value class on a Map, not a data class with 10
 * fields, so adding a new [PersonalitySection] doesn't require
 * changing this file or every test that instantiates one.
 */
class PersonalityContext(
    private val sections: Map<PersonalitySection, String>,
) {
    /** True iff [section] was loaded with non-blank content. */
    fun has(section: PersonalitySection): Boolean =
        !sections[section].isNullOrBlank()

    /** Markdown body for [section], or "" when absent.  Never null. */
    fun text(section: PersonalitySection): String =
        sections[section]?.trim().orEmpty()

    /** Convenience: concatenate the chosen sections with blank-line separators. */
    fun assemble(picks: List<PersonalitySection>): String =
        picks.asSequence()
            .map { text(it) }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

    /** Debug: which sections were successfully loaded. */
    val loadedSections: Set<PersonalitySection>
        get() = sections.keys.filter { !sections[it].isNullOrBlank() }.toSet()

    companion object {
        /** Empty context — used as the fail-safe fallback when assets are missing. */
        val EMPTY = PersonalityContext(emptyMap())
    }
}
