package com.jarvis.assistant.voice.attention

import android.util.Log
import com.jarvis.assistant.modes.JarvisMode
import com.jarvis.assistant.voice.VoiceFeatureFlags

/**
 * AttentionGate — pure decision module that decides, per transcript,
 * whether Jarvis should respond, stay silent, or ask "was that for me?"
 *
 * # Design intent
 *
 * The app is always-listening once wake fires (or, in the future, all the
 * time).  Without this gate, any speech captured during the conversation
 * window is treated as a command — which means Jarvis talks back when the
 * user is talking to a real person in the same room.
 *
 * # Decision model
 *
 * Each transcript accumulates a composite **attention score** in `[-1, 2]`.
 * Sources of signal, in rough order of weight:
 *
 *  | Signal                                         | Δ score  | Notes                                  |
 *  |------------------------------------------------|----------|----------------------------------------|
 *  | Explicit "Jarvis" / "hey Jarvis" prefix        | +2.0     | Override — always ACCEPT               |
 *  | Active conversation window not expired         | +1.5     | Follow-ups inside the post-reply window|
 *  | Strong local command match (ToolRegistry)      | +1.2     | A tool's matcher recognised it         |
 *  | Strong action verb + known object              | +0.6     | "turn on kitchen lights"               |
 *  | TranscriptCorrector score ≥ HIGH tier          | +0.4     | Lexical evidence of command intent     |
 *  | Barge-in stop/cancel phrase while TTS active   | +1.5     | "stop", "cancel", "wait"               |
 *  |                                                |          |                                        |
 *  | Strong human-conversation pattern matched      | -1.5     | "what do you want for tea"             |
 *  | Soft human-conversation pattern matched        | -0.3 ea  | "thank you", "sorry"                   |
 *  | Phone call active                              | -2.0     | Talking to a human on the phone        |
 *  | Looks like a notification body                 | -2.0     | HA motion alert, etc.                  |
 *  | Echoes Jarvis's own last TTS                   | -2.0     | TTS bleed-through                      |
 *  | Low STT confidence and no command match        | -0.5     | Mumble / background chatter            |
 *
 * Verdict:
 *  - score ≥ +0.8   → [AttentionDecision.Accept]
 *  - score ≤ -0.3   → [AttentionDecision.Ignore]
 *  - in between      → [AttentionDecision.AskIfForMe]
 *
 * Mode modifiers (applied to the thresholds rather than the score so the
 * raw signals stay legible in logs):
 *  - [JarvisMode.NIGHT]  / [JarvisMode.FOCUS] → ACCEPT bar raised to +1.2
 *  - [JarvisMode.DRIVING]                     → ACCEPT bar lowered to +0.6
 *
 * Gated by [VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED].  When the flag
 * is off the gate always returns [AttentionDecision.Accept] with reason
 * `gate_disabled` so callers cannot regress to silence by accident.
 */
class AttentionGate {

    companion object {
        private const val TAG = "AttentionGate"

        /**
         * NORMAL-mode ACCEPT bar.  Retuned much lower so command-like
         * utterances flow through without forcing an "is that for me?"
         * prompt — the user's measured frustration on the conservative
         * 0.8 bar showed Ask was firing on perfectly valid commands.
         */
        const val ACCEPT_THRESHOLD_DEFAULT = 0.5f
        /**
         * Scores at or below this are silently ignored (no Ask).  Raised
         * from -0.3 to 0.0 so neutral / weak-signal phrases fall to IGNORE
         * by default instead of into the Ask zone.
         */
        const val IGNORE_THRESHOLD          = 0.0f
        const val ACTIVE_WINDOW_MS          = 15_000L
        /**
         * "Recent interaction" half-life.  When the user has spoken to
         * Jarvis within this many ms, we are inside conversational
         * momentum — Ask is allowed; outside it, weak utterances become
         * IGNORE.
         */
        const val RECENT_INTERACTION_MS     = 30_000L
        /** Max token count for an utterance to be eligible for Ask. */
        const val SHORT_PHRASE_MAX_TOKENS   = 6
    }

    @Volatile private var _activeWindowUntilMs: Long = 0L
    val activeWindowUntilMs: Long get() = _activeWindowUntilMs

    /** Call right after Jarvis successfully responds.  Default = 15 s window. */
    fun extendActiveWindow(extensionMs: Long = ACTIVE_WINDOW_MS) {
        _activeWindowUntilMs = System.currentTimeMillis() + extensionMs
        Log.d(TAG, "[ATTENTION_ACTIVE_WINDOW_SET] until=${_activeWindowUntilMs}")
    }

    /** Drop the active window — used when the user explicitly ends a thread. */
    fun closeActiveWindow() {
        _activeWindowUntilMs = 0L
    }

    fun isInActiveWindow(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs < _activeWindowUntilMs

    /**
     * The main entry point.  Pure function of [signals] + the gate's
     * internal active-window timer.
     */
    fun gate(signals: AttentionSignals): AttentionDecision {
        if (!VoiceFeatureFlags.isEnabled(VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED)) {
            return AttentionDecision.Accept(
                target = ConversationTarget.UNKNOWN,
                reason = "gate_disabled",
                score  = 1.0f
            )
        }

        val t = signals.transcript.trim()
        Log.d(TAG, "[ATTENTION_TRANSCRIPT_RECEIVED] \"$t\" stt_conf=${signals.sttConfidence} " +
            "mode=${signals.mode} active_window=${isInActiveWindow(signals.nowMs)} " +
            "tts_active=${signals.isTtsActive} call=${signals.isInCall}")

        // ── Hard short-circuits (always IGNORE, regardless of score) ─────────
        if (signals.looksLikeNotificationText) {
            Log.d(TAG, "[ATTENTION_REJECTED_NOTIFICATION_TEXT] \"$t\"")
            return AttentionDecision.Ignore(
                target = ConversationTarget.BACKGROUND,
                reason = "notification_text",
                score  = -2.0f
            )
        }

        if (isTtsEcho(t, signals.lastTtsText)) {
            Log.d(TAG, "[ATTENTION_REJECTED_TTS_ECHO] \"$t\"")
            return AttentionDecision.Ignore(
                target = ConversationTarget.BACKGROUND,
                reason = "tts_echo",
                score  = -2.0f
            )
        }

        if (signals.isInCall) {
            Log.d(TAG, "[ATTENTION_IGNORED_BACKGROUND] phone_call_active \"$t\"")
            return AttentionDecision.Ignore(
                target = ConversationTarget.HUMAN,
                reason = "phone_call_active",
                score  = -2.0f
            )
        }

        // ── Hard short-circuits (always ACCEPT) ──────────────────────────────
        if (containsWakeWord(t)) {
            Log.d(TAG, "[ATTENTION_ACCEPTED] explicit_wake_word \"$t\"")
            return AttentionDecision.Accept(
                target = ConversationTarget.JARVIS,
                reason = "explicit_wake_word",
                score  = 2.0f
            )
        }

        if (signals.isTtsActive && isBargeInPhrase(t)) {
            Log.d(TAG, "[ATTENTION_ACCEPTED] barge_in_phrase \"$t\"")
            return AttentionDecision.Accept(
                target = ConversationTarget.JARVIS,
                reason = "barge_in_phrase",
                score  = 1.8f
            )
        }

        // Hard short-circuit: explicit command pattern.  When the phrase starts
        // with a canonical command opener ("turn on", "send", "play", "set a
        // timer for…", etc.), the user's intent is unambiguous — accept without
        // forcing them to wait for a confirmation prompt.
        if (matchesCommandPattern(t)) {
            Log.d(TAG, "[ATTENTION_ACCEPTED_COMMAND_PATTERN] \"$t\"")
            return AttentionDecision.Accept(
                target = ConversationTarget.JARVIS,
                reason = "command_pattern_short_circuit",
                score  = 2.0f
            )
        }

        // Hard short-circuit: strong local-tool match means the ToolRegistry
        // already recognised the utterance as actionable — no ambiguity to
        // confirm.  This used to score +1.2 and was often the difference
        // between ACCEPT and Ask; bypassing the score entirely is safer.
        if (signals.localCommandMatch) {
            Log.d(TAG, "[ATTENTION_ACCEPTED_TOOL_MATCH] tool=${signals.localCommandToolName} \"$t\"")
            return AttentionDecision.Accept(
                target = ConversationTarget.JARVIS,
                reason = "local_tool_match(${signals.localCommandToolName})",
                score  = 1.8f
            )
        }

        // ── Score accumulation ────────────────────────────────────────────────
        var score = 0.0f
        val reasons = mutableListOf<String>()

        val inWindow = isInActiveWindow(signals.nowMs)
        if (inWindow) {
            score += 1.8f
            reasons += "active_window(+1.8)"
            Log.d(TAG, "[ATTENTION_ACTIVE_WINDOW_USED] until=${_activeWindowUntilMs}")
        } else if (_activeWindowUntilMs > 0L && signals.nowMs >= _activeWindowUntilMs) {
            Log.d(TAG, "[ATTENTION_ACTIVE_WINDOW_EXPIRED] expired_at=${_activeWindowUntilMs}")
            _activeWindowUntilMs = 0L
        }

        if (matchesActionVerbAndObject(t)) {
            score += 1.0f
            reasons += "action_verb_object(+1.0)"
        } else if (matchesStrongActionVerb(t)) {
            // Bare strong verb without an obvious object — still likely a command.
            score += 0.6f
            reasons += "action_verb(+0.6)"
        }

        if (signals.transcriptCorrectorScore >= 14) {            // matches HIGH tier
            score += 0.5f
            reasons += "corrector_high(+0.5)"
        } else if (signals.transcriptCorrectorScore >= 6) {      // matches MEDIUM tier
            score += 0.2f
            reasons += "corrector_medium(+0.2)"
        }

        // Negative signals.
        if (HumanConversationPatterns.strongMatch(t)) {
            score -= 2.0f
            reasons += "human_strong(-2.0)"
        }
        val softHits = HumanConversationPatterns.softMatchCount(t)
        if (softHits > 0) {
            val delta = -0.4f * softHits
            score += delta
            reasons += "human_soft×$softHits(${"%.1f".format(delta)})"
        }

        if (signals.sttConfidence in 0.01f..0.55f) {
            score -= 0.3f
            reasons += "low_stt_conf(-0.3)"
        }

        if (signals.isMediaPlaying) {
            // Media noise can leak into the mic; only mildly suspicious.
            score -= 0.2f
            reasons += "media_playing(-0.2)"
        }

        Log.d(TAG, "[ATTENTION_SCORE_BREAKDOWN] total=${"%.2f".format(score)} " +
            "reasons=$reasons mode=${signals.mode}")

        // ── Verdict ──────────────────────────────────────────────────────────
        val acceptBar = acceptThresholdFor(signals.mode)
        return decide(t, score, acceptBar, reasons, signals, inWindow)
    }

    /**
     * Threshold-and-rule verdict.  Encodes the new philosophy:
     *
     *   ACCEPT  — score ≥ acceptBar
     *   IGNORE  — score ≤ IGNORE_THRESHOLD, OR no conversational momentum
     *             (no active window, no recent interaction, no command verb)
     *   ASK     — only for true ambiguity: short phrase + moderate score +
     *             inside active window or recent interaction
     *
     * In practice this collapses the old "every neutral score → Ask" zone:
     * the user reported Ask was firing too often; now Ask is the last resort.
     */
    private fun decide(
        text:        String,
        score:       Float,
        acceptBar:   Float,
        reasons:     List<String>,
        signals:     AttentionSignals,
        inWindow:    Boolean
    ): AttentionDecision {
        val reason = reasons.joinToString(",")

        if (score >= acceptBar) {
            val reasonTag = when {
                inWindow -> "[ATTENTION_ACCEPTED_ACTIVE_WINDOW]"
                else     -> "[ATTENTION_ACCEPTED]"
            }
            Log.d(TAG, "$reasonTag score=${"%.2f".format(score)} bar=$acceptBar \"$text\"")
            return AttentionDecision.Accept(ConversationTarget.JARVIS, reason, score)
        }

        if (HumanConversationPatterns.strongMatch(text)) {
            Log.d(TAG, "[ATTENTION_IGNORED_HUMAN] \"$text\" score=${"%.2f".format(score)}")
            return AttentionDecision.Ignore(ConversationTarget.HUMAN, reason, score)
        }

        if (score <= IGNORE_THRESHOLD) {
            Log.d(TAG, "[ATTENTION_IGNORED_BACKGROUND] score=${"%.2f".format(score)} \"$text\"")
            return AttentionDecision.Ignore(ConversationTarget.HUMAN, reason, score)
        }

        // Middle zone: only Ask if true ambiguity — short utterance, signs
        // of conversational momentum, no strong human pattern.
        val tokenCount   = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val recentlySpoken = signals.lastJarvisResponseMs > 0L &&
            (signals.nowMs - signals.lastJarvisResponseMs) < RECENT_INTERACTION_MS
        val askEligible = (inWindow || recentlySpoken) &&
            tokenCount in 1..SHORT_PHRASE_MAX_TOKENS

        return if (askEligible) {
            Log.d(TAG, "[ATTENTION_ASK_TRUE_AMBIGUITY] score=${"%.2f".format(score)} " +
                "tokens=$tokenCount window=$inWindow recent=$recentlySpoken \"$text\"")
            AttentionDecision.AskIfForMe(ConversationTarget.UNKNOWN, reason, score)
        } else {
            Log.d(TAG, "[ATTENTION_IGNORED_BACKGROUND] score=${"%.2f".format(score)} " +
                "no_momentum tokens=$tokenCount \"$text\"")
            AttentionDecision.Ignore(ConversationTarget.UNKNOWN, reason, score)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Per-mode ACCEPT bars.  Calibrated for the new philosophy:
     *
     *  - DRIVING: very aggressive ACCEPT.  Cognitive load in a car is high,
     *    the user has fewer mistakes to recover from, and an Ask in the
     *    middle of driving is the worst UX of all.
     *  - NIGHT: still accepts clear commands; only the borderline cases
     *    need a slightly higher bar.
     *  - FOCUS: similar to NIGHT but with a touch more friction.
     *  - NORMAL: the default — the scoring above is tuned for this.
     */
    private fun acceptThresholdFor(mode: JarvisMode): Float = when (mode) {
        JarvisMode.DRIVING                 -> 0.3f
        JarvisMode.NIGHT                   -> 0.7f
        JarvisMode.FOCUS                   -> 0.8f
        else                               -> ACCEPT_THRESHOLD_DEFAULT
    }

    private val WAKE_WORD_RX = Regex(
        """\b(?:hey\s+)?jarvis\b|^jarviss?\s+""",
        RegexOption.IGNORE_CASE
    )
    private fun containsWakeWord(t: String) = WAKE_WORD_RX.containsMatchIn(t)

    private val BARGE_IN_RX = Regex(
        """^(?:stop|cancel|wait|no|hold\s+on|never\s+mind|nevermind|quiet|shut\s+up|enough)\b""",
        RegexOption.IGNORE_CASE
    )
    private fun isBargeInPhrase(t: String) = BARGE_IN_RX.containsMatchIn(t.trim())

    /**
     * Lightweight detector: any imperative verb followed (somewhere) by a
     * plausible object word.  Cheap and intentionally permissive — the
     * gate uses it as a +1.0 boost, not a hard match.
     */
    private val ACTION_VERB_RX = Regex(
        """\b(?:turn|switch|set|dim|brighten|open|launch|play|pause|stop|skip|send|text|call|ring|phone|email|message|whatsapp|wa|take|capture|remind|note|remember|cancel|unlock|lock|find|where|what|when|who|tell|show|read|start|begin|navigate|directions|search|google|look\s+up)\b""",
        RegexOption.IGNORE_CASE
    )
    private val OBJECT_HINT_RX = Regex(
        """\b(?:lights?|heating|fan|door|thermostat|kitchen|living\s+room|dining\s+room|bedroom|bathroom|hallway|porch|spotify|whatsapp|camera|messages|calendar|timer|alarm|reminder|photo|selfie|flashlight|volume|mike|cath|jamie|heidi|email|inbox|maps|directions|home|music|the\s+news|weather)\b""",
        RegexOption.IGNORE_CASE
    )
    private fun matchesActionVerbAndObject(t: String): Boolean =
        ACTION_VERB_RX.containsMatchIn(t) && OBJECT_HINT_RX.containsMatchIn(t)

    /** Action verb present anywhere — counts even when object is missing. */
    private val STRONG_VERB_ONLY_RX = Regex(
        """\b(?:send|text|message|whatsapp|wa|call|ring|phone|email|open|launch|play|pause|stop|skip|resume|next|previous|turn|switch|set|dim|lock|unlock|remind|navigate|take|capture|search|google)\b""",
        RegexOption.IGNORE_CASE
    )
    private fun matchesStrongActionVerb(t: String): Boolean =
        STRONG_VERB_ONLY_RX.containsMatchIn(t)

    /**
     * Canonical "this is a command" openers.  Phrases anchored at the
     * start of the utterance whose semantics leave no room for ambiguity:
     * the user is clearly issuing an instruction, not chatting with a
     * person in the room.  Matches → instant ACCEPT, no scoring needed.
     *
     * Add patterns conservatively — every entry here bypasses every other
     * signal including human-conversation patterns, so it must be
     * essentially impossible to say it accidentally to another human.
     */
    private val COMMAND_PATTERN_RX = Regex(
        """^(?:""" +
            // Single-word imperative verbs
            """(?:stop|pause|resume|skip|next|previous|continue|cancel|undo|silence|quiet|mute|unmute|repeat)[.!?]?$""" +
        """|""" +
            // Messaging / calling
            """send\s+(?:a\s+|an\s+|the\s+)?(?:text|message|sms|whatsapp|wa|email)\b""" +
        """|""" +
            """(?:text|message|whatsapp|wa|email|call|ring|phone)\s+\w+""" +
        """|""" +
            // App / device
            """(?:open|launch|start)\s+\w+""" +
        """|""" +
            // Media
            """(?:play|pause|resume|skip|stop|mute|unmute|set\s+volume|volume\s+up|volume\s+down)\b""" +
        """|""" +
            // Smart home
            """turn\s+(?:on|off|up|down)\b""" +
        """|""" +
            """(?:lock|unlock)\s+\w+""" +
        """|""" +
            // Reminders / timers / alarms
            """(?:remind\s+me|set\s+(?:a\s+)?(?:timer|alarm|reminder))\b""" +
        """|""" +
            // Calendar / what's on
            """what(?:'?s|\s+is)\s+(?:on\s+)?(?:my\s+)?(?:calendar|schedule|agenda|next|coming|happening)\b""" +
        """|""" +
            """what(?:'?s|\s+is)\s+(?:the\s+)?(?:time|date|weather|temperature)\b""" +
        """|""" +
            // Navigation
            """(?:navigate|directions?)\s+(?:to|home)\b""" +
        """|""" +
            """take\s+(?:me\s+)?(?:home|to)\b""" +
        """|""" +
            """where\s+(?:am\s+i|are\s+we)\b""" +
        """|""" +
            // Camera
            """take\s+(?:a\s+)?(?:photo|picture|selfie|snap|shot)\b""" +
        """|""" +
            // Search / web
            """(?:search|google|look\s+up|find\s+me)\b""" +
        """|""" +
            // Generic
            """(?:tell|show|read)\s+me\b""" +
        """)""",
        RegexOption.IGNORE_CASE
    )
    private fun matchesCommandPattern(t: String): Boolean =
        COMMAND_PATTERN_RX.containsMatchIn(t.trim())

    /**
     * Compare to the last TTS sentence with a coarse normalisation —
     * lower-case, strip punctuation, collapse whitespace.  A substantial
     * overlap (≥ 60% Jaccard on token sets) flags echo.
     */
    private fun isTtsEcho(transcript: String, lastTts: String?): Boolean {
        if (lastTts.isNullOrBlank()) return false
        val a = tokenise(transcript)
        val b = tokenise(lastTts)
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = a.intersect(b).size
        val union = a.union(b).size
        val jaccard = inter.toFloat() / union
        return jaccard >= 0.60f
    }
    private fun tokenise(s: String): Set<String> =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
}
