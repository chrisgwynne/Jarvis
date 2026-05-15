package com.jarvis.assistant.trust

import com.jarvis.assistant.speaker.trust.VoiceTrustState
import com.jarvis.assistant.tools.framework.ToolInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for [AutonomyEngine].
 *
 * Every test wires a fresh [AutonomyEngine] with stubbed [AutonomySettingsRepository]
 * and [LearnedTrustStore] so no Android context is required.
 */
class AutonomyEngineTest {

    private lateinit var settingsRepo: AutonomySettingsRepository
    private lateinit var learnedStore: LearnedTrustStore
    private lateinit var engine: AutonomyEngine

    private fun buildEngine(
        settings: AutonomySettings = AutonomySettings(),
        skipConfirmation: Boolean = false,
        requiresConfirmation: Boolean = false,
    ): AutonomyEngine {
        settingsRepo = mock()
        learnedStore  = mock()
        whenever(settingsRepo.snapshot()).thenReturn(settings)
        whenever(learnedStore.shouldSkipConfirmation(any())).thenReturn(skipConfirmation)
        whenever(learnedStore.requiresConfirmation(any())).thenReturn(requiresConfirmation)
        return AutonomyEngine(settingsRepo, learnedStore)
    }

    private fun input(vararg pairs: Pair<String, String>) =
        ToolInput(transcript = "", params = mapOf(*pairs))

    // ── LOW_RISK always auto-approves ─────────────────────────────────────────

    @Test
    fun `volume control is LOW risk and auto-approves`() {
        val e = buildEngine()
        val result = e.evaluate("volume_control", input(), TrustContext())
        assertTrue(result is AutonomyDecision.AutoApprove)
    }

    @Test
    fun `set timer auto-approves without confirmation`() {
        val e = buildEngine()
        val result = e.evaluate("set_timer", input(), TrustContext())
        assertTrue(result is AutonomyDecision.AutoApprove)
    }

    @Test
    fun `navigation auto-approves`() {
        val e = buildEngine()
        val result = e.evaluate("navigate", input(), TrustContext())
        assertTrue(result is AutonomyDecision.AutoApprove)
    }

    @Test
    fun `flashlight auto-approves`() {
        val e = buildEngine()
        val result = e.evaluate("flashlight", input(), TrustContext())
        assertTrue(result is AutonomyDecision.AutoApprove)
    }

    // ── CRITICAL always blocks ────────────────────────────────────────────────

    @Test
    fun `factory reset is CRITICAL and blocks`() {
        val e = buildEngine()
        val result = e.evaluate("factory_reset", input(), TrustContext())
        assertTrue(result is AutonomyDecision.Block)
    }

    @Test
    fun `install app is CRITICAL and blocks`() {
        val e = buildEngine()
        val result = e.evaluate("install_app", input(), TrustContext())
        assertTrue(result is AutonomyDecision.Block)
    }

    // ── LOCKSCREEN blocks sensitive tools ────────────────────────────────────

    @Test
    fun `send SMS blocked when screen locked and restrictions enabled`() {
        val e = buildEngine(settings = AutonomySettings(lockscreenRestrictions = true))
        val result = e.evaluate(
            "send_sms", input(),
            TrustContext(deviceLocked = true)
        )
        assertTrue(result is AutonomyDecision.Block)
    }

    @Test
    fun `send SMS allowed when screen locked but restrictions disabled`() {
        val e = buildEngine(settings = AutonomySettings(lockscreenRestrictions = false))
        val result = e.evaluate(
            "send_sms", input("contact" to "Mike", "body" to "Hi"),
            TrustContext(deviceLocked = true)
        )
        // Should NOT be blocked by lockscreen — might still confirm due to MEDIUM risk
        assertTrue(result !is AutonomyDecision.Block)
    }

    // ── Unknown speaker + affects others ────────────────────────────────────

    @Test
    fun `unknown speaker requesting call → confirm`() {
        val e = buildEngine()
        val result = e.evaluate(
            "call_contact", input("contact" to "Sarah"),
            TrustContext(voiceTrust = VoiceTrustState.VOICE_MISMATCH)
        )
        assertTrue(result is AutonomyDecision.Confirm)
    }

    // ── Per-category settings overrides ─────────────────────────────────────

    @Test
    fun `requireConfirmForMessages forces confirm on send_sms even with high trust`() {
        val e = buildEngine(settings = AutonomySettings(requireConfirmForMessages = true))
        val result = e.evaluate(
            "send_sms", input("contact" to "Mum"),
            TrustContext(voiceTrust = VoiceTrustState.VOICE_MATCHED)
        )
        assertTrue(result is AutonomyDecision.Confirm)
    }

    @Test
    fun `requireConfirmForCalls forces confirm on call_contact`() {
        val e = buildEngine(settings = AutonomySettings(requireConfirmForCalls = true))
        val result = e.evaluate(
            "call_contact", input("contact" to "Dad"),
            TrustContext(voiceTrust = VoiceTrustState.VOICE_MATCHED)
        )
        assertTrue(result is AutonomyDecision.Confirm)
    }

    // ── Car mode ─────────────────────────────────────────────────────────────

    @Test
    fun `car mode auto-approves MEDIUM risk when car mode setting is on`() {
        val e = buildEngine(settings = AutonomySettings(carModeAutonomy = true))
        // send_sms is MEDIUM risk
        val result = e.evaluate(
            "send_sms", input("contact" to "Mum", "body" to "On my way"),
            TrustContext(isCarMode = true)
        )
        assertTrue(result is AutonomyDecision.AutoApprove)
    }

    @Test
    fun `car mode disabled still confirms MEDIUM risk`() {
        val e = buildEngine(
            settings = AutonomySettings(
                carModeAutonomy = false,
                preset = AutonomyPreset.CONSERVATIVE,
            )
        )
        val result = e.evaluate(
            "send_sms", input("contact" to "Tom"),
            TrustContext(isCarMode = true)
        )
        assertTrue(result is AutonomyDecision.Confirm)
    }

    // ── Learned patterns ─────────────────────────────────────────────────────

    @Test
    fun `learned skip confirmation auto-approves MEDIUM risk tool`() {
        val e = buildEngine(
            settings = AutonomySettings(preset = AutonomyPreset.BALANCED),
            skipConfirmation = true,
        )
        // With low trust normally would confirm, but learned skip overrides
        val result = e.evaluate(
            "send_sms", input("contact" to "Bob"),
            TrustContext()  // baseline / no strong signals
        )
        assertTrue(result is AutonomyDecision.AutoApprove)
    }

    @Test
    fun `user always_ask overrides learned skip`() {
        val e = buildEngine(
            settings         = AutonomySettings(preset = AutonomyPreset.BALANCED),
            skipConfirmation = true,   // would skip, but...
            requiresConfirmation = true, // ...ALWAYS_ASK wins
        )
        val result = e.evaluate(
            "send_sms", input("contact" to "Bob"),
            TrustContext(voiceTrust = VoiceTrustState.VOICE_MATCHED)
        )
        assertTrue(result is AutonomyDecision.Confirm)
    }

    // ── Trust threshold (BALANCED / JARVIS_STYLE) ────────────────────────────

    @Test
    fun `high trust voice match auto-approves MEDIUM risk on BALANCED preset`() {
        val e = buildEngine(settings = AutonomySettings(preset = AutonomyPreset.BALANCED))
        val result = e.evaluate(
            "send_sms", input("contact" to "Kate"),
            TrustContext(
                voiceTrust   = VoiceTrustState.VOICE_MATCHED,
                deviceLocked = false,
                sessionActive = true,
                recentSuccess = true,
            )
        )
        assertTrue(result is AutonomyDecision.AutoApprove)
    }

    @Test
    fun `CONSERVATIVE preset always confirms MEDIUM risk despite high trust`() {
        val e = buildEngine(settings = AutonomySettings(preset = AutonomyPreset.CONSERVATIVE))
        val result = e.evaluate(
            "send_sms", input("contact" to "Kate"),
            TrustContext(voiceTrust = VoiceTrustState.VOICE_MATCHED)
        )
        assertTrue(result is AutonomyDecision.Confirm)
    }

    @Test
    fun `HIGH risk always confirms regardless of trust`() {
        val e = buildEngine(settings = AutonomySettings(preset = AutonomyPreset.JARVIS_STYLE))
        // smart_home lock → HIGH_RISK
        val result = e.evaluate(
            "smart_home", input("action" to "lock", "entity_name" to "front door"),
            TrustContext(voiceTrust = VoiceTrustState.VOICE_MATCHED)
        )
        assertTrue(result is AutonomyDecision.Confirm)
    }

    // ── Confirmation prompt content ───────────────────────────────────────────

    @Test
    fun `confirm prompt for send_sms includes contact name`() {
        val e = buildEngine()
        val result = e.evaluate(
            "send_sms", input("contact" to "Mike"),
            TrustContext()
        ) as? AutonomyDecision.Confirm
        assertTrue(result?.prompt?.contains("Mike", ignoreCase = true) == true)
    }

    @Test
    fun `confirm prompt for call includes contact name`() {
        val e = buildEngine()
        val result = e.evaluate(
            "call_contact", input("contact" to "Mum"),
            TrustContext()
        ) as? AutonomyDecision.Confirm
        assertTrue(result?.prompt?.contains("Mum", ignoreCase = true) == true)
    }
}
