package com.jarvis.assistant.remote.openclaw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawSettingsTest {

    private fun settings(
        enabled: Boolean = true,
        host: String     = "100.10.20.30"
    ) = OpenClawSettings(
        enabled   = enabled,
        host      = host,
        port      = 8765,
        secure    = false,
        authToken = "",
        timeoutMs = 30_000L
    )

    // ── isFullyConfigured ──────────────────────────────────────────────────

    @Test
    fun `enabled with non-blank host is fully configured`() {
        assertTrue(settings(enabled = true, host = "100.10.20.30").isFullyConfigured)
    }

    @Test
    fun `disabled with non-blank host is not fully configured`() {
        assertFalse(settings(enabled = false, host = "100.10.20.30").isFullyConfigured)
    }

    @Test
    fun `enabled with blank host is not fully configured`() {
        assertFalse(settings(enabled = true, host = "").isFullyConfigured)
        assertFalse(settings(enabled = true, host = "   ").isFullyConfigured)
    }

    @Test
    fun `disabled with blank host is not fully configured`() {
        assertFalse(settings(enabled = false, host = "").isFullyConfigured)
    }

    // ── DEFAULT_PORT / DEFAULT_TIMEOUT_MS ─────────────────────────────────

    @Test
    fun `default port is 8765`() {
        assert(OpenClawSettings.DEFAULT_PORT == 8765)
    }

    @Test
    fun `default timeout is 30 seconds`() {
        assert(OpenClawSettings.DEFAULT_TIMEOUT_MS == 30_000L)
    }

    // ── RouteType values ──────────────────────────────────────────────────

    @Test
    fun `all three route types exist`() {
        val values = RouteType.values()
        assertTrue(RouteType.LOCAL_FAST  in values)
        assertTrue(RouteType.REMOTE_FAST in values)
        assertTrue(RouteType.REMOTE_LONG in values)
    }

    // ── OpenClawConnectionStatus display labels ────────────────────────────

    @Test
    fun `CONNECTED has non-blank display label`() {
        assertTrue(OpenClawConnectionStatus.CONNECTED.displayLabel.isNotBlank())
    }

    @Test
    fun `all statuses have non-blank display labels`() {
        OpenClawConnectionStatus.values().forEach { status ->
            assertTrue(
                "Status $status has blank displayLabel",
                status.displayLabel.isNotBlank()
            )
        }
    }
}
