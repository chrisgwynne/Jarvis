package com.jarvis.assistant.llm.providers

import android.util.Log
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmRateLimitedException
import com.jarvis.assistant.llm.LlmResult
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.tools.framework.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * MiniMax provider — MiniMax AI's chat model (OpenAI-compatible endpoint).
 *
 * Endpoint: https://api.minimax.io/v1/chat/completions
 * Key:      https://platform.minimaxi.com/user-center/basic-information/interface-key
 *
 * ## Model names
 *
 * MiniMax's API is **case-sensitive** for model identifiers, and its high-speed
 * variant is not just `MiniMax-M2.7-highspeed` — that string is rejected with a
 * 400.  The official aliases (as of 2026-05) are:
 *
 *   | User intent                        | API model id              |
 *   |------------------------------------|---------------------------|
 *   | Default reasoning                  | `MiniMax-M2.7`            |
 *   | Higher throughput / lower latency  | `MiniMax-M2.7-highspeed`  |
 *   | Older flagship                     | `MiniMax-Text-01`         |
 *   | Light/cheap                        | `abab6.5s-chat`           |
 *
 * Common user typings ("M2.7 highspeed", "minimax-2.7-fast", "M.27", missing
 * hyphens) are normalised to the canonical id in [canonicalise] so a small
 * spelling drift doesn't silently break LLM calls.  Anything we don't
 * recognise is passed through verbatim — MiniMax may have added new variants.
 *
 * On HTTP failure we log the canonicalised name + base URL + the response
 * preview so the user can see in logcat exactly which model the gateway
 * rejected and why.
 */
class MiniMaxProvider(
    apiKey: String,
    baseUrl: String,
    model: String,
    maxTokens: Int = 1200,
) : BaseOpenAiProvider(
    apiKey    = apiKey,
    baseUrl   = baseUrl.trim().trimEnd('/'),
    model     = canonicalise(model),
    maxTokens = maxTokens,
) {
    override val name = "MiniMax"

    private val resolvedModel = canonicalise(model)
    private val resolvedBaseUrl = baseUrl.trim().trimEnd('/')

    override suspend fun complete(messages: List<Message>): String =
        wrap("complete") { super.complete(messages) }

    override fun streamComplete(messages: List<Message>): Flow<String> =
        super.streamComplete(messages).catch { t -> reportAndRethrow("streamComplete", t) }

    suspend fun completeWithToolsLogged(messages: List<Message>, tools: List<ToolSchema>): LlmResult =
        wrap("completeWithTools") { super.completeWithTools(messages, tools) }

    private inline fun <T> wrap(stage: String, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        reportAndRethrow(stage, t)
    }

    private fun reportAndRethrow(stage: String, t: Throwable): Nothing {
        Log.w(TAG,
            "[MINIMAX_CALL_FAILED] stage=$stage model='$resolvedModel' " +
            "baseUrl='$resolvedBaseUrl' err=${t.javaClass.simpleName}: ${t.message}"
        )
        val msg = t.message.orEmpty()
        // ── HTTP 429 / rate_limit_error ─────────────────────────────────────
        // The cloud LLM is over quota.  Surface this as a recognisable
        // RateLimited exception so the runtime can ack briefly without
        // burning more retries.  We still wrap as LlmException so the
        // existing catch-all paths route correctly.
        val isRateLimit = msg.contains("HTTP 429") ||
            msg.contains("rate_limit_error", ignoreCase = true) ||
            msg.contains("rate limit", ignoreCase = true) ||
            msg.contains("Too Many Requests", ignoreCase = true)
        if (isRateLimit) {
            Log.w(TAG, "[MINIMAX_RATE_LIMITED] model='$resolvedModel' — falling back")
            throw LlmRateLimitedException(
                "MiniMax is rate-limited right now (${msg.take(120)})",
                t
            )
        }
        // Wrap unknown-model rejections with a model-name hint.
        val looksLikeUnknownModel = msg.contains("model", ignoreCase = true) ||
            msg.contains("HTTP 400") || msg.contains("HTTP 404")
        throw if (looksLikeUnknownModel) {
            LlmException(
                "MiniMax rejected '$resolvedModel' (${msg.take(160)})",
                t
            )
        } else t
    }

    companion object {
        private const val TAG = "MiniMaxProvider"

        /**
         * Map a user-typed model identifier to the canonical MiniMax API id.
         *
         * Keeps the comparison case-insensitive and ignores spaces / hyphens
         * / underscores in the input so "MiniMax M2.7 highspeed",
         * "minimax-m2.7-high-speed" and "MINIMAX_M2.7_HIGHSPEED" all resolve
         * to the same canonical id.  Unrecognised inputs are returned trimmed
         * but otherwise unchanged.
         */
        internal fun canonicalise(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "MiniMax-M2.7"
            val normalised = trimmed
                .lowercase()
                .replace(Regex("[\\s_-]+"), "")
                // Common STT mishearing: "M.27" / "m27" → "m2.7"
                .replace("m.27", "m2.7")
                .replace(Regex("(?<![0-9])m27(?![0-9])"), "m2.7")
            return when {
                // Speed / high-speed / fast / lightning variants — MiniMax's
                // canonical id is lowercase `-highspeed` (confirmed against the
                // live gateway 2026-05).  Capitalised "-Speed" / "-HighSpeed"
                // are silently rejected with HTTP 400.
                normalised.contains("m2.7") &&
                    (normalised.contains("highspeed") ||
                     normalised.contains("speed") ||
                     normalised.contains("fast") ||
                     normalised.contains("lightning")) -> "MiniMax-M2.7-highspeed"
                // Base M2.7
                normalised.contains("m2.7") -> "MiniMax-M2.7"
                // Older flagship
                normalised.contains("text01") || normalised.contains("text-01") -> "MiniMax-Text-01"
                // Cheap/light
                normalised.contains("abab6.5s") || normalised.contains("abab65s") -> "abab6.5s-chat"
                // Pass through anything we don't recognise — user may know about
                // a newer variant we haven't catalogued yet.
                else -> trimmed
            }
        }
    }
}
