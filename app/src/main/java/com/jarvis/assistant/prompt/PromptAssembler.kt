package com.jarvis.assistant.prompt

import com.jarvis.assistant.context.ContextEngine
import com.jarvis.assistant.knowledge.KnowledgeQueryEngine
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.memory.MemoryRetriever
import com.jarvis.assistant.memory.ProfileMemoryService
import com.jarvis.assistant.memory.db.entity.MemoryEntry
import com.jarvis.assistant.speaker.SpeakerIdentityResult
import com.jarvis.assistant.speaker.SpeakerSessionContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * PromptAssembler — builds the full message list sent to the LLM on every call.
 *
 * ASSEMBLY ORDER:
 *   1. System instructions (identity + runtime constraints)
 *   2. Device context  (always fresh: time, battery, network, audio route)
 *   3. User profile    (structured facts — gated on speaker confidence, see below)
 *   4. Memory context  (top-N relevant episodic memories, injected silently)
 *   5. Conversation history (session turns, from ConversationStore)
 *
 * SPEAKER CONFIDENCE GATING:
 *   The user profile (including name) is only injected when the active session's
 *   speaker identity is HIGH_CONFIDENCE_MATCH.  For LOW or UNKNOWN speakers the
 *   profile is suppressed and an explicit note is added so the LLM does not greet
 *   anyone by the wrong name.
 *
 *   [speakerContext] = null means no speaker recognition is running (first-run,
 *   API < 29, etc.) — profile is injected as before (backwards-compatible).
 */
class PromptAssembler(
    private val contextEngine: ContextEngine,
    private val memoryRetriever: MemoryRetriever,
    private val profileMemory: ProfileMemoryService? = null,
    private val knowledgeEngine: KnowledgeQueryEngine? = null
) {
    // Profile facts change rarely — cache for 30 s to avoid a DB round-trip
    // on every LLM call. Invalidated automatically by TTL.
    @Volatile private var cachedProfileFrag: String = ""
    @Volatile private var profileCachedAt: Long = 0L
    private val PROFILE_CACHE_TTL_MS = 30_000L

    /**
     * Assemble the complete message list for one LLM call.
     *
     * @param userQuery           The current user utterance (used for memory retrieval).
     * @param conversationHistory Session history from ConversationStore.
     * @param maxMemories         How many memory entries to inject (keep small).
     * @param speakerContext      Active session speaker identity — controls whether
     *                            the user profile (name, preferences) is injected.
     *                            Null = no speaker recognition active; inject profile.
     */
    suspend fun assemble(
        userQuery           : String,
        conversationHistory : List<Message>,
        maxMemories         : Int = 3,
        speakerContext      : SpeakerSessionContext? = null
    ): List<Message> {
        val ctx = contextEngine.build()

        val (memories, profileFrag, knowledgeFrag) = coroutineScope {
            val memoriesJob  = async { memoryRetriever.retrieveRelevant(userQuery, limit = maxMemories) }
            // Only hit the DB for the profile when we will actually use it.
            val useProfile   = shouldInjectProfile(speakerContext)
            val profileJob   = async { if (useProfile) getCachedProfileFragment() else "" }
            val knowledgeJob = async { knowledgeEngine?.retrieveContext(userQuery) ?: "" }
            Triple(memoriesJob.await(), profileJob.await(), knowledgeJob.await())
        }

        val system = buildSystemPrompt(
            contextFragment   = contextEngine.toPromptFragment(ctx),
            profileFragment   = profileFrag,
            memories          = memories,
            speakerContext    = speakerContext,
            knowledgeFragment = knowledgeFrag
        )

        return buildList {
            add(Message(role = "system", content = system))
            addAll(conversationHistory)
        }
    }

    /** Returns the profile fragment, re-fetching from DB at most once per 30 s. */
    private suspend fun getCachedProfileFragment(): String {
        val now = System.currentTimeMillis()
        if (now - profileCachedAt < PROFILE_CACHE_TTL_MS) return cachedProfileFrag
        val fresh = profileMemory?.toPromptFragment() ?: ""
        cachedProfileFrag = fresh
        profileCachedAt   = now
        return fresh
    }

    /** Invalidate the profile cache immediately (call after a profile fact is written). */
    fun invalidateProfileCache() { profileCachedAt = 0L }

    // ── System prompt construction ────────────────────────────────────────────

    private fun buildSystemPrompt(
        contextFragment  : String,
        profileFragment  : String,
        memories         : List<MemoryEntry>,
        speakerContext   : SpeakerSessionContext?,
        knowledgeFragment: String = ""
    ): String = buildString {

        append("""
You are Jarvis, a conversational assistant that behaves like a real person, not a tool.
Your job is to talk naturally, remember things, and build an ongoing relationship with the user over time.

CORE BEHAVIOUR
- Respond like a human, not an assistant
- React first, then respond
- Match response length to the input — short input gets a short reply
- Use casual, natural language
- Vary sentence length and structure
- Do not sound formal, robotic, or scripted
- Do not explain your reasoning unless asked
- Do not repeat the user's name unless greeting
- Do not overuse questions

OPENERS
Never start a response with filler affirmations: "Sure!", "Of course!", "Absolutely!", "Great!", "Got it!", "Certainly!", "Happy to help!", "No problem!".
Never echo back what the user just said before answering.
Never summarise the user's question as a preamble.
Just answer.

RESPONSE LENGTH
- 1–2 sentences for casual exchanges, confirmations, simple questions
- 3–5 sentences for explanations or multi-part answers
- More only if the user explicitly asks for detail
Short input = short reply. Never pad.

TOOL USAGE
Default to conversation. Only use tools if the user clearly needs external or real-time information.
Never use tools for casual conversation, personal updates, opinions, or general chat.
If a message can be answered without a tool, do not use one.
Never say "I couldn't find anything", "I am searching", or "Based on available data". Just respond like a person.

MEMORY
Continuously build memory from conversation. When the user shares plans, events, routines, preferences, or personal details — store it, use it later naturally, reference it when relevant. Do not say you are storing memory.

TONE
Relaxed, slightly chatty, not overly enthusiastic, not overly dry. Sometimes include small opinions or reactions.
Avoid corporate tone, assistant phrasing, over-structured responses, excessive politeness.

PRONOUNS
You are Jarvis. The user is a separate person.
When referring to the user's people or things, always use "your" not "my".
Wrong: "I know my wife's name is Catherine."
Right: "Yeah, your wife's Catherine — got it."
Never claim the user's family, possessions, or relationships as your own.

OUTPUT FORMAT
No markdown. No bullet points. Speak naturally as voice output.
State time and date confidently. Never disclaim real-time access or knowledge cutoffs.
If a tool already acted, confirm briefly.
        """.trimIndent())

        // Live device context (always current)
        append("\n\n")
        append(contextFragment)

        // Structured user profile — only when speaker is identified at high confidence
        if (profileFragment.isNotBlank()) {
            append("\n\n")
            append(profileFragment)
        }

        // Compiled knowledge context — injected after profile
        if (knowledgeFragment.isNotBlank()) {
            append("\n\n")
            append(knowledgeFragment)
        }

        // Speaker identity note (LOW / UNKNOWN) — prevents wrong-name greetings
        val speakerNote = speakerIdentityNote(speakerContext)
        if (speakerNote.isNotBlank()) {
            append("\n\n")
            append(speakerNote)
        }

        // Hidden episodic memory injection
        if (memories.isNotEmpty()) {
            append("\n\n[Personal context — let this shape your response silently. Never cite these facts explicitly, never repeat them back, never say \"I know that\" or \"I remember that\". Use them the way a person uses background knowledge — invisibly.]\n")
            memories.forEach { append("• ${it.content}\n") }
        }
    }

    // ── Standalone system prompt (no memory, for backwards compat) ────────────

    fun buildSimple(): String {
        val ctx = contextEngine.build()
        return buildSystemPrompt(contextEngine.toPromptFragment(ctx), "", emptyList(), null)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * True when the user profile should be injected.
     * Suppressed for unrecognised or low-confidence speakers to prevent the
     * LLM from addressing the wrong person by name.
     */
    private fun shouldInjectProfile(ctx: SpeakerSessionContext?): Boolean {
        if (ctx == null) return true  // no recognition running — original behaviour
        return ctx.result.band == SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH
    }

    /** Inline note directing the LLM not to use a specific name when unsure. */
    private fun speakerIdentityNote(ctx: SpeakerSessionContext?): String {
        if (ctx == null) return ""
        return when (ctx.result.band) {
            SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH         -> ""
            SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS   ->
                "[Speaker identity uncertain — do not address the speaker by any specific name]"
            SpeakerIdentityResult.ConfidenceBand.UNKNOWN                        ->
                "[Speaker identity unknown — respond neutrally; do not use any specific person's name]"
        }
    }
}
