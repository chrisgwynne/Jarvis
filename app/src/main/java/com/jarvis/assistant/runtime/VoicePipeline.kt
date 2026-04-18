package com.jarvis.assistant.runtime

import android.content.Context
import android.util.Log
import com.jarvis.assistant.audio.BargeInDetector
import com.jarvis.assistant.audio.TtsEngine
import com.jarvis.assistant.brain.BrainEngine
import com.jarvis.assistant.conversation.ConversationClassifier
import com.jarvis.assistant.core.state.JarvisStateMachine
import com.jarvis.assistant.data.ConversationCompressor
import com.jarvis.assistant.llm.LlmRouter
import com.jarvis.assistant.memory.MemoryWriter
import com.jarvis.assistant.prompt.PromptAssembler
import com.jarvis.assistant.security.ActionPolicyGate
import com.jarvis.assistant.speaker.SpeakerSessionContext
import com.jarvis.assistant.tools.framework.ToolRegistry
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.CoroutineScope

/**
 * VoicePipeline — the extracted conversation processing pipeline.
 *
 * EXTRACTION STATUS:
 *   Interface and dependency list defined. JarvisRuntime still owns the
 *   implementation in its internal conversation loop (lines ~1030–1900).
 *
 * MIGRATION PLAN:
 *   Step A (current): Skeleton established with full dependency list.
 *   Step B: Move streamAndSpeak(), callLlm(), speakAndRecord() here.
 *   Step C: Move the while(true) conversation loop here; JarvisRuntime
 *           calls voicePipeline.run(scope) to start the loop.
 *
 * WHY EXTRACT:
 *   JarvisRuntime is ~2080 lines. The voice conversation loop (the part that
 *   processes one user turn from transcript → tool/LLM → TTS) is ~900 lines
 *   and has well-defined inputs/outputs, making it the natural extraction target.
 *   Separating it here would let us unit-test the conversation path against
 *   fake implementations of LlmRouter, TtsEngine, and ToolRegistry.
 *
 * DEPENDENCIES (all must be injected; no Context singletons):
 *   - context: for tool execution that needs Android APIs
 *   - machine: state transitions (Listening → Thinking → Speaking → Listening)
 *   - llmRouter: LLM calls + conversation history
 *   - promptAssembler: system prompt + memory injection
 *   - toolRegistry: tool matching + execution
 *   - ttsEngine: TTS synthesis
 *   - bargeInDetector: barge-in detection during TTS
 *   - memoryWriter: post-turn memory persistence
 *   - conversationCompressor: rolling summarisation after each turn
 *   - conversationClassifier: intent classification (small-talk vs command)
 *   - brainEngine: behavioural event logging
 *   - settings: per-turn configuration reads
 */
class VoicePipeline(
    private val context: Context,
    private val machine: JarvisStateMachine,
    private val llmRouter: LlmRouter,
    private val promptAssembler: PromptAssembler,
    private val toolRegistry: ToolRegistry,
    private val ttsEngine: TtsEngine,
    private val bargeInDetector: BargeInDetector,
    private val memoryWriter: MemoryWriter,
    private val conversationCompressor: ConversationCompressor,
    private val conversationClassifier: ConversationClassifier,
    private val brainEngine: BrainEngine?,
    private val settings: SettingsStore,
    /** Called to speak a response and record it in conversation history. */
    private val onSpeak: suspend (String) -> Unit,
    /** Called to retrieve the current speaker session context. */
    private val speakerContextProvider: () -> SpeakerSessionContext
) {

    companion object {
        private const val TAG = "VoicePipeline"
    }

    /**
     * Start the conversation loop. Suspends until the pipeline is cancelled.
     *
     * @param scope Coroutine scope for launching sub-jobs (barge-in, memory writes).
     *
     * EXTRACTION TARGET: JarvisRuntime's while(true) loop at ~line 1030.
     */
    suspend fun run(scope: CoroutineScope) {
        // TODO: Extract conversation loop from JarvisRuntime here.
        // Current stub — JarvisRuntime still runs the loop directly.
        Log.d(TAG, "VoicePipeline.run() — delegation to JarvisRuntime (extraction pending)")
    }

    /**
     * Process one completed assistant turn: stream LLM response sentence by
     * sentence, feed each to TTS, handle barge-in, and save to history.
     *
     * EXTRACTION TARGET: JarvisRuntime.streamAndSpeak() at ~line 1740.
     */
    suspend fun streamAndSpeak(transcript: String, isOnline: Boolean) {
        Log.d(TAG, "VoicePipeline.streamAndSpeak() — stub (JarvisRuntime still owns implementation)")
    }
}
