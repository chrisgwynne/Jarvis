package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.provider.CallLog
import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * RecentCallsTool — "who called me", "recent calls", "missed calls",
 * "last call".  Reads the system call log (READ_CALL_LOG).
 *
 * Reads up to 5 most-recent entries; missed-only mode when the user
 * specifically asks about missed calls.
 */
class RecentCallsTool(private val context: Context) : Tool {

    override val name = "recent_calls"
    override val description = "Read recent or missed calls from the call log."
    override val requiresNetwork = false
    override val requiredPermissions = listOf(Manifest.permission.READ_CALL_LOG)

    companion object {
        private const val TAG = "RecentCallsTool"
        private val ALL_RX = Regex(
            """\b(?:recent\s+calls|last\s+(?:few\s+)?calls?|who\s+(?:has\s+)?called(?:\s+me)?|call\s+history|who\s+rang(?:\s+me)?)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val MISSED_RX = Regex(
            """\bmissed\s+calls?\b|\bdid\s+i\s+miss\s+(?:any\s+)?calls?\b""",
            RegexOption.IGNORE_CASE,
        )
        private val LAST_RX = Regex(
            """\b(?:my\s+)?last\s+call\b|\bwho\s+called\s+(?:me\s+)?last\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        val mode = when {
            MISSED_RX.containsMatchIn(t) -> "missed"
            LAST_RX.containsMatchIn(t)   -> "last"
            ALL_RX.containsMatchIn(t)    -> "recent"
            else -> return null
        }
        return ToolInput(transcript, mapOf("mode" to mode))
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Read recent calls from the call log.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "mode" to mapOf("type" to "string", "enum" to listOf("recent","missed","last")),
            ),
            "required" to listOf("mode"),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val mode = input.param("mode")
        val limit = if (mode == "last") 1 else 5
        val selection = if (mode == "missed")
            "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}" else null
        return try {
            val cr = context.contentResolver
            val projection = arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
            )
            cr.query(
                CallLog.Calls.CONTENT_URI, projection, selection, null,
                "${CallLog.Calls.DATE} DESC",
            ).use { c ->
                if (c == null || !c.moveToFirst()) {
                    return ToolResult.Success(
                        if (mode == "missed") "No missed calls."
                        else "No recent calls."
                    )
                }
                val entries = mutableListOf<String>()
                do {
                    val name = c.getString(0) ?: c.getString(1) ?: "unknown"
                    val type = c.getInt(2)
                    val when_ = formatRelative(c.getLong(3))
                    val label = when (type) {
                        CallLog.Calls.MISSED_TYPE   -> "missed from"
                        CallLog.Calls.INCOMING_TYPE -> "from"
                        CallLog.Calls.OUTGOING_TYPE -> "to"
                        else                        -> "with"
                    }
                    entries.add("$label $name $when_")
                    if (entries.size >= limit) break
                } while (c.moveToNext())
                val joined = entries.joinToString("; ")
                ToolResult.Success(
                    when (mode) {
                        "last"   -> "Last call ${entries.first()}."
                        "missed" -> "Missed calls: $joined."
                        else     -> "Recent calls: $joined."
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Read call log failed", e)
            ToolResult.Failure("That didn't work — couldn't read the call log.")
        }
    }

    private fun formatRelative(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        val mins = diff / 60_000
        return when {
            mins < 2          -> "just now"
            mins < 60         -> "$mins minutes ago"
            mins < 60 * 24    -> "${mins / 60} hours ago"
            mins < 60 * 24*7  -> "${mins / (60*24)} days ago"
            else              -> "a while ago"
        }
    }
}
