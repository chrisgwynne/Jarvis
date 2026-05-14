package com.jarvis.assistant.personality

/**
 * PersonalityPromptSelector — picks which [PersonalitySection]s belong
 * in the prompt for a given [InteractionType], assembles them into a
 * single block, and applies serious-mode + humour-level overrides.
 *
 * Pure / no Android dependency.  Read by:
 *   - [com.jarvis.assistant.prompt.PromptAssembler] when building the
 *     LLM chat system prompt
 *   - the proactive dispatcher for [InteractionType.PROACTIVE_REMINDER]
 *   - error / pushback / follow-up surfaces that want a consistent voice
 *
 * The section mapping mirrors the brief in
 * `assets/personality/`:
 *
 *  LOCAL_COMMAND_CONFIRMATION → command_confirmation_style + humour
 *  LLM_CHAT                   → identity + soul + speech_style + humour + pushback + boundaries
 *  PROACTIVE_REMINDER         → proactivity_style + humour + boundaries
 *  ERROR_MESSAGE              → error_style + boundaries
 *  FOLLOW_UP_QUESTION         → speech_style + pushback
 *  PUSHBACK                   → pushback + speech_style
 *  MEMORY_RESPONSE            → memory_rules + speech_style + boundaries
 *  DIAGNOSTIC_RESPONSE        → speech_style + boundaries
 *
 * When serious mode is active, humour-flavoured sections are dropped
 * (humour.md and proactivity_style.md's playful examples are not in
 * scope — we keep the *rule* sections so boundaries still apply).
 */
class PersonalityPromptSelector(
    private val context: PersonalityContext,
    private val settings: () -> PersonalitySettings = { PersonalitySettings.DEFAULT },
) {

    /**
     * Build the markdown block to inject for [interaction], adjusted
     * for [serious] mode + the user's humour level.
     *
     * Returns an empty string when personality is disabled or no
     * sections are loaded — callers MUST handle the empty case
     * without a leading separator.
     */
    fun promptFor(
        interaction: InteractionType,
        serious: Boolean = false,
    ): String {
        val s = settings()
        if (!s.enabled) return ""
        val picks = sectionsFor(interaction).filter { section ->
            // Strip humour-driving sections when serious mode is on
            // or the user disabled humour.
            !(serious && section == PersonalitySection.HUMOUR) &&
                !(s.sarcasm == SarcasmLevel.OFF && section == PersonalitySection.HUMOUR) &&
                !(serious && section == PersonalitySection.PUSHBACK &&
                    interaction != InteractionType.PUSHBACK)
        }
        val body = context.assemble(picks)
        if (body.isBlank()) return ""
        val header = if (serious) "Serious mode is ON — no jokes, no sarcasm, just help." else null
        return listOfNotNull(header, body).joinToString("\n\n")
    }

    /** Sections for [interaction] before any serious-mode filtering. */
    @androidx.annotation.VisibleForTesting
    internal fun sectionsFor(interaction: InteractionType): List<PersonalitySection> =
        when (interaction) {
            InteractionType.LOCAL_COMMAND_CONFIRMATION -> listOf(
                PersonalitySection.COMMAND_CONFIRMATION_STYLE,
                PersonalitySection.HUMOUR,
            )
            InteractionType.LLM_CHAT -> listOf(
                PersonalitySection.IDENTITY,
                PersonalitySection.SOUL,
                PersonalitySection.SPEECH_STYLE,
                PersonalitySection.HUMOUR,
                PersonalitySection.PUSHBACK,
                PersonalitySection.BOUNDARIES,
            )
            InteractionType.PROACTIVE_REMINDER -> listOf(
                PersonalitySection.PROACTIVITY_STYLE,
                PersonalitySection.HUMOUR,
                PersonalitySection.BOUNDARIES,
            )
            InteractionType.ERROR_MESSAGE -> listOf(
                PersonalitySection.ERROR_STYLE,
                PersonalitySection.BOUNDARIES,
            )
            InteractionType.FOLLOW_UP_QUESTION -> listOf(
                PersonalitySection.SPEECH_STYLE,
                PersonalitySection.PUSHBACK,
            )
            InteractionType.PUSHBACK -> listOf(
                PersonalitySection.PUSHBACK,
                PersonalitySection.SPEECH_STYLE,
            )
            InteractionType.MEMORY_RESPONSE -> listOf(
                PersonalitySection.MEMORY_RULES,
                PersonalitySection.SPEECH_STYLE,
                PersonalitySection.BOUNDARIES,
            )
            InteractionType.DIAGNOSTIC_RESPONSE -> listOf(
                PersonalitySection.SPEECH_STYLE,
                PersonalitySection.BOUNDARIES,
            )
        }
}
