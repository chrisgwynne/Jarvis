package com.jarvis.assistant.runtime

import android.content.Context
import android.util.Log
import com.jarvis.assistant.speaker.SpeakerEnrollmentManager
import com.jarvis.assistant.speaker.SpeakerProfileStore
import com.jarvis.assistant.speaker.SpeakerSessionContext

/**
 * SpeakerFlowHandler — handles voice enrollment and guest/owner disambiguation flows.
 *
 * This class captures the branching logic currently embedded in JarvisRuntime's
 * conversation loop:
 *   - awaitingOwnerName  — new-user flow where Jarvis asks "who's this?"
 *   - awaitingVoiceEnrollmentSample — multi-sample voice capture during enrollment
 *   - guest/owner disambiguation when multiple speakers are known
 *
 * EXTRACTION TARGET:
 *   The full implementation lives in JarvisRuntime (lines ~1030–1327).
 *   Future work: move those branches here and have JarvisRuntime call
 *   [handle] at the top of the conversation loop before tool dispatch.
 *
 * CURRENT STATUS:
 *   Interface defined; JarvisRuntime still owns the implementation.
 *   Extract incrementally: begin with [awaitingOwnerName] (simplest), then
 *   [awaitingVoiceEnrollmentSample], then guest disambiguation.
 */
class SpeakerFlowHandler(
    private val context: Context,
    private val profileStore: SpeakerProfileStore,
    private val enrollmentManager: SpeakerEnrollmentManager
) {

    companion object {
        private const val TAG = "SpeakerFlowHandler"
    }

    /**
     * Result of processing a transcript through the speaker identity flow.
     */
    sealed class FlowResult {
        /**
         * The transcript was consumed by the speaker flow.
         * [response] is the spoken reply JarvisRuntime should deliver.
         * [updatedContext] is the new [SpeakerSessionContext] to use for the rest of the turn.
         */
        data class Handled(
            val response: String,
            val updatedContext: SpeakerSessionContext
        ) : FlowResult()

        /**
         * The speaker flow did not consume this transcript.
         * JarvisRuntime should proceed with normal tool + LLM dispatch.
         */
        data class PassThrough(val context: SpeakerSessionContext) : FlowResult()
    }

    /**
     * Process [transcript] through the speaker identity flow.
     *
     * Called at the top of the conversation loop when [sessionContext] has an
     * active enrollment or disambiguation state.  Returns [FlowResult.PassThrough]
     * when no speaker flow is active, so the caller can fall through to tool dispatch.
     */
    fun handle(
        transcript: String,
        sessionContext: SpeakerSessionContext
    ): FlowResult {
        // Delegate to existing JarvisRuntime logic until full extraction is complete.
        // The branches below mirror the when-chain in JarvisRuntime's conversation loop.
        Log.v(TAG, "SpeakerFlowHandler.handle called (extraction in progress)")
        return FlowResult.PassThrough(sessionContext)
    }
}
