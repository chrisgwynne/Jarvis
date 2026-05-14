package com.jarvis.assistant.voice.attention

import com.jarvis.assistant.context.ActivityMode
import com.jarvis.assistant.context.Presence
import com.jarvis.assistant.context.TimePhase
import com.jarvis.assistant.modes.JarvisMode
import com.jarvis.assistant.voice.VoiceFeatureFlags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the rebalanced AttentionGate (Tier-D Ask-reduction work).
 *
 * Philosophy under test:
 *   1. command-like phrases → ACCEPT (never Ask)
 *   2. human-conversation phrases → IGNORE (never Ask)
 *   3. weak/neutral utterances outside any conversation window → IGNORE
 *   4. Ask remains only for short, ambiguous, in-momentum phrases
 */
class AttentionGateAggressiveAcceptTest {

    private lateinit var gate: AttentionGate

    @Before fun setUp() {
        // Ensure the gate's own feature flag is on.
        VoiceFeatureFlags.setOverride(VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED, true)
        gate = AttentionGate()
    }

    @After fun tearDown() {
        VoiceFeatureFlags.clearOverride(VoiceFeatureFlags.Flag.ATTENTION_GATE_ENABLED)
    }

    private fun signals(
        text:                    String,
        localCommandMatch:       Boolean = false,
        localCommandToolName:    String? = null,
        transcriptScore:         Int     = 0,
        mode:                    JarvisMode = JarvisMode.NORMAL,
        ttsActive:               Boolean = false,
        inCall:                  Boolean = false,
        lastJarvisResponseMs:    Long = 0L,
        nowMs:                   Long = 0L
    ) = AttentionSignals(
        transcript                 = text,
        sttConfidence              = 0.9f,
        mode                       = mode,
        activeWindowUntilMs        = gate.activeWindowUntilMs,
        lastJarvisResponseMs       = lastJarvisResponseMs,
        nowMs                      = if (nowMs > 0L) nowMs else System.currentTimeMillis(),
        isInCall                   = inCall,
        isMediaPlaying             = false,
        isHeadsetConnected         = false,
        screenOn                   = true,
        isTtsActive                = ttsActive,
        lastTtsText                = null,
        localCommandMatch          = localCommandMatch,
        localCommandToolName       = localCommandToolName,
        transcriptCorrectorScore   = transcriptScore,
        looksLikeNotificationText  = false
    )

    private fun assertAccept(text: String, vararg extras: (AttentionSignals.() -> Unit) = arrayOf()) {
        val s = signals(text)
        val r = gate.gate(s)
        assertTrue("Expected ACCEPT for \"$text\", got $r",
            r is AttentionDecision.Accept)
    }
    private fun assertIgnore(text: String) {
        val r = gate.gate(signals(text))
        assertTrue("Expected IGNORE for \"$text\", got $r",
            r is AttentionDecision.Ignore)
    }

    // ── 1. Command-like phrases ALL ACCEPT ───────────────────────────────────

    @Test fun `command - whatsapp to mike`() = assertAccept("send a whatsapp to mike saying hello")
    @Test fun `command - call cath`()         = assertAccept("call Cath")
    @Test fun `command - open spotify`()      = assertAccept("open Spotify")
    @Test fun `command - turn on kitchen lights`() = assertAccept("turn on kitchen lights")
    @Test fun `command - play music`()        = assertAccept("play music")
    @Test fun `command - pause`()             = assertAccept("pause")
    @Test fun `command - stop`()              = assertAccept("stop")
    @Test fun `command - navigate home`()     = assertAccept("navigate home")
    @Test fun `command - set timer 10 min`()  = assertAccept("set a timer for 10 minutes")
    @Test fun `command - what's on my calendar`() = assertAccept("what's on my calendar")
    @Test fun `command - take a selfie`()     = assertAccept("take a selfie")
    @Test fun `command - take a photo`()      = assertAccept("take a photo")
    @Test fun `command - remind me to call mum`() = assertAccept("remind me to call mum")
    @Test fun `command - lock front door`()   = assertAccept("lock front door")
    @Test fun `command - tell me the weather`() = assertAccept("tell me the weather")
    @Test fun `command - what time is it`()   = assertAccept("what's the time")

    // ── Tool match short-circuit ─────────────────────────────────────────────

    @Test fun `tool match always accepts even when score zero`() {
        val r = gate.gate(signals("foo bar baz", localCommandMatch = true,
            localCommandToolName = "whatsapp_message"))
        assertTrue("Expected ACCEPT for tool-matched utterance, got $r",
            r is AttentionDecision.Accept)
    }

    // ── Active window follow-ups ─────────────────────────────────────────────

    @Test fun `follow-up - and the hallway`() {
        // Simulate a freshly-extended active window.
        gate.extendActiveWindow()
        val r = gate.gate(signals("and the hallway"))
        assertTrue("Expected ACCEPT for follow-up in active window, got $r",
            r is AttentionDecision.Accept)
    }

    @Test fun `follow-up - actually Cath`() {
        gate.extendActiveWindow()
        val r = gate.gate(signals("actually Cath"))
        assertTrue("Expected ACCEPT for follow-up in active window, got $r",
            r is AttentionDecision.Accept)
    }

    // ── 2. Human conversation ALWAYS IGNORE (NEVER Ask) ──────────────────────

    @Test fun `human - what do you want for tea`() = assertIgnore("what do you want for tea")
    @Test fun `human - did you feed the dog`() {
        // Not in strong patterns by default; should still IGNORE outside any
        // conversation window because the utterance has no command structure.
        gate.closeActiveWindow()
        val r = gate.gate(signals("did you feed the dog"))
        assertTrue("Expected IGNORE / not Ask, got $r",
            r is AttentionDecision.Ignore)
    }
    @Test fun `human - I'll be there in a minute`() = assertIgnore("I'll be there in a minute")
    @Test fun `human - how was work`() {
        gate.closeActiveWindow()
        val r = gate.gate(signals("how was work"))
        assertTrue("Expected IGNORE, got $r", r !is AttentionDecision.AskIfForMe)
    }
    @Test fun `human - do you want a drink`() {
        gate.closeActiveWindow()
        val r = gate.gate(signals("do you want a drink"))
        assertTrue("Expected IGNORE, got $r", r !is AttentionDecision.AskIfForMe)
    }

    // ── 3. True ambiguity MAY Ask, but only inside an active window ──────────

    @Test fun `ambiguous - do that - inside window may Ask`() {
        gate.extendActiveWindow()
        val r = gate.gate(signals("do that"))
        assertTrue("Expected Ask or Accept, got $r",
            r is AttentionDecision.AskIfForMe || r is AttentionDecision.Accept)
    }

    @Test fun `ambiguous - do that - outside window IGNORE not Ask`() {
        gate.closeActiveWindow()
        val r = gate.gate(signals("do that"))
        assertTrue("Expected IGNORE (no momentum), got $r",
            r is AttentionDecision.Ignore)
    }

    // ── 4. Driving mode is the most permissive ───────────────────────────────

    @Test fun `driving mode accepts weak signals more readily`() {
        gate.closeActiveWindow()
        // A bare action verb with no object — would be borderline in NORMAL,
        // should clearly ACCEPT in DRIVING.
        val r = gate.gate(signals("call", mode = JarvisMode.DRIVING))
        assertTrue("Expected ACCEPT in DRIVING for bare verb, got $r",
            r is AttentionDecision.Accept)
    }

    @Test fun `night mode is balanced, not punitive`() {
        // Clear command still accepts at night.
        val r = gate.gate(signals("turn off the lights", mode = JarvisMode.NIGHT))
        assertTrue("Expected ACCEPT in NIGHT for clear command, got $r",
            r is AttentionDecision.Accept)
    }
}
