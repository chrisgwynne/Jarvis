package com.jarvis.assistant.ambient

/**
 * User-facing controls for the Ambient Intelligence subsystem.
 *
 * Defaults are conservative: everything on, but minimum confidence to speak
 * is high so uncertain patterns notify rather than interrupt.
 */
data class AmbientSettings(
    /** Master switch for the entire ambient subsystem. */
    val enabled: Boolean = true,

    /** Allow the system to build and refine [RoutinePattern]s from history. */
    val learningEnabled: Boolean = true,

    /** Emit nudges based on device location bucket (near shop, arrived home, etc.). */
    val locationSuggestionsEnabled: Boolean = true,

    /** Emit nudges when a key app is opened with relevant context. */
    val appContextSuggestionsEnabled: Boolean = true,

    /** Emit alerts when HA devices are running while nobody is home. */
    val homeAssistantAlertsEnabled: Boolean = true,

    /** Emit travel suggestions when car BT connects or event is approaching. */
    val travelSuggestionsEnabled: Boolean = true,

    /** Emit nudges for customer or work messages when relevant. */
    val customerWorkNudgesEnabled: Boolean = true,

    /** Automatically update confidence/cooldown from dismissals. */
    val learnFromDismissalsEnabled: Boolean = true,

    /**
     * Minimum confidence score [0, 1] required before a pattern graduates
     * from notification to spoken.  Patterns below this speak as passive
     * notifications instead.
     */
    val minConfidenceToSpeak: Float = 0.65f,

    /** Maximum number of ambient nudges the system may emit per day. */
    val maxNudgesPerDay: Int = 10,
)
