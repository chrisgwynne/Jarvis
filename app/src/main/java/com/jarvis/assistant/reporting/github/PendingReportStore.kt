package com.jarvis.assistant.reporting.github

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * PendingReportStore — append-only, file-backed queue of reports that should
 * be posted later.  Two callers:
 *
 *   1. [JarvisUncaughtHandler] — writes synchronously on the crash path (no
 *      network is safe on a dying process; the next cold start drains).
 *   2. [IssueReporter] — writes when a submit fails transiently (network,
 *      GitHub 5xx), so we can retry without losing context.
 *
 * On disk: `$cacheDir/jarvis_pending_reports/<uuid>.json`.  Storage is bounded
 * — [enqueue] evicts the oldest entries once [MAX_ENTRIES] is exceeded, so a
 * crash loop never fills the disk.  No secrets are serialised here.
 */
class PendingReportStore(context: Context) {

    private val dir: File = File(context.cacheDir, DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    /** Serialised subset of [IssueReport] — enough to rebuild + resubmit. */
    data class Persisted(
        val severity: String,
        val subsystem: String,
        val category: String,
        val message: String,
        val timestampMs: Long,
        val isCrash: Boolean,
        val fingerprint: String,
        val stackTrace: String,
        val metadata: Map<String, String>,
        val occurrenceCount: Int
    )

    @Synchronized
    fun enqueue(p: Persisted) {
        try {
            val file = File(dir, "${p.timestampMs}-${UUID.randomUUID()}.json")
            file.writeText(toJson(p))
            evictOverflow()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enqueue pending report: ${e.message}")
        }
    }

    /** Returns all queued reports, oldest first. */
    @Synchronized
    fun drain(): List<Pair<File, Persisted>> {
        val files = dir.listFiles()?.sortedBy { it.name } ?: return emptyList()
        val out = mutableListOf<Pair<File, Persisted>>()
        for (f in files) {
            val p = runCatching { fromJson(f.readText()) }.getOrNull() ?: run {
                f.delete(); continue
            }
            out += f to p
        }
        return out
    }

    fun delete(file: File) {
        runCatching { file.delete() }
    }

    @Synchronized
    private fun evictOverflow() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        val overflow = files.size - MAX_ENTRIES
        if (overflow > 0) {
            files.take(overflow).forEach { runCatching { it.delete() } }
        }
    }

    // ── Minimal JSON via the existing Gson instance ──────────────────────────

    private fun toJson(p: Persisted): String =
        com.jarvis.assistant.llm.NetworkClient.gson.toJson(p)

    private fun fromJson(s: String): Persisted? =
        com.jarvis.assistant.llm.NetworkClient.gson.fromJson(s, Persisted::class.java)

    companion object {
        private const val TAG        = "PendingReportStore"
        private const val DIR_NAME   = "jarvis_pending_reports"
        private const val MAX_ENTRIES = 32
    }
}
