package com.jarvis.assistant.security

import android.util.Log

object PolicyAuditLog {
    private const val TAG = "JarvisAudit"
    private const val MAX_ENTRIES = 100

    data class AuditEntry(
        val result: PolicyResult,
        val wallClockMs: Long = System.currentTimeMillis()
    )

    private val lock = Any()
    private val entries = ArrayDeque<AuditEntry>(MAX_ENTRIES)

    fun record(result: PolicyResult) {
        val entry = AuditEntry(result)
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(entry)
        }
        logResult(result)
    }

    fun recentEntries(): List<AuditEntry> = synchronized(lock) { entries.toList() }

    fun deniedCount(): Int = synchronized(lock) { entries.count { it.result is PolicyResult.ActionDenied } }
    fun unsupportedCount(): Int = synchronized(lock) { entries.count { it.result is PolicyResult.ActionUnsupported } }
    fun unsafeCount(): Int = synchronized(lock) { entries.count { it.result is PolicyResult.ActionUnsafe } }

    /** Clear entries — for testing only */
    fun clearForTest() = synchronized(lock) { entries.clear() }

    private fun logResult(result: PolicyResult) {
        val transcript = result.rawRequestedAction.take(60).let {
            if (result.rawRequestedAction.length > 60) "$it\u2026" else it
        }
        val actionLabel = result.requestedActionType?.name ?: "UNKNOWN"

        when (result) {
            is PolicyResult.ActionApproved ->
                Log.d(TAG, "[APPROVED] action=$actionLabel tool=${result.toolName} transcript=\"$transcript\"")

            is PolicyResult.ActionDenied ->
                Log.w(TAG, "[DENIED] action=$actionLabel reason=${result.reasonCode.code} details=${result.debugDetails} transcript=\"$transcript\"")

            is PolicyResult.ActionUnsupported ->
                Log.i(TAG, "[UNSUPPORTED] tool=${result.toolNameAttempted} details=${result.debugDetails} transcript=\"$transcript\"")

            is PolicyResult.ActionMalformed ->
                Log.w(TAG, "[MALFORMED] reason=${result.reasonCode} details=${result.debugDetails} transcript=\"$transcript\"")

            is PolicyResult.ActionUnsafe ->
                Log.w(TAG, "[UNSAFE] reason=${result.reasonCode} details=${result.debugDetails} transcript=\"$transcript\"")
        }
    }
}
