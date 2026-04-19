package com.jarvis.assistant.tools.device

import com.jarvis.assistant.memory.db.dao.MemoryDao
import com.jarvis.assistant.memory.db.dao.MemoryFactDao
import com.jarvis.assistant.memory.db.entity.MemoryType
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * MemoryStatsTool — answers "how many memories do you have?" and lists recent ones.
 *
 * Gives the user visibility into what Jarvis has retained so they can verify
 * memory is working and know exactly what's stored.
 */
class MemoryStatsTool(
    private val memoryDao: MemoryDao,
    private val memoryFactDao: MemoryFactDao
) : Tool {

    override val name        = "memory_stats"
    override val description = "Reports how many memories Jarvis has stored and lists them"

    override fun schema() = ToolSchema(
        name        = name,
        description = "Report how many memories Jarvis has stored and list the most useful recent ones.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>(),
            "required" to emptyList<String>()
        )
    )

    private val PATTERNS = listOf(
        Regex("""how many memories""",                              RegexOption.IGNORE_CASE),
        Regex("""how much do you (know|remember) about me""",       RegexOption.IGNORE_CASE),
        Regex("""what memories do you have""",                      RegexOption.IGNORE_CASE),
        Regex("""list (your|my|all) memories""",                    RegexOption.IGNORE_CASE),
        Regex("""show (me )?(your|my|all) memories""",              RegexOption.IGNORE_CASE),
        Regex("""what have you (stored|saved|retained|remembered)""", RegexOption.IGNORE_CASE),
        Regex("""what do you (know|remember) about me""",           RegexOption.IGNORE_CASE),
    )

    override fun matches(transcript: String): ToolInput? =
        if (PATTERNS.any { it.containsMatchIn(transcript) }) ToolInput(transcript) else null

    override suspend fun execute(input: ToolInput): ToolResult {
        val profileFacts = memoryFactDao.getAll()
        val preferences  = memoryDao.getByType(MemoryType.PREFERENCE, limit = 10)
        val facts        = memoryDao.getByType(MemoryType.FACTUAL,    limit = 10)
        val tasks        = memoryDao.getByType(MemoryType.TASK,       limit = 5)
        val summaries    = memoryDao.getByType(MemoryType.SUMMARY,    limit = 5)

        val totalEntries   = memoryDao.count()
        val totalProfile   = profileFacts.size

        if (totalEntries == 0 && totalProfile == 0) {
            return ToolResult.Success("I don't have any memories stored yet.")
        }

        val spoken = buildString {
            // Headline count
            val parts = mutableListOf<String>()
            if (totalProfile > 0)      parts += "$totalProfile profile fact${if (totalProfile != 1) "s" else ""}"
            if (preferences.isNotEmpty()) parts += "${preferences.size} preference${if (preferences.size != 1) "s" else ""}"
            if (facts.isNotEmpty())    parts += "${facts.size} fact${if (facts.size != 1) "s" else ""}"
            if (tasks.isNotEmpty())    parts += "${tasks.size} task${if (tasks.size != 1) "s" else ""}"
            if (summaries.isNotEmpty()) parts += "${summaries.size} session summar${if (summaries.size != 1) "ies" else "y"}"

            append("I have ")
            append(when (parts.size) {
                0    -> "$totalEntries entr${if (totalEntries != 1) "ies" else "y"}"
                1    -> parts[0]
                else -> parts.dropLast(1).joinToString(", ") + ", and ${parts.last()}"
            })
            append(" stored.")

            // List profile facts (most useful to read back)
            if (profileFacts.isNotEmpty()) {
                append(" Profile: ")
                append(profileFacts.take(6).joinToString("; ") { f ->
                    val label = f.factKey.removePrefix("user.").removePrefix("fact.").removePrefix("pref.")
                        .replace('.', ' ')
                    "${label}: ${f.value}"
                })
                append(".")
            }

            // List recent preferences
            if (preferences.isNotEmpty()) {
                append(" Preferences: ")
                append(preferences.take(4).joinToString("; ") { it.content.take(80) })
                append(".")
            }

            // List recent facts
            if (facts.isNotEmpty()) {
                append(" Facts: ")
                append(facts.take(3).joinToString("; ") { it.content.take(80) })
                append(".")
            }

            // Mention tasks if any
            if (tasks.isNotEmpty()) {
                append(" Tasks pending: ")
                append(tasks.take(3).joinToString("; ") { it.content.take(80) })
                append(".")
            }
        }

        return ToolResult.Success(spoken.trim())
    }
}
