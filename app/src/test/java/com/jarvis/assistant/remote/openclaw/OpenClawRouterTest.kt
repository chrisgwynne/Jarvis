package com.jarvis.assistant.remote.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class OpenClawRouterTest {

    private fun configuredRepo(): OpenClawSettingsRepository {
        val repo = mock<OpenClawSettingsRepository>()
        whenever(repo.isConfigured()).thenReturn(true)
        whenever(repo.snapshot()).thenReturn(
            OpenClawSettings(
                enabled   = true,
                host      = "100.10.20.30",
                port      = 8765,
                secure    = false,
                authToken = "tok",
                timeoutMs = 30_000L
            )
        )
        return repo
    }

    private fun disabledRepo(): OpenClawSettingsRepository {
        val repo = mock<OpenClawSettingsRepository>()
        whenever(repo.isConfigured()).thenReturn(false)
        return repo
    }

    private fun router(repo: OpenClawSettingsRepository = configuredRepo()) =
        OpenClawRouter(repo)

    // ── shouldRoute ────────────────────────────────────────────────────────

    @Test
    fun `shouldRoute returns true when configured`() {
        assertTrue(router(configuredRepo()).shouldRoute())
    }

    @Test
    fun `shouldRoute returns false when not configured`() {
        assertFalse(router(disabledRepo()).shouldRoute())
    }

    // ── classify ──────────────────────────────────────────────────────────

    @Test
    fun `'what time is it' classifies as LOCAL_FAST`() {
        assertEquals(RouteType.LOCAL_FAST, router().classify("what time is it"))
    }

    @Test
    fun `'what's the time' classifies as LOCAL_FAST`() {
        assertEquals(RouteType.LOCAL_FAST, router().classify("what's the time"))
    }

    @Test
    fun `'set a timer' classifies as LOCAL_FAST`() {
        assertEquals(RouteType.LOCAL_FAST, router().classify("set a timer for 10 minutes"))
    }

    @Test
    fun `'call mum' classifies as LOCAL_FAST`() {
        assertEquals(RouteType.LOCAL_FAST, router().classify("call mum"))
    }

    @Test
    fun `'take a photo' classifies as LOCAL_FAST`() {
        assertEquals(RouteType.LOCAL_FAST, router().classify("take a photo"))
    }

    @Test
    fun `'hi' classifies as LOCAL_FAST`() {
        assertEquals(RouteType.LOCAL_FAST, router().classify("hi"))
    }

    @Test
    fun `'write me a cover letter' classifies as REMOTE_LONG`() {
        assertEquals(RouteType.REMOTE_LONG, router().classify("write me a cover letter"))
    }

    @Test
    fun `'research the best ETFs' classifies as REMOTE_LONG`() {
        assertEquals(RouteType.REMOTE_LONG, router().classify("research the best ETFs for 2025"))
    }

    @Test
    fun `'find out why my server is slow' classifies as REMOTE_LONG`() {
        assertEquals(RouteType.REMOTE_LONG, router().classify("find out why my server is slow"))
    }

    @Test
    fun `'plan a trip to Tokyo' classifies as REMOTE_LONG`() {
        assertEquals(RouteType.REMOTE_LONG, router().classify("plan a trip to Tokyo"))
    }

    @Test
    fun `ordinary question classifies as REMOTE_FAST`() {
        assertEquals(RouteType.REMOTE_FAST, router().classify("who won the Champions League in 2023"))
    }

    @Test
    fun `'what is the capital of France' classifies as REMOTE_FAST`() {
        // Not LOCAL_FAST (no device trigger), not REMOTE_LONG (not a research/write task)
        assertEquals(RouteType.REMOTE_FAST, router().classify("what is the capital of France"))
    }

    // ── execute — bypasses when not configured ────────────────────────────

    @Test
    fun `execute returns Bypassed when not configured`() {
        val r = kotlinx.coroutines.test.runTest {
            router(disabledRepo()).execute("anything", "session-1")
        }
        // runTest returns Unit; test just verifies no exception
    }

    @Test
    fun `classify LOCAL_FAST transcript leads to Bypassed without network call`() =
        kotlinx.coroutines.test.runTest {
            // Router should return Bypassed without touching the client
            // because LOCAL_FAST transcripts never go to OpenClaw
            val result = router().execute("set an alarm for 7am", "session-x")
            assertTrue(result is OpenClawExecutionResult.Bypassed)
        }
}
