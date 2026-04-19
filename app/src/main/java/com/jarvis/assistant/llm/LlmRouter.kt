package com.jarvis.assistant.llm

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.ConversationStore
import com.jarvis.assistant.llm.providers.AnthropicProvider
import com.jarvis.assistant.llm.providers.GeminiProvider
import com.jarvis.assistant.llm.providers.KimiProvider
import com.jarvis.assistant.llm.providers.MiniMaxProvider
import com.jarvis.assistant.llm.providers.OllamaProvider
import com.jarvis.assistant.llm.providers.OpenAiProvider
import com.jarvis.assistant.llm.providers.OpenRouterProvider
import com.jarvis.assistant.tools.framework.ToolSchema
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * LlmRouter — the brain coordinator.
 *
 * RESPONSIBILITIES:
 *   1. Route to the correct provider based on Settings.
 *   2. Maintain conversation history via ConversationStore.
 *   3. Inject system prompt on every call.
 *   4. Retry once after 2 s on transient network / server errors.
 *
 * USAGE (from AudioManager):
 *   val reply = llmRouter.complete("What's the weather?")
 *   // ConversationStore now contains: user msg + this assistant reply
 *   // Next call will include both as context, enabling follow-ups.
 *
 * RETRY POLICY:
 *   Non-retryable:  "No API key", HTTP 4xx  → return error string immediately
 *   Retryable:      HTTP 5xx, IOException   → wait 2 s, try once more
 *   If the retry also fails, return a user-friendly error string (don't crash).
 *
 * NOTE:
 *   complete() accepts a plain String (the transcript). The router adds it to
 *   ConversationStore, prepends the system prompt, and sends the full context.
 */
class LlmRouter(context: Context) {

    companion object {
        private const val TAG = "LlmRouter"
        private const val RATE_LIMIT_RETRY_MS = 5_000L   // back-off on HTTP 429
        private const val LLM_TIMEOUT_MS      = 20_000L  // hard cap on any provider call

        /**
         * Regex for sentence boundaries used by [streamWithMessages].
         * Matches [.!?] followed by whitespace — catches the vast majority of
         * natural sentence endings without false-positives for decimal numbers.
         */
        private val SENTENCE_BOUNDARY = Regex("""[.!?]\s""")
    }

    private val settings = SettingsStore(context)

    /** Shared conversation history. AudioManager can call clear() for "new conversation". */
    val conversationStore = ConversationStore(context)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Process one user turn end-to-end:
     *   1. Save [userMessage] to history
     *   2. Build full context (system + history)
     *   3. Call provider (with one retry on failure)
     *   4. Save assistant reply to history
     *   5. Return reply
     */
    suspend fun complete(userMessage: String): String {
        conversationStore.addMessage("user", userMessage)
        val messages = conversationStore.getContextMessages()

        val response = try {
            callWithRetry(messages)
        } catch (e: CancellationException) {
            throw e   // propagate coroutine cancellation — do NOT swallow this
        } catch (e: LlmException) {
            Log.w(TAG, "LLM error (non-retryable): ${e.message}")
            e.message ?: "Something went wrong."
        } catch (e: Exception) {
            Log.e(TAG, "LLM unexpected error", e)
            "Something went wrong."
        }

        // Strip chain-of-thought reasoning blocks that some models emit
        val cleaned = stripReasoningTags(response)

        // Only store clean responses in history, not error strings
        if (!isErrorResponse(cleaned)) {
            conversationStore.addMessage("assistant", cleaned)
        }

        return cleaned
    }

    /**
     * Send a pre-assembled message list directly to the provider.
     *
     * Unlike [complete], the caller is responsible for adding the user message
     * to [conversationStore] BEFORE calling this function.  This function:
     *   1. Sends [messages] to the active provider (with one retry on failure).
     *   2. Strips chain-of-thought reasoning tags.
     *   3. Saves the assistant reply to [conversationStore].
     *   4. Returns the cleaned reply.
     *
     * Use this when [PromptAssembler] owns the full message list and the caller
     * has already injected context + memories into the system prompt.
     */
    suspend fun completeWithMessages(messages: List<Message>): String {
        val response = try {
            callWithRetry(messages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            Log.w(TAG, "LLM error (non-retryable): ${e.message}")
            e.message ?: "Something went wrong."
        } catch (e: Exception) {
            Log.e(TAG, "LLM unexpected error", e)
            "Something went wrong."
        }

        val cleaned = stripReasoningTags(response)

        if (!isErrorResponse(cleaned)) {
            conversationStore.addMessage("assistant", cleaned)
        }

        return cleaned
    }

    /**
     * Call the active provider with a pre-assembled message list without touching
     * [conversationStore].
     *
     * Use this for background/internal LLM calls (memory summarisation, knowledge
     * compilation, entity extraction) that must NOT pollute the live conversation
     * context the user experiences on the next turn.
     *
     * Unlike [completeWithMessages], the response is never added to history and
     * the request messages are never logged as user turns.
     */
    suspend fun completeSilent(messages: List<Message>): String {
        val response = try {
            callWithRetry(messages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            Log.w(TAG, "LLM error (silent): ${e.message}")
            e.message ?: "Something went wrong."
        } catch (e: Exception) {
            Log.e(TAG, "LLM unexpected error (silent)", e)
            "Something went wrong."
        }
        return stripReasoningTags(response)
        // Deliberately does NOT write to conversationStore
    }

    /**
     * Stream one pre-assembled message list to the provider and emit complete
     * sentences one by one, so the caller can start speaking before the full
     * response has been received.
     *
     * Sentence buffering: tokens are accumulated in a [StringBuilder].  Each time
     * the buffer contains a sentence-ending punctuation ([.!?]) followed by
     * whitespace, the sentence is emitted and the buffer is trimmed.
     * Any remaining text at stream end is emitted as a final fragment.
     *
     * The full cleaned response is saved to [conversationStore] once the stream
     * completes normally.  On cancellation (e.g. barge-in) the partial response
     * is NOT saved — the caller is responsible for any cleanup.
     *
     * The caller must add the user message to [conversationStore] BEFORE calling
     * this function (same contract as [completeWithMessages]).
     */
    fun streamWithMessages(messages: List<Message>): Flow<String> = flow {
        val provider = activeProvider()
        val buffer       = StringBuilder()
        val fullResponse = StringBuilder()
        // Strip <think>/<thinking> reasoning blocks as tokens arrive, so the
        // TTS never speaks chain-of-thought content on reasoning models
        // (MiniMax, DeepSeek, some Kimi builds).
        val stripper = ReasoningTagStripper()

        provider.streamComplete(messages).collect { token ->
            fullResponse.append(token)
            val safe = stripper.process(token)
            if (safe.isNotEmpty()) {
                buffer.append(safe)
                // Emit every complete sentence that has arrived so far
                var sentence = extractNextSentence(buffer)
                while (sentence != null) {
                    emit(sentence)
                    sentence = extractNextSentence(buffer)
                }
            }
        }

        // Flush any held-back pending text (partial opener that never became
        // a tag), then any trailing sentence fragment without punctuation.
        val flushed = stripper.flush()
        if (flushed.isNotEmpty()) buffer.append(flushed)
        val trailing = buffer.toString().trim()
        if (trailing.isNotBlank()) emit(trailing)

        // Persist full response in conversation context
        val cleaned = stripReasoningTags(fullResponse.toString().trim())
        if (cleaned.isNotBlank() && !isErrorResponse(cleaned)) {
            conversationStore.addMessage("assistant", cleaned)
        }
    }

    /** Centralised check for strings our error paths return, to avoid storing them as assistant turns. */
    private fun isErrorResponse(s: String): Boolean =
        s.startsWith("Error:") ||
        s.startsWith("No API key") ||
        s.startsWith("HTTP ") ||
        s.startsWith("Network error:") ||
        s == "Something went wrong."

    /**
     * Pull the next complete sentence from [buffer], mutating it in place.
     * Returns null if no sentence boundary is found yet.
     */
    private fun extractNextSentence(buffer: StringBuilder): String? {
        val text  = buffer.toString()
        val match = SENTENCE_BOUNDARY.find(text) ?: return null
        // Include the punctuation char but not the trailing whitespace
        val end      = match.range.first + 1
        val sentence = text.substring(0, end).trim()
        buffer.delete(0, match.range.last + 1)   // consume up to and including the space
        return sentence.ifBlank { null }
    }

    // ── Provider selection ─────────────────────────────────────────────────────

    private fun activeProvider(): LlmProvider = providerByName(settings.llmProvider)

    internal fun providerByName(providerName: String): LlmProvider {
        val apiKey    = settings.apiKey
        val ollamaUrl = settings.ollamaBaseUrl
        val mt        = settings.maxTokens

        return when (providerName) {
            "Anthropic"  -> AnthropicProvider(apiKey, mt)
            "Gemini"     -> GeminiProvider(apiKey, mt)
            "Ollama"     -> OllamaProvider(ollamaUrl)
            "OpenRouter" -> OpenRouterProvider(apiKey, mt)
            "Kimi"       -> KimiProvider(apiKey, mt)
            "MiniMax"    -> MiniMaxProvider(apiKey, settings.miniMaxBaseUrl, settings.miniMaxModel, mt)
            else         -> {
                val openAiToken = if (settings.openAiOAuthEnabled && settings.openAiAccessToken.isNotBlank())
                    settings.openAiAccessToken
                else
                    apiKey
                OpenAiProvider(openAiToken, mt)
            }
        }
    }

    val currentProviderName: String get() = activeProvider().name

    /**
     * Call the provider with function-calling tools.
     *
     * Returns [LlmResult.ToolCall] or [LlmResult.Text] when a function-calling-capable
     * provider handles the request, or **null** when the active provider does not
     * support native function calling (Gemini, Ollama) — the caller should fall back
     * to the streaming path in that case.
     *
     * Note: The assistant response is NOT written to [conversationStore] here.
     * The caller is responsible for saving it after deciding how to present it.
     */
    suspend fun completeWithFunctionCalling(
        messages: List<Message>,
        tools: List<ToolSchema>
    ): LlmResult? {
        if (tools.isEmpty()) return null
        val provider = activeProvider()
        return try {
            when (provider) {
                is AnthropicProvider  -> provider.completeWithTools(messages, tools)
                is OpenAiProvider     -> provider.completeWithTools(messages, tools)
                is OpenRouterProvider -> provider.completeWithTools(messages, tools)
                is KimiProvider       -> provider.completeWithTools(messages, tools)
                is MiniMaxProvider    -> provider.completeWithTools(messages, tools)
                is GeminiProvider     -> provider.completeWithTools(messages, tools)
                is OllamaProvider     -> provider.completeWithTools(messages, tools)
                else                  -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Function calling failed (${e.message}) — falling back to streaming")
            null
        }
    }

    // ── Retry logic ────────────────────────────────────────────────────────────

    /**
     * Try the provider once. On transient failure, wait [retryDelayMs] and try
     * a second time. Non-retryable errors (no API key, HTTP 4xx) throw immediately.
     *
     * Delay uses a 500 ms base + ±100 ms jitter (max 4 s) rather than a flat
     * 2 s wait — faster recovery on short blips, same ceiling on real outages.
     */
    private suspend fun callWithRetry(messages: List<Message>): String {
        return try {
            callWithTimeout(messages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            when {
                isRateLimit(e) -> {
                    Log.w(TAG, "Rate limited — retrying in ${RATE_LIMIT_RETRY_MS}ms")
                    delay(RATE_LIMIT_RETRY_MS)
                    callWithTimeout(messages)
                }
                isNonRetryable(e) -> throw e
                else -> {
                    val delayMs = retryDelayMs()
                    Log.w(TAG, "First attempt failed (${e.message}) — retrying in ${delayMs}ms")
                    delay(delayMs)
                    try {
                        callWithTimeout(messages)
                    } catch (e2: Exception) {
                        tryFallbackProvider(messages, e2)
                    }
                }
            }
        } catch (e: IOException) {
            val delayMs = retryDelayMs()
            Log.w(TAG, "Network failure (${e.javaClass.simpleName}) — retrying in ${delayMs}ms")
            delay(delayMs)
            try {
                callWithTimeout(messages)
            } catch (e2: Exception) {
                tryFallbackProvider(messages, e2)
            }
        }
    }

    /** Wrap a single provider call in the per-attempt timeout. */
    private suspend fun callWithTimeout(messages: List<Message>): String =
        withTimeoutOrNull(LLM_TIMEOUT_MS) { activeProvider().complete(messages) }
            ?: throw LlmException("LLM request timed out after ${LLM_TIMEOUT_MS / 1000}s")

    private suspend fun tryFallbackProvider(messages: List<Message>, originalError: Exception): String {
        val fallback = settings.fallbackProvider.takeIf { it.isNotBlank() }
            ?: throw if (originalError is LlmException) originalError
                     else LlmException("Network error: ${originalError.javaClass.simpleName}")

        Log.w(TAG, "Primary provider failed — trying fallback: $fallback")
        return try {
            withTimeoutOrNull(LLM_TIMEOUT_MS) { providerByName(fallback).complete(messages) }
                ?: throw LlmException("Fallback ($fallback) timed out")
        } catch (e: Exception) {
            throw LlmException("Both primary and fallback ($fallback) failed")
        }
    }

    /** 500 ms base + up to 100 ms jitter (≤ 600 ms). */
    private fun retryDelayMs(): Long = 500L + (0L..100L).random()

    /**
     * Strip chain-of-thought blocks that reasoning models emit before their answer.
     * Handles <think>…</think> (MiniMax, DeepSeek) and <thinking>…</thinking>.
     * Also collapses any leading/trailing whitespace left behind.
     *
     * Previous behaviour: if the whole response was inside think tags we
     * returned the raw text — which meant the user's TTS spoke the model's
     * chain-of-thought verbatim. That's worse than saying nothing, because
     * the reasoning is often verbose and off-voice. Return a short, safe
     * fallback string instead and let the caller treat it as an error line
     * (isErrorResponse already flags this constant).
     */
    private fun stripReasoningTags(text: String): String {
        val stripped = text
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<thinking>[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE), "")
            .trim()
        return stripped.ifBlank { "Something went wrong." }
    }

    /** True if the server is rate-limiting us (HTTP 429). 429 is retryable with backoff. */
    private fun isRateLimit(e: LlmException): Boolean =
        e.message?.contains("HTTP 429", ignoreCase = true) == true

    /**
     * True if the error is caused by misconfiguration or auth failure —
     * retrying will never help for these.
     * NOTE: 429 is checked first in callWithRetry — "HTTP 4" would otherwise match it.
     */
    private fun isNonRetryable(e: LlmException): Boolean {
        val msg = e.message ?: return false
        return msg.contains("No API key", ignoreCase = true) ||
               msg.contains("HTTP 4",    ignoreCase = true)  // 401, 403, 4xx (not 429)
    }

}
