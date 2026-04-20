package com.jarvis.assistant.tools.device

import com.jarvis.assistant.core.events.EventKind
import com.jarvis.assistant.core.presence.ExpectationStore
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * NoteExpectationTool — lets the LLM (or a voice path) register a short-
 * term expectation the system should hold. Feeds [ExpectationStore] so
 * the prompt and scoring can consult it.
 *
 * Intentionally LLM-first: the tool's schema is the primary interface;
 * the regex only catches simple spoken forms ("expect Dan at 6 pm",
 * "remember I'm waiting for a delivery"). Real extraction happens via
 * the LLM's function-calling path which has all the context it needs.
 */
class NoteExpectationTool(
    private val store: ExpectationStore,
) : Tool {
    override val name = "note_expectation"
    override val description = "Register a short-term expectation for the agent to hold in mind"
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions: List<String> = emptyList()

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = "Record a short-term expectation (e.g. user said they'll be home by 6, or a delivery is expected). Optional time or event kind ties it to a trigger.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "label" to mapOf("type" to "string"),
                "triggerAtMs" to mapOf("type" to "integer"),
                "triggerEventKind" to mapOf("type" to "string"),
                "expiresInMinutes" to mapOf("type" to "integer"),
            ),
            "required" to listOf("label"),
        ),
    )

    override fun matches(transcript: String): ToolInput? {
        val match = RE.find(transcript.trim().lowercase()) ?: return null
        val label = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return ToolInput(transcript = transcript, params = mapOf("label" to label))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val label = input.param("label").trim()
        if (label.isBlank()) return ToolResult.Failure("What should I hold in mind?")
        val triggerAt = input.paramOrNull("triggerAtMs")?.toLongOrNull()
        val kindName = input.paramOrNull("triggerEventKind")
        val triggerKind = kindName?.let { n -> runCatching { EventKind.valueOf(n) }.getOrNull() }
        val expiresIn = input.paramOrNull("expiresInMinutes")?.toLongOrNull()?.let { it * 60_000L }
            ?: ExpectationStore.DEFAULT_TTL_MS

        store.expect(
            label = label,
            triggerAtMs = triggerAt,
            triggerEventKind = triggerKind,
            expiresInMs = expiresIn,
            sourceTranscript = input.transcript.takeIf { it.isNotBlank() },
        )
        return ToolResult.Success(spokenFeedback = "", silent = true)
    }

    companion object {
        private val RE = Regex(
            """(?:expect|remember|note|hold\s+onto)\s+(?:that\s+)?(.+?)\s*[.!?]?\s*$"""
        )
    }
}
