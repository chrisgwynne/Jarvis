package com.jarvis.assistant.core.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialContextTest {

    @Test
    fun `empty turns is NEUTRAL and engagement zero`() {
        val ctx = SocialContext.from(recentUserTurns = emptyList(), minutesSinceLastTurn = 10L)
        assertEquals(ConversationTone.NEUTRAL, ctx.tone)
        assertEquals(0f, ctx.engagement, 0.001f)
        assertNull(ctx.toPromptFragment())
    }

    @Test
    fun `very short turns classify as TERSE`() {
        val ctx = SocialContext.from(
            recentUserTurns = listOf("ok", "yep", "sure"),
            minutesSinceLastTurn = 2L,
        )
        assertEquals(ConversationTone.TERSE, ctx.tone)
    }

    @Test
    fun `long turns classify as WARM`() {
        val ctx = SocialContext.from(
            recentUserTurns = listOf(
                "I was just thinking about what we talked about yesterday and it really stuck with me for some reason",
                "We should plan something nice for the weekend because the weather is finally shaping up properly",
            ),
            minutesSinceLastTurn = 1L,
        )
        assertEquals(ConversationTone.WARM, ctx.tone)
    }

    @Test
    fun `concern keywords override length-based tone`() {
        val ctx = SocialContext.from(
            recentUserTurns = listOf("I'm so tired today"),
            minutesSinceLastTurn = 0L,
        )
        assertEquals(ConversationTone.CONCERNED, ctx.tone)
    }

    @Test
    fun `recent high-volume turns yield high engagement`() {
        val ctx = SocialContext.from(
            recentUserTurns = listOf("a", "b", "c", "d", "e"),
            minutesSinceLastTurn = 0L,
        )
        assertTrue("engagement should be > 0.7 but was ${ctx.engagement}", ctx.engagement > 0.7f)
    }

    @Test
    fun `stale low-volume conversation yields low engagement`() {
        val ctx = SocialContext.from(
            recentUserTurns = listOf("ok"),
            minutesSinceLastTurn = 60L,
        )
        assertTrue("engagement should be < 0.3 but was ${ctx.engagement}", ctx.engagement < 0.3f)
    }

    @Test
    fun `prompt fragment is null for NEUTRAL, non-null otherwise`() {
        val neutral = SocialContext.from(
            recentUserTurns = listOf("short medium length reply here"),
            minutesSinceLastTurn = 0L,
        )
        assertEquals(ConversationTone.NEUTRAL, neutral.tone)
        assertNull(neutral.toPromptFragment())

        val warm = SocialContext.from(
            recentUserTurns = listOf(
                "I was just thinking about what we talked about yesterday and it really stuck with me",
            ),
            minutesSinceLastTurn = 0L,
        )
        val frag = warm.toPromptFragment()
        assertTrue(frag != null && frag.startsWith("Recent tone:"))
    }
}
