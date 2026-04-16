package com.jarvis.assistant.conversation

/**
 * ToolUsePolicy — single source of truth for when the ToolRegistry may be invoked.
 *
 * Hard rules:
 *   - Only ACTION_REQUEST and REAL_WORLD_LOOKUP go to the tool layer.
 *     Everything else routes to the LLM directly.
 *   - PERSONAL_UPDATE and CASUAL_CHAT: pure conversation, no tools.
 *   - MEMORY_QUERY: LLM handles naturally using injected memory context.
 *   - FOLLOW_UP_REPLY: short continuations ("yeah", "ok", "go on") must
 *     never hit tool matching — they always continue the conversation.
 */
object ToolUsePolicy {

    /**
     * True when the ToolRegistry may be consulted for this intent.
     * False means skip tool matching entirely and go straight to the LLM.
     */
    fun allowsTools(intent: ConversationIntent): Boolean = when (intent) {
        ConversationIntent.ACTION_REQUEST    -> true
        ConversationIntent.REAL_WORLD_LOOKUP -> true
        ConversationIntent.PERSONAL_UPDATE   -> false
        ConversationIntent.CASUAL_CHAT       -> false
        ConversationIntent.MEMORY_QUERY      -> false
        ConversationIntent.FOLLOW_UP_REPLY   -> false
    }
}
