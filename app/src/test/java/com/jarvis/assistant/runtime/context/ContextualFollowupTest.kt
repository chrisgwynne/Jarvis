package com.jarvis.assistant.runtime.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualFollowupTest {

    private val store get() = RecentActionContextStore(
        ttlMs = 10 * 60_000L,
        clock = { 1_000L },
    )

    // ── Parser ────────────────────────────────────────────────────────────

    @Test
    fun `bare turn off matches RepeatToggle OFF`() {
        listOf("turn off", "turn it off", "switch off", "switch it off").forEach {
            val f = ContextualFollowupParser.parse(it)
            assertTrue("'$it' should be RepeatToggle, got $f",
                f is ContextualFollowupParser.Followup.RepeatToggle)
            assertEquals(ContextualFollowupParser.Followup.Direction.OFF,
                (f as ContextualFollowupParser.Followup.RepeatToggle).direction)
        }
    }

    @Test
    fun `bare turn on matches RepeatToggle ON`() {
        val f = ContextualFollowupParser.parse("turn it on")
        assertTrue(f is ContextualFollowupParser.Followup.RepeatToggle)
        assertEquals(ContextualFollowupParser.Followup.Direction.ON,
            (f as ContextualFollowupParser.Followup.RepeatToggle).direction)
    }

    @Test
    fun `show me the selfie matches ShowMedia`() {
        listOf("show me the selfie", "show it", "open the photo",
               "show the video", "let me see that").forEach {
            assertEquals("'$it' should match ShowMedia",
                ContextualFollowupParser.Followup.ShowMedia,
                ContextualFollowupParser.parse(it))
        }
    }

    @Test
    fun `do it again matches Repeat`() {
        assertEquals(ContextualFollowupParser.Followup.Repeat,
            ContextualFollowupParser.parse("do it again"))
        assertEquals(ContextualFollowupParser.Followup.Repeat,
            ContextualFollowupParser.parse("again"))
    }

    @Test
    fun `share that matches ShareCurrent`() {
        assertEquals(ContextualFollowupParser.Followup.ShareCurrent,
            ContextualFollowupParser.parse("share that"))
    }

    @Test
    fun `cancel that matches Cancel`() {
        assertEquals(ContextualFollowupParser.Followup.Cancel,
            ContextualFollowupParser.parse("cancel that"))
    }

    @Test
    fun `non-followup utterances return null`() {
        listOf("what time is it", "remind me to call mum",
               "send mike a whatsapp", "turn the volume down",
               "turn the flashlight on").forEach {
            assertNull("'$it' should not be a followup",
                ContextualFollowupParser.parse(it))
        }
    }

    @Test
    fun `looksLikeFollowup mirrors parse positive cases`() {
        listOf("turn off", "show it", "do it again", "share that", "cancel that")
            .forEach {
                assertTrue("'$it' should be flagged",
                    ContextualFollowupParser.looksLikeFollowup(it))
            }
    }

    // ── Store + Resolver join ─────────────────────────────────────────────

    @Test
    fun `turn off after flashlight on dispatches flashlight off`() {
        val s = store
        s.record(
            type   = RecentActionContextStore.ActionType.DEVICE_TOGGLE,
            tool   = "flashlight",
            target = "flashlight",
            params = mapOf("direction" to "on"),
        )
        val r = ContextualFollowupResolver.resolve("turn off", s)
        assertTrue(r is ContextualFollowupResolver.Resolution.Dispatch)
        val d = r as ContextualFollowupResolver.Resolution.Dispatch
        assertEquals("flashlight", d.toolName)
        assertEquals("off", d.params["direction"])
    }

    @Test
    fun `turn off after smart_home on dispatches smart_home off`() {
        val s = store
        s.record(
            type   = RecentActionContextStore.ActionType.SMART_HOME,
            tool   = "smart_home",
            target = "kitchen lights",
            params = mapOf("entity" to "light.kitchen", "action" to "on"),
        )
        val r = ContextualFollowupResolver.resolve("turn it off", s) as
            ContextualFollowupResolver.Resolution.Dispatch
        assertEquals("smart_home", r.toolName)
        assertEquals("off", r.params["action"])
        assertEquals("light.kitchen", r.params["entity"])
    }

    @Test
    fun `bare turn off after volume change resolves to mute`() {
        val s = store
        s.record(
            type   = RecentActionContextStore.ActionType.VOLUME,
            tool   = "volume_control",
            target = "volume",
            params = mapOf("direction" to "down"),
        )
        val r = ContextualFollowupResolver.resolve("turn off", s)
        assertTrue(r is ContextualFollowupResolver.Resolution.Dispatch)
        assertEquals("mute",
            (r as ContextualFollowupResolver.Resolution.Dispatch)
                .params["direction"])
    }

    @Test
    fun `show me the selfie after camera capture dispatches view_media`() {
        val s = store
        s.record(
            type     = RecentActionContextStore.ActionType.MEDIA_CAPTURE,
            tool     = "camera_capture",
            params   = mapOf("kind" to "photo", "camera" to "front"),
            mediaUri = "content://media/external/images/media/1234",
        )
        val r = ContextualFollowupResolver.resolve("show me the selfie", s)
        assertTrue(r is ContextualFollowupResolver.Resolution.Dispatch)
        val d = r as ContextualFollowupResolver.Resolution.Dispatch
        assertEquals("view_media", d.toolName)
        assertTrue(d.params["uri"]!!.startsWith("content://"))
    }

    @Test
    fun `show me the selfie with no media context is NotApplicable`() {
        val s = store
        s.record(
            type   = RecentActionContextStore.ActionType.DEVICE_TOGGLE,
            tool   = "flashlight",
            params = mapOf("direction" to "on"),
        )
        val r = ContextualFollowupResolver.resolve("show me the selfie", s)
        // No mediaUri on the last action → soft fall-through.
        assertTrue(r is ContextualFollowupResolver.Resolution.NotApplicable)
    }

    @Test
    fun `share that with no media speaks a friendly message`() {
        val s = store
        s.record(
            type   = RecentActionContextStore.ActionType.DEVICE_TOGGLE,
            tool   = "flashlight",
        )
        val r = ContextualFollowupResolver.resolve("share that", s)
        assertTrue(r is ContextualFollowupResolver.Resolution.Speak)
    }

    @Test
    fun `do it again replays the same tool and params`() {
        val s = store
        s.record(
            type   = RecentActionContextStore.ActionType.DEVICE_TOGGLE,
            tool   = "flashlight",
            params = mapOf("direction" to "on"),
        )
        val r = ContextualFollowupResolver.resolve("do it again", s) as
            ContextualFollowupResolver.Resolution.Dispatch
        assertEquals("flashlight", r.toolName)
        assertEquals("on", r.params["direction"])
    }

    @Test
    fun `empty store yields NotApplicable for any followup`() {
        val s = store
        val r = ContextualFollowupResolver.resolve("turn off", s)
        assertTrue(r is ContextualFollowupResolver.Resolution.NotApplicable)
    }

    @Test
    fun `non-followup utterance is NotApplicable`() {
        val s = store
        s.record(
            type   = RecentActionContextStore.ActionType.DEVICE_TOGGLE,
            tool   = "flashlight",
            params = mapOf("direction" to "on"),
        )
        val r = ContextualFollowupResolver.resolve("what time is it", s)
        assertTrue(r is ContextualFollowupResolver.Resolution.NotApplicable)
    }

    // ── Regression: bare "show me" and noun-only variants ────────────────

    @Test
    fun `bare show me matches ShowMedia`() {
        assertEquals(ContextualFollowupParser.Followup.ShowMedia,
            ContextualFollowupParser.parse("show me"))
        assertEquals(ContextualFollowupParser.Followup.ShowMedia,
            ContextualFollowupParser.parse("show me."))
    }

    @Test
    fun `show me the selfie still matches the parser`() {
        // The reported bug was that CameraCaptureTool matched first
        // (bare `\bselfie\b` catch-all).  Tightening the camera regex
        // lets the contextual follow-up resolver run — but only if
        // the parser still recognises the phrase.
        assertEquals(ContextualFollowupParser.Followup.ShowMedia,
            ContextualFollowupParser.parse("show me the selfie"))
        assertEquals(ContextualFollowupParser.Followup.ShowMedia,
            ContextualFollowupParser.parse("show me my selfie"))
    }

    @Test
    fun `show the photo matches without me`() {
        assertEquals(ContextualFollowupParser.Followup.ShowMedia,
            ContextualFollowupParser.parse("show the photo"))
        assertEquals(ContextualFollowupParser.Followup.ShowMedia,
            ContextualFollowupParser.parse("open the picture"))
    }

    @Test
    fun `bare show me dispatches to view_media when media context exists`() {
        val s = RecentActionContextStore(ttlMs = 60_000L, clock = { 1_000L })
        s.record(
            type     = RecentActionContextStore.ActionType.MEDIA_CAPTURE,
            tool     = "camera_capture",
            params   = mapOf("kind" to "selfie"),
            mediaUri = "/data/cache/camera/IMG_001.jpg",
        )
        val r = ContextualFollowupResolver.resolve("show me", s)
        assertTrue("expected Dispatch, got $r",
            r is ContextualFollowupResolver.Resolution.Dispatch)
        assertEquals("view_media",
            (r as ContextualFollowupResolver.Resolution.Dispatch).toolName)
    }

    @Test
    fun `bare show me with no media context is NotApplicable`() {
        val s = RecentActionContextStore(clock = { 0L })
        s.record(
            type   = RecentActionContextStore.ActionType.DEVICE_TOGGLE,
            tool   = "flashlight",
            params = mapOf("direction" to "on"),
        )
        val r = ContextualFollowupResolver.resolve("show me", s)
        assertTrue("expected NotApplicable, got $r",
            r is ContextualFollowupResolver.Resolution.NotApplicable)
    }

    // ── TTL ───────────────────────────────────────────────────────────────

    @Test
    fun `expired context is dropped`() {
        var now = 0L
        val s = RecentActionContextStore(ttlMs = 60_000L, clock = { now })
        s.record(
            type   = RecentActionContextStore.ActionType.DEVICE_TOGGLE,
            tool   = "flashlight",
            params = mapOf("direction" to "on"),
        )
        now = 70_000L
        assertNull(s.peek())
    }
}
