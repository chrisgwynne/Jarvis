package com.jarvis.assistant.vision

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * VisualContextStore — lightweight in-memory store for the most recent visual
 * context produced by any capture source (camera, screenshot, gallery).
 *
 * PURPOSE:
 *   Enables natural follow-up commands after a vision action:
 *     User: "Look at this."     → Jarvis analyses + stores VisualContext
 *     User: "Send that to Mike" → VisualFollowupTool reads from here
 *     User: "Read that again."  → OcrScanTool re-speaks stored OCR text
 *
 * EXPIRY:
 *   Context expires after [EXPIRY_MS] (10 minutes) so "send that to Mike"
 *   only works for images captured in the current session, not from yesterday.
 *
 * THREAD SAFETY:
 *   All read/write operations use [AtomicReference] — safe from any thread.
 */
class VisualContextStore {

    companion object {
        private const val TAG = "VisualContextStore"
        const val EXPIRY_MS = 10 * 60 * 1000L // 10 minutes
    }

    // ── Data model ────────────────────────────────────────────────────────────

    enum class Source {
        PHONE_CAMERA,
        FRONT_CAMERA,
        SCREENSHOT,
        GALLERY,
        META_GLASSES,
    }

    data class VisualContext(
        val source: Source,
        /** Absolute path to the image file (null if not saved to disk). */
        val imageFilePath: String?,
        /** OCR-extracted text, if available. */
        val ocrText: String? = null,
        /** Short natural-language summary of the image. */
        val summary: String? = null,
        /** Detected foreground app/package name (screenshots only). */
        val appName: String? = null,
        val capturedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            nowMs - capturedAtMs > EXPIRY_MS

        /** True if there is meaningful text content to speak or share. */
        fun hasText(): Boolean = !ocrText.isNullOrBlank() || !summary.isNullOrBlank()
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val _current = AtomicReference<VisualContext?>(null)
    private val _flow    = MutableStateFlow<VisualContext?>(null)

    /** Observable flow of the current visual context (may be expired — check [hasContext]). */
    val contextFlow: StateFlow<VisualContext?> = _flow.asStateFlow()

    /** The active visual context, or null if none or expired. */
    val current: VisualContext?
        get() = _current.get()?.takeUnless { it.isExpired() }

    /** True if there is a non-expired visual context available. */
    val hasContext: Boolean get() = current != null

    // ── Mutations ─────────────────────────────────────────────────────────────

    /** Replace the current context with [context]. */
    fun update(context: VisualContext) {
        _current.set(context)
        _flow.value = context
        Log.d(TAG, "[VISUAL_CTX_UPDATE] source=${context.source} " +
            "hasOcr=${context.ocrText != null} hasFile=${context.imageFilePath != null}")
    }

    /**
     * Enrich the current context with OCR text and/or summary, preserving
     * all other fields.  No-op if there is no active context.
     */
    fun enrichCurrent(ocrText: String? = null, summary: String? = null): Boolean {
        val ctx = _current.get() ?: return false
        val enriched = ctx.copy(
            ocrText  = ocrText  ?: ctx.ocrText,
            summary  = summary  ?: ctx.summary,
        )
        _current.set(enriched)
        _flow.value = enriched
        Log.d(TAG, "[VISUAL_CTX_ENRICHED] ocrLen=${ocrText?.length} summaryLen=${summary?.length}")
        return true
    }

    /** Clear all stored context. */
    fun clear() {
        _current.set(null)
        _flow.value = null
        Log.d(TAG, "[VISUAL_CTX_CLEARED]")
    }
}
