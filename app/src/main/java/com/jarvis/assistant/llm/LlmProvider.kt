package com.jarvis.assistant.llm

import com.jarvis.assistant.tools.framework.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A single turn in a conversation.
 * role is "system", "user", or "assistant" — matches the OpenAI convention
 * which every major LLM API has also adopted.
 */
data class Message(
    val role: String,
    val content: String
)

/**
 * Result from an LLM call that may include a tool call request.
 */
sealed class LlmResult {
    /** Normal text response from the model. */
    data class Text(val content: String) : LlmResult()
    /** The model wants to call a tool. */
    data class ToolCall(val toolName: String, val argsJson: String) : LlmResult()
}

/**
 * LlmProvider — the common interface every AI backend must implement.
 *
 * WHY AN INTERFACE?
 * The rest of the app (audio layer, UI) never needs to know *which* LLM is
 * in use. It just calls complete() and gets a string back. Swapping providers
 * is a single setting change with zero code changes elsewhere.
 *
 * All implementations must be coroutine-safe (suspend fun) because network
 * calls cannot run on the main thread.
 */
interface LlmProvider {
    /** Human-readable provider name shown in settings UI. */
    val name: String

    /**
     * Send a list of messages and return the assistant's reply.
     * Throws [LlmException] on API errors.
     */
    suspend fun complete(messages: List<Message>): String

    /**
     * Stream the assistant's reply as a [Flow] of raw text tokens.
     *
     * Default implementation falls back to [complete] and emits the full
     * response as a single token.  Providers that support native SSE streaming
     * (OpenAI, Anthropic) override this for sub-second first-token latency.
     */
    fun streamComplete(messages: List<Message>): Flow<String> = flow {
        emit(complete(messages))
    }
}

/** Wraps provider-level errors with a readable message. */
class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
