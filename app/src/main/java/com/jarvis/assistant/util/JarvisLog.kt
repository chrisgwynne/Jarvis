package com.jarvis.assistant.util

import android.util.Log

/**
 * JarvisLog — thin logcat wrapper that emits structured `key=value` events
 * under a single tag per subsystem.  Grep-friendly: `logcat -s Jarvis |
 * grep event=proactive_dispatch`.
 *
 * Usage:
 *   val log = JarvisLog.for("proactive")
 *   log.event("dispatch", "type" to event.type, "score" to scored.finalScore)
 *
 * Values are rendered via toString().  Keys must not contain spaces or `=`.
 */
class JarvisLog private constructor(private val subsystem: String) {

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val body = buildString {
            append("subsystem=").append(subsystem)
            append(" event=").append(name)
            for ((k, v) in fields) {
                append(' ').append(k).append('=').append(render(v))
            }
        }
        Log.d(TAG, body)
    }

    fun warn(name: String, throwable: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val body = buildString {
            append("subsystem=").append(subsystem)
            append(" event=").append(name)
            for ((k, v) in fields) {
                append(' ').append(k).append('=').append(render(v))
            }
        }
        if (throwable != null) Log.w(TAG, body, throwable) else Log.w(TAG, body)
    }

    private fun render(v: Any?): String {
        val s = v?.toString() ?: "null"
        // Strip spaces so a single key=value stays atomic under grep.
        return if (s.any { it.isWhitespace() || it == '=' }) "\"${s.replace("\"", "'")}\"" else s
    }

    companion object {
        const val TAG = "Jarvis"
        fun forSubsystem(subsystem: String) = JarvisLog(subsystem)
    }
}
