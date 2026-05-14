package com.jarvis.assistant.speaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerPermissionPolicyRegressionTest {

    private fun result(band: SpeakerIdentityResult.ConfidenceBand): SpeakerIdentityResult =
        SpeakerIdentityResult(
            confidence  = 0.5f,
            personId    = null,
            displayName = null,
            band        = band,
        )

    // ── Owner-lockout regression: LOW_RISK / PUBLIC always allowed ─────────

    @Test
    fun `volume_control allowed for UNKNOWN speaker by default`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.UNKNOWN),
            "volume_control",
        ))
    }

    @Test
    fun `flashlight allowed for LOW_CONFIDENCE speaker by default`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS),
            "flashlight",
        ))
    }

    @Test
    fun `open_app allowed for UNKNOWN by default`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.UNKNOWN),
            "open_app",
        ))
    }

    // ── MEDIUM_RISK no longer hard-blocked by default ──────────────────────

    @Test
    fun `whatsapp allowed for UNKNOWN by default (was the lockout bug)`() {
        // This was the exact path that locked the user out after app
        // restart: a saved profile that hadn't loaded yet → UNKNOWN
        // band → personal action → Denied.  Safe defaults must now
        // allow this.
        assertTrue(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.UNKNOWN),
            "whatsapp_message",
        ))
    }

    @Test
    fun `sms allowed for LOW_CONFIDENCE by default`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.LOW_CONFIDENCE_OR_AMBIGUOUS),
            "send_sms",
        ))
    }

    @Test
    fun `calendar allowed for UNKNOWN by default`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.UNKNOWN),
            "calendar",
        ))
    }

    @Test
    fun `call_contact allowed for UNKNOWN by default`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.UNKNOWN),
            "call_contact",
        ))
    }

    // ── Strict mode still gates HIGH_RISK ──────────────────────────────────

    @Test
    fun `send_email asks reauth for UNKNOWN in default mode`() {
        // HIGH_RISK in OWNER_ASSUMED → ReauthRequired, which the
        // legacy contract surfaces as not-allowed with the prompt as
        // the deny reason.
        assertFalse(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.UNKNOWN),
            "send_email",
        ))
    }

    @Test
    fun `strict mode blocks MEDIUM_RISK only when voice unknown`() {
        // OWNER_ASSUMED + strict → reauth.  The boolean shortcut
        // returns false in either reauth or deny.
        assertFalse(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.UNKNOWN),
            "send_sms",
            strictMode = true,
        ))
    }

    @Test
    fun `HIGH_CONFIDENCE always passes regardless of mode`() {
        assertTrue(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH),
            "send_email",
        ))
        assertTrue(SpeakerPermissionPolicy.isAllowed(
            result(SpeakerIdentityResult.ConfidenceBand.HIGH_CONFIDENCE_MATCH),
            "send_email",
            strictMode = true,
        ))
    }
}
