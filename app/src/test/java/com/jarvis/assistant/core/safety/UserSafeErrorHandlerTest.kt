package com.jarvis.assistant.core.safety

import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmRateLimitedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UserSafeErrorHandlerTest {

    @Test
    fun `LlmRateLimitedException maps to friendly limit message`() {
        val r = UserSafeErrorHandler.handle(
            LlmRateLimitedException("HTTP 429 too many requests"),
            UserSafeErrorHandler.Area.LLM,
        )
        assertEquals(UserSafeErrorHandler.Friendly.LLM_RATE_LIMITED, r.friendlyText)
        assertEquals(UserSafeErrorHandler.Severity.TRANSIENT, r.severity)
        assertEquals("rate_limit", r.category)
    }

    @Test
    fun `generic LLM error maps to LLM generic copy`() {
        val r = UserSafeErrorHandler.handle(
            LlmException("HTTP 503 service unavailable"),
            UserSafeErrorHandler.Area.LLM,
        )
        assertEquals(UserSafeErrorHandler.Friendly.LLM_GENERIC, r.friendlyText)
    }

    @Test
    fun `LLM 401 routes to USER_FIXABLE severity (auth)`() {
        val r = UserSafeErrorHandler.handle(
            LlmException("HTTP 401 unauthorized"),
            UserSafeErrorHandler.Area.LLM,
        )
        assertEquals(UserSafeErrorHandler.Severity.USER_FIXABLE, r.severity)
        assertEquals("auth", r.category)
    }

    @Test
    fun `IOException maps to network`() {
        val r = UserSafeErrorHandler.handle(
            IOException("timeout"), UserSafeErrorHandler.Area.OPENCLAW,
        )
        assertEquals(UserSafeErrorHandler.Friendly.NETWORK, r.friendlyText)
        assertEquals(UserSafeErrorHandler.Severity.TRANSIENT, r.severity)
    }

    @Test
    fun `SecurityException maps to permission`() {
        val r = UserSafeErrorHandler.handle(
            SecurityException("PERMISSION_DENIED"),
            UserSafeErrorHandler.Area.LOCAL_TOOL,
        )
        assertEquals(UserSafeErrorHandler.Friendly.PERMISSION_MISSING, r.friendlyText)
        assertEquals(UserSafeErrorHandler.Severity.USER_FIXABLE, r.severity)
    }

    @Test
    fun `OpenClaw area maps to OpenClaw unavailable`() {
        val r = UserSafeErrorHandler.handle(
            RuntimeException("anything"),
            UserSafeErrorHandler.Area.OPENCLAW,
        )
        assertEquals(UserSafeErrorHandler.Friendly.OPENCLAW_UNAVAILABLE, r.friendlyText)
    }

    @Test
    fun `Todoist area maps to offline saved`() {
        val r = UserSafeErrorHandler.handle(
            RuntimeException("anything"),
            UserSafeErrorHandler.Area.TODOIST,
        )
        assertEquals(UserSafeErrorHandler.Friendly.TODOIST_OFFLINE, r.friendlyText)
    }

    @Test
    fun `unknown exception maps to generic bug copy and BUG severity`() {
        val r = UserSafeErrorHandler.handle(
            IllegalStateException("internal state corruption"),
            UserSafeErrorHandler.Area.ROUTING,
        )
        assertEquals(UserSafeErrorHandler.Friendly.GENERIC_BUG, r.friendlyText)
        assertEquals(UserSafeErrorHandler.Severity.BUG, r.severity)
        assertEquals("IllegalStateException", r.category)
    }

    @Test
    fun `CancellationException is EXPECTED_NO_MATCH and empty text`() {
        val r = UserSafeErrorHandler.handle(
            kotlinx.coroutines.CancellationException("cancelled"),
            UserSafeErrorHandler.Area.LLM,
        )
        assertEquals(UserSafeErrorHandler.Severity.EXPECTED_NO_MATCH, r.severity)
        assertEquals("", r.friendlyText)
    }

    @Test
    fun `null throwable maps to generic bug`() {
        val r = UserSafeErrorHandler.handle(null, UserSafeErrorHandler.Area.UNKNOWN)
        assertEquals(UserSafeErrorHandler.Friendly.GENERIC_BUG, r.friendlyText)
        assertEquals(UserSafeErrorHandler.Severity.BUG, r.severity)
    }

    @Test
    fun `noMatch returns the expected unsupported message`() {
        val r = UserSafeErrorHandler.noMatch(UserSafeErrorHandler.Area.LOCAL_TOOL)
        assertEquals(UserSafeErrorHandler.Severity.EXPECTED_NO_MATCH, r.severity)
        assertTrue(r.friendlyText.isNotBlank())
    }
}
