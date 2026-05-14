package com.jarvis.assistant.speaker.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPermissionPolicyTest {

    // ── Classifier: every relevant tool maps to the right risk class ────────

    @Test
    fun `LOW_RISK tools classify correctly`() {
        listOf(
            "flashlight", "volume_control", "media_control", "set_alarm", "set_timer",
            "open_app", "weather", "time", "battery", "where_am_i", "smart_home",
        ).forEach {
            assertEquals("tool=$it should be LOW_RISK",
                CommandPermissionPolicy.RiskClass.LOW_RISK,
                CommandPermissionPolicy.classify(it))
        }
    }

    @Test
    fun `MEDIUM_RISK tools classify correctly`() {
        listOf(
            "calendar", "call_contact", "send_sms", "whatsapp_message",
            "read_notifications", "directions", "navigate",
        ).forEach {
            assertEquals("tool=$it should be MEDIUM_RISK",
                CommandPermissionPolicy.RiskClass.MEDIUM_RISK,
                CommandPermissionPolicy.classify(it))
        }
    }

    @Test
    fun `HIGH_RISK tools classify correctly`() {
        listOf("send_email", "delete_routine", "save_routine", "audio_recording")
            .forEach {
                assertEquals("tool=$it should be HIGH_RISK",
                    CommandPermissionPolicy.RiskClass.HIGH_RISK,
                    CommandPermissionPolicy.classify(it))
            }
    }

    @Test
    fun `unknown tools default to LOW_RISK (no fail-safe lockout)`() {
        // The PREVIOUS behaviour was to fail-safe to PERSONAL and block
        // unknown tools.  That's exactly the regression we're fixing —
        // any future tool that slips through the registry stays usable.
        assertEquals(CommandPermissionPolicy.RiskClass.LOW_RISK,
            CommandPermissionPolicy.classify("brand_new_tool_xyz"))
    }

    // ── Owner lockout regression — LOW_RISK always allowed ──────────────────

    @Test
    fun `LOW_RISK is allowed in every trust state including VOICE_MISMATCH`() {
        for (trust in VoiceTrustState.values()) {
            val d = CommandPermissionPolicy.evaluate("flashlight", trust)
            assertTrue("LOW_RISK should be allowed in $trust, got $d",
                d is CommandPermissionPolicy.Decision.Allow)
        }
    }

    @Test
    fun `LOW_RISK is allowed even in strict mode`() {
        for (trust in VoiceTrustState.values()) {
            val d = CommandPermissionPolicy.evaluate(
                "volume_control", trust, strictMode = true,
            )
            assertTrue("LOW_RISK volume_control in strict + $trust should still allow",
                d is CommandPermissionPolicy.Decision.Allow)
        }
    }

    // ── MEDIUM_RISK behaves correctly across trust states ───────────────────

    @Test
    fun `MEDIUM_RISK allowed in OWNER_ASSUMED with safe defaults`() {
        val d = CommandPermissionPolicy.evaluate(
            "whatsapp_message", VoiceTrustState.OWNER_ASSUMED,
        )
        assertTrue(d is CommandPermissionPolicy.Decision.Allow)
    }

    @Test
    fun `MEDIUM_RISK allowed in OWNER_TRUSTED and VOICE_MATCHED`() {
        listOf(VoiceTrustState.OWNER_TRUSTED, VoiceTrustState.VOICE_MATCHED).forEach {
            val d = CommandPermissionPolicy.evaluate("send_sms", it)
            assertTrue("MEDIUM in $it should allow, got $d",
                d is CommandPermissionPolicy.Decision.Allow)
        }
    }

    @Test
    fun `MEDIUM_RISK asks reauth in strict mode + OWNER_ASSUMED`() {
        val d = CommandPermissionPolicy.evaluate(
            "send_sms", VoiceTrustState.OWNER_ASSUMED, strictMode = true,
        )
        assertTrue("expected ReauthRequired, got $d",
            d is CommandPermissionPolicy.Decision.ReauthRequired)
    }

    @Test
    fun `MEDIUM_RISK asks reauth in VOICE_UNKNOWN by default`() {
        val d = CommandPermissionPolicy.evaluate(
            "calendar", VoiceTrustState.VOICE_UNKNOWN,
        )
        assertTrue(d is CommandPermissionPolicy.Decision.ReauthRequired)
    }

    @Test
    fun `MEDIUM_RISK allows VOICE_MISMATCH in non-strict mode`() {
        // The user may have multiple household speakers; we don't want
        // a mismatched-but-genuine secondary speaker locked out unless
        // strict mode is on.
        val d = CommandPermissionPolicy.evaluate(
            "calendar", VoiceTrustState.VOICE_MISMATCH,
        )
        assertTrue(d is CommandPermissionPolicy.Decision.Allow)
    }

    @Test
    fun `MEDIUM_RISK denies VOICE_MISMATCH in strict mode`() {
        val d = CommandPermissionPolicy.evaluate(
            "calendar", VoiceTrustState.VOICE_MISMATCH, strictMode = true,
        )
        assertTrue("expected Deny, got $d",
            d is CommandPermissionPolicy.Decision.Deny)
    }

    // ── HIGH_RISK ────────────────────────────────────────────────────────────

    @Test
    fun `HIGH_RISK allowed for VOICE_MATCHED and OWNER_TRUSTED`() {
        listOf(VoiceTrustState.VOICE_MATCHED, VoiceTrustState.OWNER_TRUSTED).forEach {
            val d = CommandPermissionPolicy.evaluate("send_email", it)
            assertTrue("HIGH in $it should allow, got $d",
                d is CommandPermissionPolicy.Decision.Allow)
        }
    }

    @Test
    fun `HIGH_RISK asks reauth in OWNER_ASSUMED by default`() {
        val d = CommandPermissionPolicy.evaluate(
            "send_email", VoiceTrustState.OWNER_ASSUMED,
        )
        assertTrue(d is CommandPermissionPolicy.Decision.ReauthRequired)
    }

    @Test
    fun `HIGH_RISK denies in OWNER_ASSUMED when requireVoiceMatchForSensitive is on`() {
        val d = CommandPermissionPolicy.evaluate(
            "send_email", VoiceTrustState.OWNER_ASSUMED,
            requireVoiceMatchForSensitive = true,
        )
        assertTrue("expected Deny, got $d",
            d is CommandPermissionPolicy.Decision.Deny)
    }

    // ── Boolean shortcut ────────────────────────────────────────────────────

    @Test
    fun `isAllowed mirrors evaluate`() {
        assertTrue(CommandPermissionPolicy.isAllowed("volume_control", VoiceTrustState.OWNER_ASSUMED))
        assertFalse(CommandPermissionPolicy.isAllowed("send_email", VoiceTrustState.OWNER_ASSUMED))
    }

    // ── VoiceTrustState.isOwnerLike helper ─────────────────────────────────

    @Test
    fun `isOwnerLike covers OWNER_* and VOICE_MATCHED only`() {
        assertTrue(VoiceTrustState.OWNER_ASSUMED.isOwnerLike)
        assertTrue(VoiceTrustState.OWNER_TRUSTED.isOwnerLike)
        assertTrue(VoiceTrustState.VOICE_MATCHED.isOwnerLike)
        assertFalse(VoiceTrustState.VOICE_UNKNOWN.isOwnerLike)
        assertFalse(VoiceTrustState.VOICE_MISMATCH.isOwnerLike)
        assertFalse(VoiceTrustState.REAUTH_REQUIRED.isOwnerLike)
    }
}
