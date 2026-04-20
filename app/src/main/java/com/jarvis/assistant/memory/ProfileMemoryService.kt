package com.jarvis.assistant.memory

import com.jarvis.assistant.memory.db.dao.MemoryFactDao
import com.jarvis.assistant.memory.db.entity.FactCategory
import com.jarvis.assistant.memory.db.entity.MemoryFact

/**
 * ProfileMemoryService — typed read/write access to structured user facts.
 *
 * Sits above [MemoryFactDao] and provides named convenience methods so the
 * rest of the runtime doesn't need to know about key naming conventions.
 */
class ProfileMemoryService(private val dao: MemoryFactDao) {

    // ── Typed getters ─────────────────────────────────────────────────────────

    suspend fun getUserName(): String? = dao.getByKey("user.name")?.value

    suspend fun getUserLocation(): String? = dao.getByKey("user.location")?.value

    // ── Generic fact storage ──────────────────────────────────────────────────

    suspend fun setFact(key: String, value: String, category: FactCategory) {
        dao.upsert(MemoryFact(factKey = key, value = value, category = category))
    }

    suspend fun getFact(key: String): String? = dao.getByKey(key)?.value

    suspend fun getAllFacts(): List<MemoryFact> = dao.getAll()

    suspend fun getFactsByCategory(category: FactCategory): List<MemoryFact> =
        dao.getByCategory(category)

    suspend fun forgetFact(key: String) = dao.deleteByKey(key)

    // ── Dislikes ──────────────────────────────────────────────────────────────
    //
    // "Don't tell me about X" / "stop suggesting X" is recorded as a PREFERENCE
    // fact with a `dislike.<topic>` key so it survives restart and is visible
    // next to the user's other stated preferences. The proactive layer reads
    // these via [MemoryPolicy] to decide whether to suppress suggestions.

    /**
     * Persist a new dislike for [topic]. Overwrites any previous entry for
     * the same topic so repeated mutes don't accumulate duplicate rows.
     */
    suspend fun addDislike(topic: String) {
        val normalized = topic.lowercase().trim()
        if (normalized.isBlank()) return
        setFact(
            key = "dislike.$normalized",
            value = normalized,
            category = FactCategory.PREFERENCE,
        )
    }

    /** Remove a previously-stored dislike; no-op if it isn't set. */
    suspend fun removeDislike(topic: String) {
        val normalized = topic.lowercase().trim()
        if (normalized.isBlank()) return
        dao.deleteByKey("dislike.$normalized")
    }

    /**
     * Every dislike topic the user has asked to mute. Returned as lowercase
     * topic strings without the `dislike.` prefix so callers can pattern-match
     * directly. Empty when the user has made no mute requests.
     */
    suspend fun dislikes(): List<String> =
        dao.getByCategory(FactCategory.PREFERENCE)
            .asSequence()
            .filter { it.factKey.startsWith("dislike.") }
            .map { it.factKey.removePrefix("dislike.") }
            .toList()

    // ── Prompt injection ──────────────────────────────────────────────────────

    /**
     * Returns a compact block to be silently prepended to the system prompt.
     * Empty string when no facts are known.
     */
    suspend fun toPromptFragment(): String {
        val facts = dao.getAll()
        if (facts.isEmpty()) return ""
        return buildString {
            append("[User profile]\n")
            facts.forEach { f ->
                when {
                    // Structured named keys — show as "label: value"
                    f.factKey == "user.name"     -> append("• name: ${f.value}\n")
                    f.factKey == "user.location" -> append("• location: ${f.value}\n")
                    f.factKey == "user.age"      -> append("• age: ${f.value}\n")
                    f.factKey == "user.job" ||
                    f.factKey == "user.occupation" ||
                    f.factKey == "user.profession" -> append("• job: ${f.value}\n")
                    f.factKey == "user.birthday"   -> append("• birthday: ${f.value}\n")
                    f.factKey == "user.hometown"   -> append("• hometown: ${f.value}\n")
                    f.factKey == "user.nationality"-> append("• nationality: ${f.value}\n")
                    f.factKey.startsWith("user.")  ->
                        append("• ${f.factKey.removePrefix("user.").replace('.', ' ')}: ${f.value}\n")

                    // Free-form facts — value is already a full statement, display as-is
                    f.factKey.startsWith("fact.")  -> append("• ${f.value}\n")

                    // Explicit preferences
                    f.factKey.startsWith("pref.")  -> append("• prefers: ${f.value}\n")

                    else -> append("• ${f.value}\n")
                }
            }
        }
    }
}
