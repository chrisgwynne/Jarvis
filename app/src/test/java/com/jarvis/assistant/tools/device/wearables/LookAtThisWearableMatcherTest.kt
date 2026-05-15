package com.jarvis.assistant.tools.device.wearables

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LookAtThisWearableMatcherTest — pin the disambiguation between
 * "look at this" (ambiguous; ALSO owned by the existing screen-
 * observation handler) and "take a glasses photo" / "what am I
 * looking at" (explicit glasses).
 *
 * The historical bug this defends against:
 *   "look at this" → the existing `LookAtThisIntentHandler` takes a
 *   phone screenshot.  Once we register the wearable tool first, an
 *   over-eager matcher would steal every "look at this" — including
 *   when the glasses are powered off — and the user loses the
 *   screenshot fallback entirely.  We claim ambiguous triggers ONLY
 *   when the glasses are actually ready.  Explicit triggers always
 *   claim so the user gets a friendly "glasses aren't connected"
 *   message rather than a silent screenshot.
 */
class LookAtThisWearableMatcherTest {

    // ── Explicit glasses phrases ──────────────────────────────────────────

    @Test fun `take a glasses photo always matches even when not ready`() {
        listOf("take a glasses photo", "Take glasses picture",
               "take a glasses shot", "glasses camera",
               "what am I looking at").forEach {
            assertEquals("'$it' should classify as EXPLICIT",
                WearableMatchKind.EXPLICIT,
                LookAtThisWearableTool.classify(it, isGlassesReady = false))
            assertEquals("'$it' should still classify as EXPLICIT when ready",
                WearableMatchKind.EXPLICIT,
                LookAtThisWearableTool.classify(it, isGlassesReady = true))
        }
    }

    // ── Ambiguous phrases ────────────────────────────────────────────────

    @Test fun `look at this matches only when glasses are ready`() {
        // Ambiguous — when glasses NOT ready, decline so the existing
        // LookAtThisIntentHandler (phone screenshot) takes over.
        assertNull(LookAtThisWearableTool.classify(
            "look at this", isGlassesReady = false))
        // When glasses ARE ready, claim it.
        assertEquals(
            WearableMatchKind.AMBIGUOUS,
            LookAtThisWearableTool.classify("look at this", isGlassesReady = true),
        )
    }

    @Test fun `capture this is also ambiguous`() {
        assertNull(LookAtThisWearableTool.classify(
            "capture this", isGlassesReady = false))
        assertEquals(
            WearableMatchKind.AMBIGUOUS,
            LookAtThisWearableTool.classify("capture this", isGlassesReady = true),
        )
    }

    // ── Non-matches ──────────────────────────────────────────────────────

    @Test fun `unrelated utterances never match`() {
        listOf(
            "what time is it",
            "remind me to call mum",
            "send Mike a whatsapp",
            "show me the selfie",
            "take a photo",         // plain — owned by CameraCaptureTool
            "what is this",         // intentionally NOT a glasses phrase
        ).forEach {
            assertNull("'$it' should not match (not ready)",
                LookAtThisWearableTool.classify(it, isGlassesReady = false))
            assertNull("'$it' should not match (ready)",
                LookAtThisWearableTool.classify(it, isGlassesReady = true))
        }
    }
}
