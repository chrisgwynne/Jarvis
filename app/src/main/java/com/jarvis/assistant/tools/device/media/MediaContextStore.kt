package com.jarvis.assistant.tools.device.media

import java.util.concurrent.atomic.AtomicReference

/**
 * MediaContextStore — process-wide pointer to the most recently
 * captured media file.  CameraCaptureTool writes here on success,
 * ViewMediaTool / ShareMediaTool read here when no explicit URI is
 * supplied.
 *
 * Why a singleton: the tools are constructed independently by
 * ToolRegistry.buildDefault — they don't share a closure with the
 * camera manager — and we don't want to plumb a captured-media list
 * through every tool constructor.  The store is in-memory only; on
 * process death the pointer is gone, which matches the conversational
 * "last thing I did" model.
 *
 * Pure / Android-free (java.io.File is on the JVM).  Thread-safe via
 * AtomicReference so the runtime's record path and the UI's
 * diagnostics read stay coherent.
 */
object MediaContextStore {

    data class Entry(
        /** Absolute filesystem path to the captured media. */
        val filePath: String,
        /** "image/jpeg", "video/mp4", "audio/m4a", … */
        val mimeType: String,
        /** "photo" / "selfie" / "video" / "recording" — for spoken copy. */
        val kind: String,
        val capturedAtMs: Long = System.currentTimeMillis(),
    )

    private val slot = AtomicReference<Entry?>(null)

    fun record(entry: Entry) { slot.set(entry) }

    fun peek(): Entry? = slot.get()

    fun clear() { slot.set(null) }
}
