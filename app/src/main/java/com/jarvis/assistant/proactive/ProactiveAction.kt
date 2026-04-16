package com.jarvis.assistant.proactive

/**
 * ProactiveAction — the output of [DecisionEngine]; describes exactly what
 * the proactive engine wants the dispatcher to do.
 *
 * Three variants:
 * - [SpeakAction]   — synthesise [text] via TTS (high-urgency, ACTIVE interrupt level).
 * - [PassiveAction] — surface a notification or ambient indicator without speech.
 * - [NoAction]      — nothing to do this tick; the dispatcher is not invoked.
 */
sealed class ProactiveAction {

    /**
     * Instruct the TTS dispatcher to speak [text] aloud.
     *
     * @param text        TTS-ready sentence to be passed to [TtsEngine.speak].
     * @param dedupeKey   The event's dedupeKey so [CooldownStore] can be updated
     *                    after successful dispatch.
     * @param sourceType  The [ProactiveEventType] that produced this action.
     */
    data class SpeakAction(
        val text: String,
        val dedupeKey: String,
        val sourceType: ProactiveEventType
    ) : ProactiveAction()

    /**
     * Instruct the passive dispatcher to surface a notification or in-app alert.
     *
     * @param title       Short notification title.
     * @param body        Optional longer notification body, or null for title-only.
     * @param dedupeKey   The event's dedupeKey so [CooldownStore] can be updated
     *                    after successful dispatch.
     * @param sourceType  The [ProactiveEventType] that produced this action.
     */
    data class PassiveAction(
        val title: String,
        val body: String?,
        val dedupeKey: String,
        val sourceType: ProactiveEventType
    ) : ProactiveAction()

    /**
     * No action is warranted this tick.
     *
     * This is not an error; it is the normal result when no event clears the
     * scoring threshold, the global gap is not satisfied, or all events are stale.
     */
    object NoAction : ProactiveAction()
}
