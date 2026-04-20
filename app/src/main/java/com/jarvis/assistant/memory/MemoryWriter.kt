package com.jarvis.assistant.memory

import android.util.Log
import com.jarvis.assistant.memory.db.dao.ConversationDao
import com.jarvis.assistant.memory.db.dao.MemoryDao
import com.jarvis.assistant.memory.db.entity.ConversationSession
import com.jarvis.assistant.memory.db.entity.ConversationTurn
import com.jarvis.assistant.memory.db.entity.MemoryEntry
import com.jarvis.assistant.memory.db.entity.MemoryType
import java.util.UUID

/**
 * MemoryWriter — persists conversation turns and synthesises memories.
 *
 * WRITE POLICY:
 *   - Every user/assistant turn is written to conversation_turns.
 *   - On session close (silence or stop command), MemorySummarizer is called
 *     to write a compact SUMMARY entry — this is what retrieval uses.
 *   - Explicit preference statements are written as PREFERENCE entries immediately.
 *   - Unresolved task mentions are written as TASK entries.
 *
 * PRUNE POLICY:
 *   - Episodic + Summary entries older than 90 days are pruned.
 *   - PREFERENCE / FACTUAL / ROUTINE entries are kept indefinitely.
 *   - Raw conversation_turns older than 30 days are pruned to save space.
 */
class MemoryWriter(
    private val memoryDao: MemoryDao,
    private val conversationDao: ConversationDao,
    private val embeddingEngine: MemoryEmbeddingEngine? = null
) {

    companion object {
        private const val TAG = "MemoryWriter"
        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "was", "did", "i", "me", "my", "you", "it",
            "that", "this", "to", "of", "and", "or", "in", "on", "at", "for",
            "with", "about", "by", "from", "do", "does", "can", "could", "would"
        )

        // Patterns that signal a preference the user stated explicitly.
        // "call me X" is intentionally excluded here — it is an identity statement
        // handled by IntentClassifier → MemoryActionHandler, not a preference.
        // Keeping it here caused false positives: "call me back", "call me at 5".
        private val PREFERENCE_PATTERNS = listOf(
            Regex("""^i (?:prefer|always prefer|usually prefer|hate|dislike|love|enjoy|avoid)\s+.{3,}""", RegexOption.IGNORE_CASE),
            Regex("""^i (?:always|usually|never|typically|often|rarely) (?!am |was |will |have |had )\S""", RegexOption.IGNORE_CASE)
        )

        // Patterns that signal an unresolved task
        private val TASK_PATTERNS = listOf(
            Regex("""(?:remind me to|don't forget to|i need to|i have to|i must)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:i'll|i will|let me)\s+(.+?)\s+later""", RegexOption.IGNORE_CASE)
        )
    }

    // ── Session management ────────────────────────────────────────────────────

    fun newSessionId(): String = UUID.randomUUID().toString()

    suspend fun openSession(sessionId: String) {
        conversationDao.insertSession(
            ConversationSession(id = sessionId, startedAt = System.currentTimeMillis())
        )
        Log.d(TAG, "Session opened: $sessionId")
    }

    /**
     * Persist a conversation turn and inline-detect implicit memories.
     * Returns true if an implicit preference or task was detected and stored —
     * the caller can use this to give the user a brief "Noted." confirmation.
     */
    suspend fun writeTurn(sessionId: String, role: String, content: String): Boolean {
        conversationDao.insertTurn(
            ConversationTurn(sessionId = sessionId, role = role, content = content)
        )
        // Inspect user utterances for preferences/tasks inline
        if (role == "user") {
            val prefStored = detectAndWritePreference(content, sessionId)
            val taskStored = detectAndWriteTask(content, sessionId)
            return prefStored || taskStored
        }
        return false
    }

    suspend fun closeSession(sessionId: String, summary: String?) {
        val turns = conversationDao.getTurnsForSession(sessionId)
        val session = conversationDao.getSession(sessionId) ?: return

        conversationDao.updateSession(
            session.copy(
                endedAt    = System.currentTimeMillis(),
                turnCount  = turns.size,
                summary    = summary
            )
        )

        // If a summary was provided (by MemorySummarizer), store it as a memory entry too
        if (!summary.isNullOrBlank() && turns.size >= 2) {
            val keywords = buildKeywords(turns.map { it.content }.joinToString(" "))
            memoryDao.insert(MemoryEntry(
                type            = MemoryType.SUMMARY,
                content         = summary,
                keywords        = keywords,
                sessionId       = sessionId,
                importanceScore = 0.7f,
                embedding       = embedBytes(summary)
            ))
        }

        Log.d(TAG, "Session closed: $sessionId (${turns.size} turns, summary=${summary != null})")
    }

    // ── Direct memory writes ──────────────────────────────────────────────────

    suspend fun writePreference(content: String, sessionId: String? = null) {
        val normalized = content.trim()
        if (memoryDao.countByContentAndType(normalized, MemoryType.PREFERENCE) > 0) {
            Log.d(TAG, "Preference already stored, skipping: $normalized")
            return
        }
        memoryDao.insert(MemoryEntry(
            type            = MemoryType.PREFERENCE,
            content         = normalized,
            keywords        = buildKeywords(normalized),
            sessionId       = sessionId,
            importanceScore = 0.9f,
            embedding       = embedBytes(normalized)
        ))
        Log.d(TAG, "Preference written: $normalized")
    }

    suspend fun writeTask(content: String, sessionId: String? = null) {
        val normalized = content.trim()
        if (memoryDao.countByContentAndType(normalized, MemoryType.TASK) > 0) {
            Log.d(TAG, "Task already stored, skipping: $normalized")
            return
        }
        memoryDao.insert(MemoryEntry(
            type            = MemoryType.TASK,
            content         = normalized,
            keywords        = buildKeywords(normalized),
            sessionId       = sessionId,
            importanceScore = 0.85f,
            embedding       = embedBytes(normalized)
        ))
        Log.d(TAG, "Task written: $normalized")
    }

    suspend fun writeFact(content: String, sessionId: String? = null) {
        val normalized = content.trim()
        if (memoryDao.countByContentAndType(normalized, MemoryType.FACTUAL) > 0) {
            Log.d(TAG, "Fact already stored, skipping: $normalized")
            return
        }
        memoryDao.insert(MemoryEntry(
            type            = MemoryType.FACTUAL,
            content         = normalized,
            keywords        = buildKeywords(normalized),
            sessionId       = sessionId,
            importanceScore = 0.8f,
            embedding       = embedBytes(normalized)
        ))
    }

    // ── Maintenance ───────────────────────────────────────────────────────────

    suspend fun prune() {
        // Retention: keep raw turns + session metadata for a year, semantic
        // memories for two years. MemoryRetriever already weights by recency
        // so old turns don't dominate; the extra window is pure optionality.
        val oneYearAgo = System.currentTimeMillis() - 365L * 24 * 3600 * 1000
        val twoYearsAgo = System.currentTimeMillis() - 730L * 24 * 3600 * 1000

        conversationDao.pruneTurnsOlderThan(oneYearAgo)
        conversationDao.pruneSessionsOlderThan(oneYearAgo)
        memoryDao.pruneOlderThan(twoYearsAgo)

        Log.d(TAG, "Pruning complete")
    }

    // ── Inline pattern detection ──────────────────────────────────────────────

    private suspend fun detectAndWritePreference(utterance: String, sessionId: String): Boolean {
        for (pattern in PREFERENCE_PATTERNS) {
            pattern.find(utterance) ?: continue
            val normalized = utterance.trim()
            val alreadyStored = memoryDao.countByContentAndType(normalized, MemoryType.PREFERENCE) > 0
            if (alreadyStored) return false
            writePreference(normalized, sessionId)
            return true
        }
        return false
    }

    private suspend fun detectAndWriteTask(utterance: String, sessionId: String): Boolean {
        for (pattern in TASK_PATTERNS) {
            pattern.find(utterance) ?: continue
            val normalized = utterance.trim()
            val alreadyStored = memoryDao.countByContentAndType(normalized, MemoryType.TASK) > 0
            if (alreadyStored) return false
            writeTask(normalized, sessionId)
            return true
        }
        return false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun embedBytes(text: String): ByteArray? =
        embeddingEngine?.embed(text)?.let { MemoryEmbeddingEngine.toByteArray(it) }

    private fun buildKeywords(text: String): String =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .distinct()
            .take(25)
            .joinToString(",")
}
