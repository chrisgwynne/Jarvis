package com.jarvis.assistant.personality

/**
 * PersonalitySection — one of the 10 modular markdown files under
 * `assets/personality/` that describe Jarvis's character.
 *
 * Each section is loaded once at startup by [PersonalityProfileLoader],
 * cached in memory, and selected per-interaction by
 * [PersonalityPromptSelector].  Section names map 1:1 to filenames so
 * the file system *is* the source of truth — editing the markdown is
 * the supported way to tune Jarvis's voice, no recompile required.
 */
enum class PersonalitySection(val fileName: String) {
    IDENTITY                   ("identity.md"),
    SOUL                       ("soul.md"),
    SPEECH_STYLE               ("speech_style.md"),
    HUMOUR                     ("humour.md"),
    PUSHBACK                   ("pushback.md"),
    PROACTIVITY_STYLE          ("proactivity_style.md"),
    ERROR_STYLE                ("error_style.md"),
    COMMAND_CONFIRMATION_STYLE ("command_confirmation_style.md"),
    MEMORY_RULES               ("memory_rules.md"),
    BOUNDARIES                 ("boundaries.md");
}

/**
 * Why an interaction is happening — drives [PersonalityPromptSelector]'s
 * section choice.  Each value maps to a documented section set in
 * `docs/personality/sections.md` (or inline KDoc on the selector).
 */
enum class InteractionType {
    LOCAL_COMMAND_CONFIRMATION,
    LLM_CHAT,
    PROACTIVE_REMINDER,
    ERROR_MESSAGE,
    FOLLOW_UP_QUESTION,
    PUSHBACK,
    MEMORY_RESPONSE,
    DIAGNOSTIC_RESPONSE,
}
