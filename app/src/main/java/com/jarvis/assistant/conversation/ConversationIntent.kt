package com.jarvis.assistant.conversation

/**
 * Classified intent for a single user utterance.
 *
 * Used by [ConversationClassifier] and [ToolUsePolicy] to decide
 * whether tools should be invoked or the LLM should respond directly.
 */
enum class ConversationIntent {
    /** Explicit device command: call, text, alarm, open app, volume, etc. */
    ACTION_REQUEST,

    /** User sharing something personal: plans, feelings, events, preferences. */
    PERSONAL_UPDATE,

    /** Needs live external data: weather, news, sports scores, prices. */
    REAL_WORLD_LOOKUP,

    /** Asking what Jarvis knows or remembers about the user. */
    MEMORY_QUERY,

    /** Short fragment continuing an active multi-turn exchange. */
    FOLLOW_UP_REPLY,

    /** General conversation, opinion, knowledge question — LLM handles it. */
    CASUAL_CHAT
}
