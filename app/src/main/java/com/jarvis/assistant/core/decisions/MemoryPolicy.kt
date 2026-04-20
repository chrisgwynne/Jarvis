package com.jarvis.assistant.core.decisions

import com.jarvis.assistant.memory.ProfileMemoryService

/**
 * MemoryPolicy — bridges stored user preferences into the decision layer.
 *
 * Memory has always shaped prompts (see [ProfileMemoryService.toPromptFragment]).
 * This class lets memory shape *decisions*: when the user has asked Jarvis
 * not to proactively surface something ("stop telling me about the weather"),
 * that dislike is persisted as a `dislike.<topic>` PREFERENCE fact and then
 * reflected in [com.jarvis.assistant.core.decisions.ActionLedger] so
 * [com.jarvis.assistant.proactive.EventScorer] hard-suppresses the matching
 * action class without any changes to the scoring pipeline.
 *
 * Read as: the ledger already knew how to mute an action class; this class
 * teaches it which classes the user has asked about by name.
 */
class MemoryPolicy(
    private val profileMemory: ProfileMemoryService,
    private val ledger: ActionLedger,
) {

    /**
     * Apply a single dislike. The [topic] is mapped to a canonical action
     * class (e.g. "notifications" → "NOTIFICATION") and the ledger's
     * per-class suppression is toggled on. If no mapping exists the dislike
     * is still persisted so the user sees it in their profile, but no
     * proactive class is muted.
     *
     * Returns the action class that was suppressed, or null if the topic
     * didn't map to a known class.
     */
    suspend fun registerDislike(topic: String): String? {
        profileMemory.addDislike(topic)
        val actionClass = topicToActionClass(topic) ?: return null
        ledger.suppressClass(actionClass)
        return actionClass
    }

    /** Inverse of [registerDislike]. */
    suspend fun lift(topic: String): String? {
        profileMemory.removeDislike(topic)
        val actionClass = topicToActionClass(topic) ?: return null
        ledger.unsuppressClass(actionClass)
        return actionClass
    }

    /**
     * Re-apply every persisted dislike to the ledger. Called once on startup
     * so in-memory suppression matches what the user has previously asked
     * for across process restarts.
     */
    suspend fun hydrateSuppressionsFromMemory() {
        val topics = profileMemory.dislikes()
        for (topic in topics) {
            val cls = topicToActionClass(topic) ?: continue
            ledger.suppressClass(cls)
        }
    }

    /**
     * Does the current utterance already cover a topic the user has muted?
     * Callers can use this to avoid asking "anything else?" about a topic
     * the user deliberately turned off. Comparison is simple substring match
     * against lowercased topic strings; false when the user has no dislikes.
     */
    suspend fun isMutedTopicMentioned(utterance: String): Boolean {
        val lower = utterance.lowercase()
        val topics = profileMemory.dislikes()
        return topics.any { topic -> lower.contains(topic) }
    }

    companion object {
        /**
         * Map a user-spoken topic to the canonical [ActionLedger] class key.
         * Intentionally small and additive — new entries can be appended as
         * new proactive event types are introduced. Unknown topics return
         * null (the dislike is still persisted, just not enforced in the
         * scorer).
         */
        fun topicToActionClass(topic: String): String? {
            val t = topic.lowercase().trim()
            return when {
                t.contains("battery")       -> "BATTERY"
                t.contains("reminder")      -> "REMINDER"
                t.contains("call")          -> "CALL"
                t.contains("notification")  -> "NOTIFICATION"
                t.contains("meeting")       -> "CALENDAR"
                t.contains("calendar")      -> "CALENDAR"
                t.contains("agenda")        -> "CALENDAR"
                t.contains("location")      -> "LOCATION"
                t.contains("home")          -> "LOCATION"
                t.contains("behaviour")     -> "BRAIN"
                t.contains("behavior")      -> "BRAIN"
                t.contains("suggestion")    -> "BRAIN"
                else                        -> null
            }
        }
    }
}
