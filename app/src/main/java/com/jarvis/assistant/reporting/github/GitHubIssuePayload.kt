package com.jarvis.assistant.reporting.github

/**
 * GitHubIssuePayload — wire-format for `POST /repos/{owner}/{repo}/issues`.
 *
 * Kept as a plain data class so Gson serialises it directly with no custom
 * adapter and no reflection-unfriendly field names.
 */
data class GitHubIssuePayload(
    val title: String,
    val body: String,
    val labels: List<String> = emptyList()
)
