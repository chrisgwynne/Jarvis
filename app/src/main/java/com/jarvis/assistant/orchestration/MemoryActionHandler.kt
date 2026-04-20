package com.jarvis.assistant.orchestration

import android.util.Log
import com.jarvis.assistant.core.decisions.MemoryPolicy
import com.jarvis.assistant.memory.ProfileMemoryService
import com.jarvis.assistant.memory.db.entity.FactCategory

/**
 * MemoryActionHandler — executes [ConversationAction.RememberFact] and
 * [ConversationAction.RecallFact] actions and returns a spoken response.
 *
 * [memoryPolicy] is optional: when provided, [MuteSuggestion] / [UnmuteSuggestion]
 * actions are routed through it so both memory *and* the action ledger stay in
 * sync. When null those actions degrade to memory-only writes.
 */
class MemoryActionHandler(
    private val profileMemory: ProfileMemoryService,
    private val memoryPolicy: MemoryPolicy? = null,
) {

    companion object {
        private const val TAG = "MemoryActionHandler"
    }

    suspend fun handleStore(action: ConversationAction.RememberFact): String {
        // Owner-name lock: once a name is stored it is immutable via casual speech.
        // An explicit "set my name to X" or "call me X" can still set it the FIRST
        // time, but will not overwrite an already-locked name.
        // Changing the name requires a deliberate action in Settings.
        if (action.key == "user.name") {
            val existing = profileMemory.getUserName()
            if (existing != null) {
                Log.d(TAG, "Owner name already locked as '$existing' — ignoring overwrite to '${action.value}'")
                return if (existing.equals(action.value, ignoreCase = true)) {
                    "Yes, I know — you're ${existing}."
                } else {
                    "Your name is already saved as $existing. To change it, use the Settings screen."
                }
            }
        }

        val category = when {
            action.key == "user.name"          -> FactCategory.NAME
            action.key.startsWith("pref.")     -> FactCategory.PREFERENCE
            else                               -> FactCategory.FACT
        }
        profileMemory.setFact(action.key, action.value, category)
        Log.d(TAG, "Stored: ${action.key} = ${action.value}")

        return when (action.key) {
            "user.name"       -> "Nice to meet you, ${action.value}."
            "user.location"   -> "Got it, ${action.value}."
            "user.age"        -> "Got it."
            "user.job",
            "user.occupation",
            "user.profession" -> "Noted."
            else              -> "Noted."
        }
    }

    /**
     * Handle "don't tell me about X" — persist the dislike and (when a policy
     * is wired) hard-suppress the matching proactive class.
     */
    suspend fun handleMute(action: ConversationAction.MuteSuggestion): String {
        val topic = action.topic.trim().lowercase()
        if (topic.isBlank()) return "I'll leave that alone."
        val policy = memoryPolicy
        val suppressedClass = if (policy != null) {
            policy.registerDislike(topic)
        } else {
            profileMemory.addDislike(topic)
            null
        }
        Log.d(TAG, "Mute: topic=$topic actionClass=$suppressedClass")
        return if (suppressedClass != null)
            "Got it — no more $topic."
        else
            "Noted — I'll skip $topic."
    }

    /** Inverse of [handleMute]. */
    suspend fun handleUnmute(action: ConversationAction.UnmuteSuggestion): String {
        val topic = action.topic.trim().lowercase()
        if (topic.isBlank()) return "Sure."
        memoryPolicy?.lift(topic) ?: profileMemory.removeDislike(topic)
        Log.d(TAG, "Unmute: topic=$topic")
        return "Okay — $topic is back on."
    }

    suspend fun handleRecall(action: ConversationAction.RecallFact): String {
        val facts = profileMemory.getAllFacts()
        if (facts.isEmpty()) return "I don't have any personal details saved yet."

        return buildString {
            // Named structured facts first
            facts.filter { it.factKey.startsWith("user.") }.forEach { f ->
                val label = f.factKey.removePrefix("user.").replace('.', ' ')
                append("Your $label is ${f.value}. ")
            }
            // Free-form facts
            val freeFacts = facts.filter { it.factKey.startsWith("fact.") }
            if (freeFacts.isNotEmpty()) {
                append("I also know: ")
                append(freeFacts.take(5).joinToString("; ") { it.value })
                append(".")
            }
            // Preferences
            val prefs = facts.filter { it.factKey.startsWith("pref.") }
            if (prefs.isNotEmpty()) {
                append(" You prefer: ")
                append(prefs.take(3).joinToString("; ") { it.value })
                append(".")
            }
        }.trim().ifBlank { "I have a few things saved but nothing I can summarise right now." }
    }
}
