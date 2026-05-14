package com.jarvis.assistant.personality

/** How sarcastic Jarvis is allowed to be.  OFF = no humour at all. */
enum class SarcasmLevel(val displayLabel: String) {
    OFF   ("Off"),
    LOW   ("Low"),
    MEDIUM("Medium"),
    HIGH  ("High"),
}

/** How often Jarvis adds a one-liner to a confirmation. */
enum class JokeFrequency(val displayLabel: String, val probability: Float) {
    RARE     ("Rare",      0.10f),
    SOMETIMES("Sometimes", 0.30f),
    OFTEN    ("Often",     0.55f),
}

/**
 * PersonalitySettings — user-visible policy that gates the personality
 * system.  Read by [PersonalityPromptSelector] +
 * [com.jarvis.assistant.personality.template.LocalResponseTemplateEngine].
 *
 * Persisted via [com.jarvis.assistant.util.SettingsStore]; the
 * Compose Settings UI reads + writes through
 * [PersonalitySettingsRepository] for live updates.
 */
data class PersonalitySettings(
    val enabled: Boolean,
    val sarcasm: SarcasmLevel,
    val jokeFrequency: JokeFrequency,
    val pushbackEnabled: Boolean,
    val friendlyRoastingEnabled: Boolean,
    val seriousModeAutoDetectEnabled: Boolean,
    val applyToProactiveReminders: Boolean,
    val applyToLocalConfirmations: Boolean,
    val applyToLlmAnswers: Boolean,
) {
    companion object {
        val DEFAULT = PersonalitySettings(
            enabled                      = true,
            sarcasm                      = SarcasmLevel.MEDIUM,
            jokeFrequency                = JokeFrequency.SOMETIMES,
            pushbackEnabled              = true,
            friendlyRoastingEnabled      = true,
            seriousModeAutoDetectEnabled = true,
            applyToProactiveReminders    = true,
            applyToLocalConfirmations    = true,
            applyToLlmAnswers            = true,
        )
    }
}
