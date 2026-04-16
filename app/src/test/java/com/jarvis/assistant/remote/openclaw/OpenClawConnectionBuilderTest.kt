package com.jarvis.assistant.remote.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawConnectionBuilderTest {

    private fun settings(
        host: String    = "100.10.20.30",
        port: Int       = 8765,
        secure: Boolean = false
    ) = OpenClawSettings(
        enabled   = true,
        host      = host,
        port      = port,
        secure    = secure,
        authToken = "tok",
        timeoutMs = 30_000L
    )

    // ── WebSocket URL ─────────────────────────────────────────────────────

    @Test
    fun `insecure ws URL uses ws scheme`() {
        val url = OpenClawConnectionBuilder.buildWsEndpoint(settings(secure = false))
        assertTrue("Expected ws://", url.startsWith("ws://"))
    }

    @Test
    fun `secure ws URL uses wss scheme`() {
        val url = OpenClawConnectionBuilder.buildWsEndpoint(settings(secure = true))
        assertTrue("Expected wss://", url.startsWith("wss://"))
    }

    @Test
    fun `ws URL contains host port and gateway path`() {
        val url = OpenClawConnectionBuilder.buildWsEndpoint(settings(host = "100.10.20.30", port = 9000))
        assertEquals("ws://100.10.20.30:9000/gateway", url)
    }

    @Test
    fun `trailing slash in host is stripped from ws URL`() {
        val url = OpenClawConnectionBuilder.buildWsEndpoint(settings(host = "mypc.example.com/"))
        assertFalse("Trailing slash must be stripped", url.contains("//gateway"))
        assertTrue(url.endsWith("/gateway"))
    }

    // ── Health URL ────────────────────────────────────────────────────────

    @Test
    fun `insecure health URL uses http scheme`() {
        val url = OpenClawConnectionBuilder.buildHealthEndpoint(settings(secure = false))
        assertTrue("Expected http://", url.startsWith("http://"))
    }

    @Test
    fun `secure health URL uses https scheme`() {
        val url = OpenClawConnectionBuilder.buildHealthEndpoint(settings(secure = true))
        assertTrue("Expected https://", url.startsWith("https://"))
    }

    @Test
    fun `health URL contains host port and health path`() {
        val url = OpenClawConnectionBuilder.buildHealthEndpoint(settings(host = "192.168.1.5", port = 8765))
        assertEquals("http://192.168.1.5:8765/health", url)
    }

    // ── Host validation ───────────────────────────────────────────────────

    @Test
    fun `valid IP host passes validation`() {
        assertTrue(OpenClawConnectionBuilder.validateHost("100.10.20.30"))
    }

    @Test
    fun `valid hostname passes validation`() {
        assertTrue(OpenClawConnectionBuilder.validateHost("mypc.tailnet.ts.net"))
    }

    @Test
    fun `blank host fails validation`() {
        assertFalse(OpenClawConnectionBuilder.validateHost(""))
        assertFalse(OpenClawConnectionBuilder.validateHost("   "))
    }

    @Test
    fun `host with space fails validation`() {
        assertFalse(OpenClawConnectionBuilder.validateHost("my host.local"))
    }

    @Test
    fun `protocol-prefixed host fails validation`() {
        assertFalse(OpenClawConnectionBuilder.validateHost("http://myhost"))
        assertFalse(OpenClawConnectionBuilder.validateHost("ws://myhost"))
    }
}
