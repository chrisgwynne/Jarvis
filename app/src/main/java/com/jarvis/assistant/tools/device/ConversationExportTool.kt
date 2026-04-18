package com.jarvis.assistant.tools.device

import android.content.Context
import android.os.Environment
import android.util.Log
import com.jarvis.assistant.memory.db.JarvisDatabase
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ConversationExportTool — saves the most recent session's turns to a markdown
 * file in the public Downloads folder.
 *
 * Pattern: "export/save [this] conversation/chat/transcript"
 * Output: jarvis_conversation_YYYYMMDD_HHmm.md in Downloads
 */
class ConversationExportTool(private val context: Context) : Tool {

    override val name            = "export_conversation"
    override val description     = "Exports the current conversation transcript to a markdown file in Downloads"
    override val requiresNetwork = false

    companion object {
        private const val TAG = "ConversationExportTool"

        private val TRIGGERS = Regex(
            """(?:export|save)\s+(?:this\s+)?(?:conversation|chat|transcript)""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun matches(transcript: String): ToolInput? =
        if (TRIGGERS.containsMatchIn(transcript)) ToolInput(transcript) else null

    override suspend fun execute(input: ToolInput): ToolResult {
        val dao = JarvisDatabase.getInstance(context).conversationDao()

        val sessions = dao.getRecentSessions(1)
        if (sessions.isEmpty()) {
            return ToolResult.Failure("No conversation history found to export.")
        }

        val session = sessions.first()
        val turns   = dao.getTurnsForSession(session.id)
        if (turns.isEmpty()) {
            return ToolResult.Failure("The current conversation is empty.")
        }

        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val fileFmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())

        val markdown = buildString {
            appendLine("# Jarvis Conversation")
            appendLine("**Date:** ${dateFmt.format(Date(session.startedAt))}")
            appendLine()
            for (turn in turns) {
                val label = if (turn.role == "user") "**You:**" else "**Jarvis:**"
                appendLine("$label ${turn.content}")
                appendLine()
            }
        }

        val fileName = "jarvis_conversation_${fileFmt.format(Date())}.md"
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, fileName).writeText(markdown)
            Log.d(TAG, "Exported ${turns.size} turns to $fileName")
            ToolResult.Success("Done. Saved $fileName to your Downloads folder.")
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            ToolResult.Failure("I couldn't write the file. ${e.message?.take(80)}")
        }
    }
}
