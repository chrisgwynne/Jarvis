package com.jarvis.assistant.reporting.github

import java.security.MessageDigest

/**
 * IssueFingerprint — a stable short hash that identifies "this is the same
 * kind of problem as the last one", so the [IssueDeduper] and
 * [IssueRateLimiter] don't create a new GitHub issue for every re-occurrence.
 *
 * Inputs (in order):
 *   1. subsystem                     (e.g. "tts")
 *   2. category                      (e.g. "TTS_INIT_FAILED")
 *   3. exception class name, if any  (e.g. "java.lang.IllegalStateException")
 *   4. top app-package stack frame   — we walk the trace and pick the first
 *                                      com.jarvis.* frame, so framework frames
 *                                      that shift between Android versions
 *                                      don't invalidate the fingerprint.
 *   5. normalised message            — lowercased; digit runs, long hex, and
 *                                      file paths redacted so transient values
 *                                      (timestamps, temp-file paths, retry
 *                                      counts) don't fracture the fingerprint.
 *
 * Output is the first 16 hex chars of SHA-256 over the joined input — short
 * enough to go in issue titles and labels, long enough to be collision-free
 * in practice.
 */
object IssueFingerprint {

    private const val APP_PACKAGE_PREFIX = "com.jarvis"
    private const val HEX_LEN = 16

    fun of(report: IssueReport): String {
        val parts = buildList {
            add(report.subsystem)
            add(report.category)
            add(report.throwable?.javaClass?.name.orEmpty())
            add(topAppFrame(report.throwable))
            add(normalise(report.message))
        }
        return sha256Hex(parts.joinToString(separator = "|")).take(HEX_LEN)
    }

    /** First stack frame inside the app's package; falls back to the root cause's first frame. */
    private fun topAppFrame(t: Throwable?): String {
        val root = rootCause(t) ?: return ""
        val frames = root.stackTrace ?: return ""
        return frames.firstOrNull { it.className.startsWith(APP_PACKAGE_PREFIX) }
            ?.let { "${it.className}.${it.methodName}" }
            ?: frames.firstOrNull()?.let { "${it.className}.${it.methodName}" }
            ?: ""
    }

    private fun rootCause(t: Throwable?): Throwable? {
        var cur: Throwable? = t
        while (cur?.cause != null && cur.cause !== cur) cur = cur.cause
        return cur
    }

    /** Redact digit-runs, long hex tokens and absolute paths. */
    private fun normalise(message: String): String = message
        .lowercase()
        .replace(Regex("""\b[0-9a-f]{8,}\b"""), "#HEX#")
        .replace(Regex("""\b\d+\b"""),          "#N#")
        .replace(Regex("""/[^ \s\"']+"""),      "#PATH#")
        .trim()

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
