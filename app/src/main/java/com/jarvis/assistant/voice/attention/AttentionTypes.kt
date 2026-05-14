package com.jarvis.assistant.voice.attention

import com.jarvis.assistant.modes.JarvisMode

/**
 * Who, if anyone, the user appears to be talking to.
 */
enum class ConversationTarget { JARVIS, HUMAN, BACKGROUND, UNKNOWN }

/**
 * The three possible outcomes from [AttentionGate.gate].
 *
 * [Accept]        — route the transcript to the local-first router as usual.
 * [Ignore]        — drop the transcript silently; no TTS, no transcript log,
 *                   no state machine transition.
 * [AskIfForMe]    — speak a brief confirmation prompt (e.g. "Was that for
 *                   me?") and wait for the next utterance to confirm.
 */
sealed class AttentionDecision {
    abstract val target: ConversationTarget
    abstract val reason: String
    abstract val score:  Float          // total composite score [-1.0 .. 2.0]

    data class Accept(
        override val target: ConversationTarget,
        override val reason: String,
        override val score:  Float
    ) : AttentionDecision()

    data class Ignore(
        override val target: ConversationTarget,
        override val reason: String,
        override val score:  Float
    ) : AttentionDecision()

    data class AskIfForMe(
        override val target: ConversationTarget,
        override val reason: String,
        override val score:  Float,
        val prompt: String = "Was that for me?"
    ) : AttentionDecision()
}

/**
 * Live signals the gate scores against.  Pulled together by JarvisRuntime
 * right before each gate call so a single snapshot is consistent for the
 * whole decision.
 */
data class AttentionSignals(
    /** The corrected transcript (after [com.jarvis.assistant.audio.stt.TranscriptCorrector]). */
    val transcript:               String,
    /** STT confidence in [0,1].  Use 0f when unavailable. */
    val sttConfidence:            Float,
    /** Current Jarvis mode for verbosity / sensitivity adjustments. */
    val mode:                     JarvisMode,
    /** Wall-clock ms until the active-conversation window expires. */
    val activeWindowUntilMs:      Long,
    /** Wall-clock ms when Jarvis last finished speaking. */
    val lastJarvisResponseMs:     Long,
    /** Wall-clock ms now. */
    val nowMs:                    Long,
    /** True if a phone call is currently ringing or connected. */
    val isInCall:                 Boolean,
    /** True if media is playing (Spotify / YouTube / etc. has the focus). */
    val isMediaPlaying:           Boolean,
    /** Bluetooth headset / earbuds are connected. */
    val isHeadsetConnected:       Boolean,
    /** Screen is on (proxy for user attention to the phone). */
    val screenOn:                 Boolean,
    /** Jarvis is currently mid-TTS. */
    val isTtsActive:              Boolean,
    /** The last sentence Jarvis spoke — used for echo guard. */
    val lastTtsText:              String?,
    /** A local tool's matcher recognised this transcript. */
    val localCommandMatch:        Boolean,
    /** Tool name when [localCommandMatch] is true. */
    val localCommandToolName:     String?,
    /** TranscriptCorrector composite score (higher = clearer command). */
    val transcriptCorrectorScore: Int,
    /** True if the transcript text looks like a system notification body. */
    val looksLikeNotificationText:Boolean
)
