package com.jarvis.assistant.proactive

/**
 * ProactiveEvent — an enriched signal produced by [EventGenerator] and
 * consumed by [EventScorer] and [DecisionEngine].
 *
 * All numeric fields are normalised to [0, 1] unless otherwise noted.
 *
 * @param type             The category of this event (battery, reminder, etc.).
 * @param title            Short human-readable label for logging / notifications.
 * @param spokenText       Optional TTS-ready sentence; null means speech is not
 *                         appropriate for this event (e.g. info-only alerts).
 * @param urgency          How time-sensitive the event is.  1.0 = act immediately.
 * @param relevance        How likely the user wants to know about this right now.
 * @param confidence       How confident the generator is that the underlying data
 *                         is accurate.
 * @param annoyanceCost    Expected user annoyance if surfaced at a random moment.
 *                         High values (e.g. 0.9) suppresses frequent interruptions.
 * @param createdAtMillis  Wall-clock timestamp of when this event was generated.
 *                         Used by [DecisionEngine] to discard stale events.
 * @param dedupeKey        Stable key that identifies this "bucket" of events so
 *                         the same underlying situation is not surfaced twice.
 *                         Should encode enough granularity that distinct
 *                         situations get distinct keys (e.g. per-5%-battery-bucket,
 *                         per-minute-reminder, per-second-missed-call).
 * @param metadata         Arbitrary string extras carried through to the dispatcher
 *                         (contact name, reminder label, etc.).
 */
data class ProactiveEvent(
    val type: ProactiveEventType,
    val title: String,
    val spokenText: String?,
    val urgency: Float,
    val relevance: Float,
    val confidence: Float,
    val annoyanceCost: Float,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val dedupeKey: String,
    val metadata: Map<String, String> = emptyMap()
)
