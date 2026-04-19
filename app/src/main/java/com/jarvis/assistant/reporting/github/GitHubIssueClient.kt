package com.jarvis.assistant.reporting.github

import android.util.Log
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * GitHubIssueClient — single-purpose POST to
 * `POST https://api.github.com/repos/{owner}/{repo}/issues`.
 *
 * Uses the existing [NetworkClient] so we inherit OkHttp timeouts and the
 * network security config (cleartext denied by default — GitHub is HTTPS
 * anyway).  The client:
 *
 *   * authenticates with a bearer PAT supplied by the caller (never stored here)
 *   * sends JSON via Gson
 *   * returns a typed [Result] — never throws
 *   * classifies failures so the reporter can decide: retry later (transient)
 *     vs. drop (permanent, e.g. bad repo or revoked token)
 */
class GitHubIssueClient {

    companion object {
        private const val TAG = "GitHubIssueClient"
        private const val GITHUB_API_BASE = "https://api.github.com"
        /** Hard per-call timeout.  Background-submit paths already swallow this. */
        private const val HTTP_TIMEOUT_MS = 15_000L
    }

    sealed class Result {
        data class Success(val issueNumber: Int?, val htmlUrl: String?) : Result()
        object AuthFailure        : Result()
        object RepoNotFound       : Result()
        data class RateLimited(val retryAfterMs: Long)  : Result()
        data class Transient  (val reason: String)      : Result()
        data class Permanent  (val reason: String)      : Result()
    }

    /** Submit [payload] to [owner]/[repo] with the provided [token]. */
    suspend fun createIssue(
        owner: String,
        repo: String,
        token: String,
        payload: GitHubIssuePayload
    ): Result = withContext(Dispatchers.IO) {
        if (owner.isBlank() || repo.isBlank()) return@withContext Result.Permanent("owner/repo not configured")
        if (token.isBlank())                   return@withContext Result.AuthFailure

        val url = "$GITHUB_API_BASE/repos/${owner.trim()}/${repo.trim()}/issues"
        val headers = mapOf(
            "Accept"               to "application/vnd.github+json",
            "Authorization"        to "Bearer ${token.trim()}",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent"           to "Jarvis-IssueReporter"
        )
        val body = NetworkClient.gson.toJson(payload)

        return@withContext try {
            val response = withTimeoutOrNull(HTTP_TIMEOUT_MS) {
                NetworkClient.post(url, headers, body)
            } ?: return@withContext Result.Transient("timeout after ${HTTP_TIMEOUT_MS}ms")

            val parsed = parseCreateResponse(response)
            // Never log the response body at INFO — it contains issue URL which
            // is harmless, but GitHub error responses can echo the request (and
            // we'd rather not print anything token-shaped in logs at all).
            Log.i(TAG, "GitHub issue created — #${parsed.issueNumber}")
            parsed
        } catch (e: LlmException) {
            // NetworkClient throws LlmException on HTTP 4xx/5xx with "HTTP <code>: …".
            classifyHttpFailure(e.message.orEmpty())
        } catch (e: IOException) {
            Log.w(TAG, "Network error while creating issue: ${e.javaClass.simpleName}")
            Result.Transient("network: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error while creating issue", e)
            Result.Permanent("unexpected: ${e.javaClass.simpleName}")
        }
    }

    private fun parseCreateResponse(jsonBody: String): Result.Success = try {
        val root = NetworkClient.gson.fromJson(jsonBody, CreateResponse::class.java)
        Result.Success(issueNumber = root?.number, htmlUrl = root?.html_url)
    } catch (_: Exception) {
        // Shouldn't happen on 2xx; if it does, treat the submit as successful
        // — the issue IS created even if we can't parse the echo.
        Result.Success(null, null)
    }

    /**
     * Translate an HTTP error from [NetworkClient.post] into the typed result.
     * GitHub uses standard statuses; we pick the handful that matter here:
     *   401 / 403      → auth or rate-limit-by-token
     *   404            → repo not found (permanent, requires user fix)
     *   429            → rate-limited
     *   5xx            → transient
     *   other 4xx      → permanent
     */
    private fun classifyHttpFailure(message: String): Result {
        val code = Regex("""HTTP (\d{3})""").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when (code) {
            401          -> Result.AuthFailure
            403          -> if (message.contains("rate limit", ignoreCase = true)) Result.RateLimited(60_000L) else Result.AuthFailure
            404          -> Result.RepoNotFound
            429          -> Result.RateLimited(60_000L)
            in 500..599  -> Result.Transient("server $code")
            null         -> Result.Transient("unknown")
            else         -> Result.Permanent("HTTP $code")
        }
    }

    // Minimal wire shape — only the fields we actually read.
    private data class CreateResponse(val number: Int?, val html_url: String?)
}
