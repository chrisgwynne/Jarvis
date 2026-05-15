package com.jarvis.assistant.session

/** Whether Jarvis is actively listening for a response. */
enum class ListeningState {
    /** Between sessions; wake word required. */
    IDLE,
    /** Active session, general listening — any command accepted. */
    LISTENING,
    /** Waiting for a specific slot value (e.g. "what time?"). */
    AWAITING_SLOT,
    /** Waiting for a yes/no confirmation. */
    AWAITING_CONFIRMATION,
}

/**
 * Snapshot of a single wake-word-to-quiet Jarvis session.
 *
 * Held in-memory by [SessionStateEngine]; never persisted.  A new instance is
 * created each time the wake word fires.
 */
data class JarvisSession(
    val sessionId: String,
    val startedAt: Long = System.currentTimeMillis(),
    var lastUserSpeechAt: Long = startedAt,
    var lastAssistantSpeechAt: Long = startedAt,
    /** The multi-step goal currently in progress (null when Jarvis is just chatting). */
    var activeGoal: ConversationGoal? = null,
    /** A deferred single-shot action awaiting confirmation or one missing value. */
    var pendingAction: PendingAction? = null,
    var listeningState: ListeningState = ListeningState.LISTENING,
    /** Time after which this session is considered stale and new commands should start fresh. */
    var expiresAt: Long = startedAt + NORMAL_TTL_MS,
) {
    companion object {
        /** Normal session window — extended on each successful command. */
        const val NORMAL_TTL_MS = 30_000L
        /** Window extended while waiting for a slot answer. */
        const val SLOT_TTL_MS = 60_000L
        /** Window kept alive after execution — context still usable. */
        const val POST_EXECUTE_TTL_MS = 30_000L
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs > expiresAt

    fun extendNormal() {
        expiresAt = System.currentTimeMillis() + NORMAL_TTL_MS
    }

    fun extendForSlot() {
        expiresAt = System.currentTimeMillis() + SLOT_TTL_MS
        listeningState = ListeningState.AWAITING_SLOT
    }

    fun extendPostExecute() {
        expiresAt = System.currentTimeMillis() + POST_EXECUTE_TTL_MS
        listeningState = ListeningState.LISTENING
    }
}
