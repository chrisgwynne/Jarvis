package com.jarvis.assistant.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisualContextStoreTest {

    private lateinit var store: VisualContextStore

    @Before
    fun setUp() {
        store = VisualContextStore()
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    fun `update sets current context`() {
        val ctx = makeCtx(source = VisualContextStore.Source.PHONE_CAMERA)
        store.update(ctx)
        assertEquals(ctx, store.current)
    }

    @Test
    fun `update replaces previous context`() {
        store.update(makeCtx(source = VisualContextStore.Source.SCREENSHOT))
        val newer = makeCtx(source = VisualContextStore.Source.PHONE_CAMERA)
        store.update(newer)
        assertEquals(newer, store.current)
    }

    // ── hasContext ─────────────────────────────────────────────────────────────

    @Test
    fun `hasContext is false when empty`() {
        assertFalse(store.hasContext)
    }

    @Test
    fun `hasContext is true after update`() {
        store.update(makeCtx())
        assertTrue(store.hasContext)
    }

    // ── expiry ─────────────────────────────────────────────────────────────────

    @Test
    fun `current returns null for expired context`() {
        val expiredMs = System.currentTimeMillis() - VisualContextStore.EXPIRY_MS - 1_000L
        store.update(makeCtx(capturedAtMs = expiredMs))
        assertNull("Expired context should return null from current", store.current)
    }

    @Test
    fun `hasContext is false for expired context`() {
        val expiredMs = System.currentTimeMillis() - VisualContextStore.EXPIRY_MS - 1_000L
        store.update(makeCtx(capturedAtMs = expiredMs))
        assertFalse(store.hasContext)
    }

    @Test
    fun `current returns context within expiry window`() {
        val freshMs = System.currentTimeMillis() - (VisualContextStore.EXPIRY_MS / 2)
        val ctx = makeCtx(capturedAtMs = freshMs)
        store.update(ctx)
        assertEquals(ctx, store.current)
    }

    // ── enrichCurrent ─────────────────────────────────────────────────────────

    @Test
    fun `enrichCurrent adds ocr text to existing context`() {
        store.update(makeCtx())
        val enriched = store.enrichCurrent(ocrText = "Hello world")
        assertTrue(enriched)
        assertEquals("Hello world", store.current?.ocrText)
    }

    @Test
    fun `enrichCurrent adds summary to existing context`() {
        store.update(makeCtx())
        store.enrichCurrent(summary = "A photo of a cat")
        assertEquals("A photo of a cat", store.current?.summary)
    }

    @Test
    fun `enrichCurrent preserves other fields`() {
        val original = makeCtx(
            source = VisualContextStore.Source.SCREENSHOT,
            imageFilePath = "/data/screenshot.png",
            appName = "com.example.app",
        )
        store.update(original)
        store.enrichCurrent(ocrText = "some text")
        val ctx = store.current!!
        assertEquals(VisualContextStore.Source.SCREENSHOT, ctx.source)
        assertEquals("/data/screenshot.png", ctx.imageFilePath)
        assertEquals("com.example.app", ctx.appName)
    }

    @Test
    fun `enrichCurrent returns false when no context`() {
        val result = store.enrichCurrent(ocrText = "nothing")
        assertFalse(result)
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    fun `clear removes context`() {
        store.update(makeCtx())
        store.clear()
        assertNull(store.current)
        assertFalse(store.hasContext)
    }

    @Test
    fun `clear is safe when already empty`() {
        store.clear()
        assertFalse(store.hasContext)
    }

    // ── contextFlow ───────────────────────────────────────────────────────────

    @Test
    fun `contextFlow initial value is null`() {
        assertNull(store.contextFlow.value)
    }

    @Test
    fun `contextFlow reflects latest update`() {
        val ctx = makeCtx(source = VisualContextStore.Source.GALLERY)
        store.update(ctx)
        assertEquals(ctx, store.contextFlow.value)
    }

    @Test
    fun `contextFlow emits null after clear`() {
        store.update(makeCtx())
        store.clear()
        assertNull(store.contextFlow.value)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeCtx(
        source: VisualContextStore.Source = VisualContextStore.Source.PHONE_CAMERA,
        imageFilePath: String? = "/data/test.jpg",
        appName: String? = null,
        capturedAtMs: Long = System.currentTimeMillis(),
    ) = VisualContextStore.VisualContext(
        source        = source,
        imageFilePath = imageFilePath,
        appName       = appName,
        capturedAtMs  = capturedAtMs,
    )
}
