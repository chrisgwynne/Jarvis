package com.jarvis.assistant.data

import android.util.Log
import com.jarvis.assistant.llm.Message

/**
 * Minimal interface for the conversation-store operations [ConversationCompressor] needs.
 * [ConversationStore] implements this. Tests can implement it directly without Android Context.
 */
interface CompressibleStore {
    val size: Int
    fun oldestPairs(pairs: Int): List<Message>
    fun applyRollingContext(summary: String, pairs: Int)
}

/**
 * ConversationCompressor — rolls up old turn-pairs when the context window is near full.
 *
 * When [ConversationStore] holds [ConversationStore.MAX_HISTORY_PAIRS] pairs it starts
 * dropping the oldest on every new message, silently discarding context. Instead, when
 * history reaches the compression threshold, this compressor summarises the oldest
 * [PAIRS_TO_COMPRESS] pairs via the LLM and stores the result in
 * [ConversationStore.rollingContext], which is injected as a pinned system block on
 * the next turn.
 *
 * Call [maybeCompress] after each completed assistant turn. It is a no-op unless the
 * compression threshold is met.
 *
 * @param store    The in-memory conversation store to inspect and modify.
 * @param summarise Suspend function that takes a message list and returns a summary.
 *                  Typically [LlmRouter.completeSilent].
 */
class ConversationCompressor(
    private val store: CompressibleStore,
    private val summarise: suspend (List<Message>) -> String
) {

    companion object {
        private const val TAG = "ConversationCompressor"

        /** Compress when history holds at least this many pairs. */
        private const val COMPRESS_THRESHOLD = ConversationStore.MAX_HISTORY_PAIRS - 1

        /** Number of oldest pairs to fold into the rolling summary each time. */
        private const val PAIRS_TO_COMPRESS = 2
    }

    /**
     * Check whether compression is needed and, if so, summarise the oldest pairs
     * and fold them into [ConversationStore.rollingContext].
     *
     * Must be called from a coroutine running on Dispatchers.IO (or a scope where
     * network I/O is permitted), since [summarise] involves an LLM call.
     */
    suspend fun maybeCompress() {
        if (store.size < COMPRESS_THRESHOLD * 2) return

        val pairs = store.oldestPairs(PAIRS_TO_COMPRESS)
        if (pairs.isEmpty()) return

        val prompt = buildList {
            add(Message("system",
                "Summarise the following conversation excerpt in 2–3 sentences, " +
                "preserving key facts, decisions, and named entities. " +
                "Be concise — output only the summary, no preamble."
            ))
            addAll(pairs)
        }

        val summary = try {
            summarise(prompt).trim()
        } catch (e: Exception) {
            Log.w(TAG, "Summarisation failed — skipping compression: ${e.message}")
            return
        }

        if (summary.isNotBlank()) {
            store.applyRollingContext(summary, PAIRS_TO_COMPRESS)
            Log.d(TAG, "Compressed ${PAIRS_TO_COMPRESS} pairs → \"${summary.take(80)}…\"")
        }
    }
}
