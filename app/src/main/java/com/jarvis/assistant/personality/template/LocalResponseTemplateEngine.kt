package com.jarvis.assistant.personality.template

import com.jarvis.assistant.personality.JokeFrequency
import com.jarvis.assistant.personality.PersonalitySettings
import com.jarvis.assistant.personality.SarcasmLevel

/**
 * LocalResponseTemplateEngine — deterministic, **non-LLM** spoken
 * confirmation generator for local commands.
 *
 * Pure data + a small selector.  Inputs:
 *   - the tool category ("volume", "flashlight", "todoist", …)
 *   - the [PersonalitySettings] snapshot
 *   - whether [com.jarvis.assistant.personality.SeriousModeDetector] is active
 *   - the [RecentPhraseStore] for repetition control
 *   - an RNG seed (defaults to System.nanoTime — tests pin it)
 *
 * Output: a single short phrase to speak.  Tools opt in to this
 * surface by calling `engine.choose("flashlight_on", settings, serious)`
 * instead of hard-coding a string.  Existing tools that hard-code
 * strings continue to work unchanged.
 *
 * Why deterministic + local:
 *   - No round-trip latency.
 *   - No LLM cost.
 *   - Acceptance criterion: "personality system never calls LLM for
 *     local commands."
 *
 * Templates live in [TEMPLATES] below; adding a category is a
 * 5-line change.  Each category has a plain list (boring,
 * always-allowed) and an optional witty list (gated by sarcasm level
 * + joke frequency + serious mode + recent-phrase dedupe).
 */
class LocalResponseTemplateEngine(
    private val recent: RecentPhraseStore = RecentPhraseStore(),
    private val rng: () -> Double = { Math.random() },
) {

    /**
     * Pick a confirmation for [category].
     *
     * @param category   Stable key (e.g. "flashlight_on", "volume_down",
     *                   "todoist_added", "calendar_added", "photo_taken").
     * @param settings   Live personality settings.  When
     *                   [PersonalitySettings.applyToLocalConfirmations] is
     *                   false, only the plain list is consulted.
     * @param serious    When true, plain list only; humour skipped.
     * @return A short spoken confirmation.  Never blank.
     */
    fun choose(
        category: String,
        settings: PersonalitySettings = PersonalitySettings.DEFAULT,
        serious: Boolean = false,
    ): String {
        val entry = TEMPLATES[category]
            ?: return DEFAULT_PLAIN.random(rng)
        val plain = entry.plain
        val witty = entry.witty

        val wittyAllowed = settings.enabled &&
            settings.applyToLocalConfirmations &&
            !serious &&
            settings.sarcasm != SarcasmLevel.OFF &&
            witty.isNotEmpty()

        val sarcasmBias = when (settings.sarcasm) {
            SarcasmLevel.OFF    -> 0.0f
            SarcasmLevel.LOW    -> 0.6f
            SarcasmLevel.MEDIUM -> 1.0f
            SarcasmLevel.HIGH   -> 1.4f
        }
        val freqProbability = settings.jokeFrequency.probability * sarcasmBias

        // Throttle: if we've used 3+ witty replies for this category
        // in the last window, force plain to avoid annoyance.
        val recentWittyCount = recent.recentCount("witty:$category")
        val wittyForced = wittyAllowed && rng() < freqProbability && recentWittyCount < 3

        val pool = if (wittyForced) witty else plain
        var pick = pool.firstNotRecent(category)
        if (pick == null) pick = pool.randomNonRecent(category) ?: pool.random(rng)
        recent.record(category, pick)
        if (wittyForced) recent.record("witty:$category", pick)
        return pick
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun List<String>.random(rng: () -> Double): String =
        if (isEmpty()) "" else this[(rng() * size).toInt().coerceAtMost(size - 1)]

    private fun List<String>.firstNotRecent(category: String): String? =
        firstOrNull { !recent.wasRecent(category, it) }

    private fun List<String>.randomNonRecent(category: String): String? {
        val candidates = filter { !recent.wasRecent(category, it) }
        return candidates.takeIf { it.isNotEmpty() }?.random(rng)
    }

    companion object {
        /** Generic fallback when the category isn't in [TEMPLATES]. */
        private val DEFAULT_PLAIN = listOf("Done.", "Sorted.", "Handled.")

        data class Entry(val plain: List<String>, val witty: List<String>)

        /**
         * Curated templates from `assets/personality/command_confirmation_style.md`.
         * Keep the lists short — variety without overload.
         */
        @androidx.annotation.VisibleForTesting
        internal val TEMPLATES: Map<String, Entry> = mapOf(
            // ── Volume / sound ────────────────────────────────────────────
            "volume_down" to Entry(
                plain = listOf("Volume down.", "Done.", "Quieter."),
                witty = listOf("Quieter. Civilised.", "Volume down. The neighbours may recover."),
            ),
            "volume_up" to Entry(
                plain = listOf("Volume up.", "Done.", "Louder."),
                witty = listOf("Louder. Sorry, neighbours.", "Cranked."),
            ),
            "mute" to Entry(
                plain = listOf("Muted.", "Silent now."),
                witty = listOf("Muted. Bliss.", "Silenced."),
            ),

            // ── Flashlight ────────────────────────────────────────────────
            "flashlight_on" to Entry(
                plain = listOf("Torch on.", "Light on."),
                witty = listOf("Let there be light. Briefly.", "Torch on. Try not to blind anyone."),
            ),
            "flashlight_off" to Entry(
                plain = listOf("Torch off.", "Light off."),
                witty = listOf("Torch off. Welcome back to the cave.", "Off."),
            ),

            // ── Messaging ─────────────────────────────────────────────────
            "message_sent" to Entry(
                plain = listOf("Sent.", "Message sent.", "Done."),
                witty = listOf("Sent. Social obligation complete.", "Sent. Try not to overthink the reply."),
            ),

            // ── Todoist / reminders ───────────────────────────────────────
            "todoist_added" to Entry(
                plain = listOf("Added.", "Saved to Todoist.", "Reminder set."),
                witty = listOf("Future you has been warned.", "Added. Future you owes me one."),
            ),
            "todoist_completed" to Entry(
                plain = listOf("Marked done.", "Closed."),
                witty = listOf("Crossed off. Briefly heroic.", "Done. Reward yourself accordingly."),
            ),

            // ── Calendar ──────────────────────────────────────────────────
            "calendar_added" to Entry(
                plain = listOf("Added to calendar.", "Calendar updated."),
                witty = listOf("Calendar updated. The illusion of control restored."),
            ),
            "calendar_moved" to Entry(
                plain = listOf("Moved.", "Rescheduled."),
                witty = listOf("Moved. Procrastination respected."),
            ),

            // ── Maps ──────────────────────────────────────────────────────
            "maps_opening" to Entry(
                plain = listOf("Opening Maps.", "Navigation started."),
                witty = listOf("Maps it is. Try to trust the route."),
            ),

            // ── Camera ────────────────────────────────────────────────────
            "photo_taken" to Entry(
                plain = listOf("Taken.", "Photo saved."),
                witty = listOf("Selfie taken. Brave.", "Photo saved. Posterity has been served."),
            ),

            // ── Timers / alarms ───────────────────────────────────────────
            "timer_set" to Entry(
                plain = listOf("Timer set.", "Running."),
                witty = listOf("Timer set. Try not to stare at it."),
            ),
            "alarm_set" to Entry(
                plain = listOf("Alarm set.", "Done."),
                witty = listOf("Alarm set. Future you is already annoyed."),
            ),
        )
    }
}
