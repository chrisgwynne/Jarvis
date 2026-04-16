package com.jarvis.assistant.memory

import android.util.Log
import com.jarvis.assistant.llm.LlmRouter
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.memory.db.dao.ConversationDao

/**
 * MemorySummarizer — converts raw conversation turns into a compact summary
 * that is stored as a SUMMARY MemoryEntry for future retrieval.
 *
 * Called by JarvisRuntime when a session closes (silence or stop command).
 * Runs on the background IO dispatcher so the main pipeline never blocks.
 *
 * If the LLM call fails (offline, error) a simple heuristic summary is
 * written instead so no conversation is ever lost entirely.
 */
class MemorySummarizer(
    private val conversationDao: ConversationDao,
    private val llmRouter: LlmRouter,
    private val memoryWriter: MemoryWriter
) {

    companion object {
        private const val TAG = "MemorySummarizer"
        private const val MIN_TURNS_TO_SUMMARIZE = 2
        private const val MAX_TURNS_IN_PROMPT = 10
    }

    /**
     * Summarise [sessionId] and close it in the database.
     * Safe to call from a background coroutine — does not touch the audio pipeline.
     */
    suspend fun summarizeAndClose(sessionId: String) {
        val turns = conversationDao.getTurnsForSession(sessionId)
        if (turns.size < MIN_TURNS_TO_SUMMARIZE) {
            memoryWriter.closeSession(sessionId, summary = null)
            return
        }

        // Build the transcript excerpt (last N turns to keep the prompt small)
        val excerpt = turns.takeLast(MAX_TURNS_IN_PROMPT)
            .joinToString("\n") { "${it.role.uppercase()}: ${it.content}" }

        // Use completeSilent so the summarisation prompt and response are never
        // added to ConversationStore.  Using complete() or completeWithMessages()
        // here pollutes the next session's history with meta-prompts and summaries,
        // which confuses the LLM and degrades conversation quality.
        val summary = try {
            llmRouter.completeSilent(listOf(
                Message("system",
                    "Summarize this voice assistant conversation in 1–2 factual sentences. " +
                    "Focus on what the user asked, any decisions made, and any preferences expressed. " +
                    "Be specific and concrete. Omit greetings and filler."),
                Message("user", excerpt)
            ))
        } catch (e: Exception) {
            Log.w(TAG, "LLM summarisation failed, using heuristic: ${e.message}")
            // Heuristic: first user turn as the summary
            turns.firstOrNull { it.role == "user" }?.content?.take(200) ?: return
        }

        memoryWriter.closeSession(sessionId, summary.take(400))
        Log.d(TAG, "Session $sessionId summarised: ${summary.take(80)}…")
    }
}
