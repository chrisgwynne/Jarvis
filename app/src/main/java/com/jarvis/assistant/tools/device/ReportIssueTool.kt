package com.jarvis.assistant.tools.device

import com.jarvis.assistant.reporting.github.IssueReporter
import com.jarvis.assistant.reporting.github.IssueReporter.SubmitOutcome
import com.jarvis.assistant.tools.framework.RiskClass
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ReportIssueTool — lets the user file a GitHub issue by voice.
 *
 * Trigger examples:
 *   "create an issue, the kitchen light response was completely wrong"
 *   "file a bug, Jarvis misunderstood me when I asked about the weather"
 *   "report a problem, response took 30 seconds"
 *   "log an issue, it said mop kitchen instead of kitchen light"
 *
 * The description after the trigger phrase is used verbatim as the issue body.
 * The issue is filed immediately via [IssueReporter.reportUserFeedback], bypassing
 * the repetition threshold — this is an explicit user action.
 *
 * If GitHub reporting isn't configured, tells the user how to set it up.
 */
class ReportIssueTool : Tool {

    override val name             = "report_issue"
    override val description      = "File a GitHub issue describing a problem with Jarvis"
    override val requiresNetwork  = true
    override val riskClass        = RiskClass.LOW

    override fun schema() = ToolSchema(
        name        = name,
        description = "Create a GitHub issue for a bug, misunderstanding, or any problem the user noticed.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "description" to mapOf(
                    "type"        to "string",
                    "description" to "What went wrong — the user's own words"
                )
            ),
            "required" to listOf("description")
        )
    )

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim().lowercase()
        val triggered = TRIGGERS.any { t.startsWith(it) || t.contains(it) }
        if (!triggered) return null

        // Extract the description that follows the trigger phrase
        val description = TRIGGER_REGEX.find(transcript.trim())
            ?.groupValues?.get(1)?.trim()
            ?: transcript.trim()

        if (description.isBlank()) return null
        return ToolInput(transcript, mapOf("description" to description))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val description = input.param("description").ifBlank { input.transcript }
        val reporter    = IssueReporter.get()
            ?: return ToolResult.Failure(
                "GitHub reporting isn't set up. Enable it in Settings → Privacy → GitHub issue reporting."
            )

        val outcome = suspendCancellableCoroutine<SubmitOutcome> { cont ->
            reporter.reportUserFeedback(
                description = description,
                metadata    = mapOf("source" to "voice", "transcript" to input.transcript.take(200)),
                onResult    = { cont.resume(it) }
            )
        }

        return when (outcome) {
            is SubmitOutcome.Created      -> {
                val url = outcome.htmlUrl
                if (url != null)
                    ToolResult.Success("Done — issue filed at $url")
                else
                    ToolResult.Success("Issue filed on GitHub.")
            }
            is SubmitOutcome.DisabledByFlag   -> ToolResult.Failure(
                "GitHub reporting is disabled. Turn it on in Settings → Privacy."
            )
            is SubmitOutcome.NoTokenConfigured -> ToolResult.Failure(
                "No GitHub token set. Add a personal access token in Settings → Privacy."
            )
            is SubmitOutcome.Denied       -> ToolResult.Failure(
                "Couldn't file the issue: ${outcome.reason}."
            )
            is SubmitOutcome.Failed       -> ToolResult.Failure(
                "Failed to file the issue: ${outcome.reason}. Check the token and repo settings."
            )
            is SubmitOutcome.Queued       -> ToolResult.Success(
                "Couldn't reach GitHub right now — the issue is queued and will be filed when connectivity returns."
            )
        }
    }

    companion object {
        private val TRIGGERS = listOf(
            "create an issue",
            "file a bug",
            "file an issue",
            "report a problem",
            "report an issue",
            "report a bug",
            "log an issue",
            "log a bug",
            "raise an issue",
            "submit a bug",
        )

        private val TRIGGER_REGEX = Regex(
            """(?:create an issue|file (?:a bug|an issue)|report (?:a problem|an issue|a bug)|log (?:an issue|a bug)|raise an issue|submit a bug)[,\s]+(.+)""",
            RegexOption.IGNORE_CASE
        )
    }
}
