package com.jarvis.assistant.runtime.session

/**
 * SessionContinuationPolicy — single source of truth for "after this
 * local command finished, should Jarvis stay listening or end the
 * session?"
 *
 * Background.  Some tools (`volume_control`, `flashlight`, `time`)
 * return `silent = true` because the user wants a fast tactile
 * confirmation, not a spoken one.  Previously the dispatcher routed
 * those through `DispatchResult.SilentExit`, which triggered
 * [com.jarvis.assistant.runtime.JarvisRuntime.backToWakeWord] — closing
 * the attention window and forcing the user to say "Jarvis" again.
 * That's not what "silent" should mean.
 *
 * This policy separates two concerns:
 *
 *   1. **Should we speak?** — owned by `ToolResult.silent`.
 *   2. **Should the session continue?** — owned by [decide] below.
 *
 * For instant-router tools the answer is almost always "continue".  A
 * small allowlist of tools genuinely WANTS the session to end after
 * dispatch (open-app launches another full-screen UI; end-call hangs
 * up; camera_capture switches to the camera UI).  Everything else
 * keeps listening.
 *
 * Pure / Android-free / unit-testable.
 */
object SessionContinuationPolicy {

    enum class Verdict {
        /** Stay in the conversation loop; extend the active window. */
        CONTINUE_LISTENING,
        /** Tear down the session, return to wake-word detection. */
        STOP_LISTENING,
        /** Stop only the current TTS utterance; keep listening. */
        STOP_TTS_ONLY,
        /** Require wake word; do NOT stay in active conversation. */
        REQUIRE_WAKE_WORD,
        /** Mute Jarvis output for the configured duration. */
        ENTER_SILENT_MODE,
    }

    /**
     * Tools whose intent is to hand the user over to another full-
     * screen UI / external surface.  After dispatch the conversation
     * loop should END because the user isn't looking at Jarvis any
     * more.  Everything not in this set defaults to CONTINUE_LISTENING.
     */
    val SESSION_ENDING_TOOLS: Set<String> = setOf(
        "open_app",          // user goes to the launched app
        "end_call",          // call ends → conversation done
        "camera_capture",    // camera UI takes over
        "music_search",      // hand-off to a music app
    )

    /**
     * Phrases the user can say to explicitly stop Jarvis or silence it.
     * Checked BEFORE tool routing so even a tool-matching transcript
     * can be overridden ("stop listening" still stops, even if it
     * matched something).
     */
    /**
     * Explicit "stop listening" / "cancel" / "never mind" / "that's
     * enough" — these unambiguously tear down the session.  Bare
     * "stop" is NOT in this list; it has its own ambiguous handling
     * (stop TTS only when speaking, otherwise just keep listening)
     * via [STOP_TTS_ONLY_PHRASES] below.
     */
    private val STOP_LISTENING_PHRASES = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:
            stop\s+listening
          | cancel(?:\s+that)?
          | never\s+mind
          | that(?:'?s|\s+is)\s+enough
        )
        \s*[.!?]?\s*$
        """,
    )

    private val SILENCE_ASSISTANT_PHRASES = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:
            go\s+quiet
          | be\s+quiet
          | silence(?:\s+yourself)?
          | shut\s+up
          | mute\s+yourself
        )
        \s*[.!?]?\s*$
        """,
    )

    /**
     * Stop ONLY the current TTS output without ending the session.
     * Uttered during a long spoken reply ("stop" while speaking).
     */
    private val STOP_TTS_ONLY_PHRASES = Regex(
        """(?ix)^\s*stop\s*[.!?]?\s*$""",
    )

    /**
     * Decide the post-command verdict.
     *
     * @param toolName       The tool the dispatcher just executed.
     * @param transcript     The raw user utterance, lowercased.  Used to
     *                       detect explicit "stop listening" / "be quiet"
     *                       phrases that override the tool's preference.
     * @param ttsIsSpeaking  True when Jarvis was speaking when this
     *                       command arrived — relevant for "stop"
     *                       interpretation (stop TTS only vs stop session).
     */
    fun decide(
        toolName: String?,
        transcript: String,
        ttsIsSpeaking: Boolean = false,
    ): Verdict {
        // Explicit stop / silence phrases win every time.
        if (STOP_LISTENING_PHRASES.matches(transcript.trim()))     return Verdict.STOP_LISTENING
        if (SILENCE_ASSISTANT_PHRASES.matches(transcript.trim()))  return Verdict.ENTER_SILENT_MODE
        if (STOP_TTS_ONLY_PHRASES.matches(transcript.trim())) {
            // "stop" alone — only meaningful while speaking.  Otherwise
            // it's noise and we keep listening.
            return if (ttsIsSpeaking) Verdict.STOP_TTS_ONLY else Verdict.CONTINUE_LISTENING
        }

        // Tool-driven session ending.
        if (toolName != null && toolName in SESSION_ENDING_TOOLS) {
            return Verdict.STOP_LISTENING
        }

        // Default: stay listening.  Local device commands (volume,
        // flashlight, time, battery, calendar lookup, …) should never
        // force the user to re-say the wake word.
        return Verdict.CONTINUE_LISTENING
    }

    /** Convenience predicate for explicit-stop detection. */
    fun isExplicitStopOrSilence(transcript: String): Boolean {
        val t = transcript.trim()
        return STOP_LISTENING_PHRASES.matches(t) ||
            SILENCE_ASSISTANT_PHRASES.matches(t)
    }
}
