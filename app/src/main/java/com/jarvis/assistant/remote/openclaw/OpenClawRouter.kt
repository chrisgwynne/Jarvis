package com.jarvis.assistant.remote.openclaw

import android.util.Log
import com.jarvis.assistant.llm.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Decides whether a transcript should be routed to OpenClaw and, if so,
 * what route type applies.  Then executes the remote call.
 *
 * ROUTING RULES
 * ─────────────
 * 1. Keyword match  — transcript starts with [OpenClawSettings.keyword] →
 *                     always REMOTE (LONG if it also matches a LONG_PATTERN, else FAST).
 *                     The keyword is stripped before sending so OpenClaw receives the
 *                     clean query.
 *
 * 2. LOCAL_FAST     — matches a LOCAL_PATTERN → kept on-device entirely.
 *
 * 3. REMOTE_LONG    — matches a LONG_PATTERN (research, writing, planning tasks).
 *                     Jarvis speaks "Looking into that." first.
 *
 * 4. Default        — LOCAL_FAST (stay on device). Only keyword + LONG_PATTERN hits
 *                     go to OpenClaw automatically; everything else stays local.
 */
class OpenClawRouter(
    private val settingsRepo: OpenClawSettingsRepository,
    private val client:       OpenClawClient = OpenClawClient()
) {

    companion object {
        private const val TAG = "OpenClawRouter"

        private val SENTENCE_BOUNDARY = Regex("""[.!?]\s""")

        // Patterns that force LOCAL_FAST — keep on device
        private val LOCAL_PATTERNS = listOf(
            Regex("""^(?:what(?:'s| is) (?:the )?(?:time|date|day)|what day is it)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:how are you|what(?:'s| are) you|are you|do you|can you|tell me about yourself)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:hi|hello|hey|thanks|thank you|cheers|ok|okay|yes|no|sure|never mind|forget it)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:what is \d|calculate|how much is \d|\d+\s*[+\-*/]\s*\d)""", RegexOption.IGNORE_CASE),
            Regex("""^(?:set|turn|switch|enable|disable|open|close|start|stop|play|pause|resume|cancel)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:alarm|timer|reminder|note|call|text|message|photo|selfie|record)\b""", RegexOption.IGNORE_CASE)
        )

        // Patterns that classify as REMOTE_LONG — long-running research/generation tasks
        private val LONG_PATTERNS = listOf(
            Regex("""^(?:write|draft|compose|create)\s+(?:me\s+)?(?:a|an|the)\s+\w""", RegexOption.IGNORE_CASE),
            Regex("""^(?:explain|describe|summarise|summarize)\s+(?:in detail|everything|how|why|what)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:find out|research|look into|investigate|analyse|analyze)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:plan|outline|generate|build me|give me a list of|list all)\b""", RegexOption.IGNORE_CASE),
            Regex("""^(?:what(?:'s| is) the (?:best|difference|meaning|history|background))\b""", RegexOption.IGNORE_CASE),
            // Queries referencing personal/stored knowledge — local LLM can't answer these.
            // "what's in my wiki", "check the wiki", "my notes on X", "search my docs"
            Regex("""(?:my|the)\s+wiki\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:in|on|from|about|check|search)\s+my\s+(?:notes?|knowledge\s*base|docs?|documents?|files?|obsidian|notion|confluence)\b""", RegexOption.IGNORE_CASE),
        )
    }

    /**
     * Returns true when OpenClaw is enabled and fully configured.
     * Call before [execute] to avoid needlessly building a request.
     */
    fun shouldRoute(): Boolean = settingsRepo.isConfigured()

    /**
     * Classify a transcript into a [RouteType].
     *
     * Checks in order: keyword → LOCAL_FAST patterns → LONG_PATTERNS → default LOCAL_FAST.
     */
    fun classify(transcript: String): RouteType {
        val t      = transcript.trim()
        val kw     = settingsRepo.snapshot().keyword.trim().lowercase()

        // 1. Explicit keyword → always REMOTE
        if (kw.isNotBlank() && t.lowercase().startsWith(kw)) {
            return if (LONG_PATTERNS.any { it.containsMatchIn(t) }) RouteType.REMOTE_LONG
                   else RouteType.REMOTE_FAST
        }

        // 2. LOCAL_FAST patterns → stay on device
        if (LOCAL_PATTERNS.any { it.containsMatchIn(t) }) return RouteType.LOCAL_FAST

        // 3. Auto-classify clearly complex queries → REMOTE_LONG
        if (LONG_PATTERNS.any { it.containsMatchIn(t) }) return RouteType.REMOTE_LONG

        // 4. Default → stay local (do not silently intercept all queries)
        return RouteType.LOCAL_FAST
    }

    /**
     * Route [transcript] to OpenClaw and return the execution result.
     *
     * Returns [OpenClawExecutionResult.Bypassed] when:
     *   - OpenClaw is not configured/enabled
     *   - The transcript classifies as [RouteType.LOCAL_FAST]
     *
     * Callers are responsible for speaking the REMOTE_LONG acknowledgement
     * ("Looking into that.") BEFORE calling this function.
     *
     * @param recentMessages  Last N turns from ConversationStore for context injection.
     */
    suspend fun execute(
        transcript:     String,
        sessionId:      String,
        recentMessages: List<Message> = emptyList()
    ): OpenClawExecutionResult {
        if (!shouldRoute()) return OpenClawExecutionResult.Bypassed

        val settings = settingsRepo.snapshot()
        val route    = classify(transcript)
        Log.d(TAG, "Route=$route for: ${transcript.take(60)}")

        if (route == RouteType.LOCAL_FAST) return OpenClawExecutionResult.Bypassed

        // Strip keyword prefix so OpenClaw receives the clean query
        val kw = settings.keyword.trim().lowercase()
        val cleanTranscript = if (kw.isNotBlank() && transcript.trim().lowercase().startsWith(kw))
            transcript.trim().substring(kw.length).trim()
        else transcript

        // Build OpenAI message array: recent history (up to 6 turns) + current user turn
        val ocMessages = buildList {
            recentMessages.takeLast(6)
                .filter { it.role == "user" || it.role == "assistant" }
                .forEach { add(OpenClawChatMessage(role = it.role, content = it.content)) }
            add(OpenClawChatMessage(role = "user", content = cleanTranscript))
        }

        val request = OpenClawRequest(
            model    = settings.modelName,
            messages = ocMessages
        )

        return client.send(settings, request)
    }

    /**
     * Same routing logic as [execute] but returns a [Flow] of sentences rather than
     * waiting for the full response.  Returns null when routing is disabled or the
     * transcript classifies as [RouteType.LOCAL_FAST].
     *
     * The returned flow emits complete sentences (not raw tokens) so the caller can
     * pass each sentence directly to TTS.  Throws on HTTP / IO errors.
     */
    fun executeStreaming(
        transcript:     String,
        sessionId:      String,
        recentMessages: List<Message> = emptyList()
    ): Flow<String>? {
        if (!shouldRoute()) return null
        val settings = settingsRepo.snapshot()
        val route    = classify(transcript)
        if (route == RouteType.LOCAL_FAST) return null

        val kw = settings.keyword.trim().lowercase()
        val cleanTranscript = if (kw.isNotBlank() && transcript.trim().lowercase().startsWith(kw))
            transcript.trim().substring(kw.length).trim()
        else transcript

        val ocMessages = buildList {
            recentMessages.takeLast(6)
                .filter { it.role == "user" || it.role == "assistant" }
                .forEach { add(OpenClawChatMessage(role = it.role, content = it.content)) }
            add(OpenClawChatMessage(role = "user", content = cleanTranscript))
        }

        val request    = OpenClawRequest(model = settings.modelName, messages = ocMessages, stream = true)
        val tokenFlow  = client.stream(settings, request)

        return flow {
            val buffer = StringBuilder()
            tokenFlow.collect { token ->
                buffer.append(token)
                var sentence = extractSentence(buffer)
                while (sentence != null) {
                    emit(sentence)
                    sentence = extractSentence(buffer)
                }
            }
            val trailing = buffer.toString().trim()
            if (trailing.isNotBlank()) emit(trailing)
        }
    }

    private fun extractSentence(buffer: StringBuilder): String? {
        val text  = buffer.toString()
        val match = SENTENCE_BOUNDARY.find(text) ?: return null
        val end      = match.range.first + 1
        val sentence = text.substring(0, end).trim()
        buffer.delete(0, match.range.last + 1)
        return sentence.ifBlank { null }
    }
}
