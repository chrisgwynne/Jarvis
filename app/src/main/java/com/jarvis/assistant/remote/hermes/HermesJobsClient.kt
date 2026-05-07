package com.jarvis.assistant.remote.hermes

import android.util.Log
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * HermesJobsClient — REST wrapper around Hermes Agent's /api/jobs surface.
 *
 * Hermes exposes a CRUD + lifecycle API for scheduled background runs:
 *
 *   GET    /api/jobs                    list
 *   POST   /api/jobs                    create  (body: cron-format JSON)
 *   GET    /api/jobs/{job_id}           fetch one (incl. last-run state)
 *   PATCH  /api/jobs/{job_id}           update
 *   DELETE /api/jobs/{job_id}           delete (cancels in-flight runs)
 *   POST   /api/jobs/{job_id}/pause     suspend scheduling
 *   POST   /api/jobs/{job_id}/resume    resume
 *   POST   /api/jobs/{job_id}/run       run now (out-of-band)
 *
 * All endpoints require the same Bearer auth as the chat completions API
 * (the API_SERVER_KEY).  This client mirrors the OpenClawClient style:
 *
 *   • One shared OkHttp instance via NetworkClient.
 *   • Per-call timeout via [HermesSettings.timeoutMs].
 *   • Never throws; outcomes expressed as [HermesResult].
 *   • Returns raw JSON response bodies — the app's job-management surface
 *     can deserialise them locally without us pinning a schema that may
 *     drift between Hermes versions.
 *
 * Not exposed as a tool yet — that's the next layer.  This class is the
 * transport.  A higher-level tool can wrap [list]/[create]/[runNow] and
 * surface them through the assistant ("schedule a Hermes job to summarise
 * my Slack at 9am").
 */
class HermesJobsClient {

    companion object {
        private const val TAG = "HermesJobsClient"
    }

    suspend fun list(settings: HermesSettings): HermesResult =
        request(settings, verb = Verb.GET,    path = "/api/jobs",                              body = null)

    suspend fun create(settings: HermesSettings, jobJson: String): HermesResult =
        request(settings, verb = Verb.POST,   path = "/api/jobs",                              body = jobJson)

    suspend fun get(settings: HermesSettings, jobId: String): HermesResult =
        request(settings, verb = Verb.GET,    path = "/api/jobs/${encode(jobId)}",             body = null)

    suspend fun update(settings: HermesSettings, jobId: String, patchJson: String): HermesResult =
        request(settings, verb = Verb.PATCH,  path = "/api/jobs/${encode(jobId)}",             body = patchJson)

    suspend fun delete(settings: HermesSettings, jobId: String): HermesResult =
        request(settings, verb = Verb.DELETE, path = "/api/jobs/${encode(jobId)}",             body = null)

    suspend fun pause(settings: HermesSettings, jobId: String): HermesResult =
        request(settings, verb = Verb.POST,   path = "/api/jobs/${encode(jobId)}/pause",       body = "")

    suspend fun resume(settings: HermesSettings, jobId: String): HermesResult =
        request(settings, verb = Verb.POST,   path = "/api/jobs/${encode(jobId)}/resume",      body = "")

    suspend fun runNow(settings: HermesSettings, jobId: String): HermesResult =
        request(settings, verb = Verb.POST,   path = "/api/jobs/${encode(jobId)}/run",         body = "")

    // ── Internals ──────────────────────────────────────────────────────────

    private enum class Verb { GET, POST, PATCH, DELETE }

    private suspend fun request(
        settings: HermesSettings,
        verb: Verb,
        path: String,
        body: String?,
    ): HermesResult = withContext(Dispatchers.IO) {
        val url = settings.baseOrigin().trimEnd('/') + path
        val headers = buildMap<String, String> {
            put("Content-Type", "application/json")
            if (settings.apiKey.isNotBlank()) {
                put("Authorization", "Bearer ${settings.apiKey}")
            }
        }

        val outcome = withTimeoutOrNull(settings.timeoutMs) {
            try {
                val response = when (verb) {
                    Verb.GET    -> NetworkClient.get(url, headers)
                    Verb.POST   -> NetworkClient.post(url, headers, body ?: "")
                    Verb.PATCH  -> NetworkClient.patch(url, headers, body ?: "")
                    Verb.DELETE -> NetworkClient.delete(url, headers)
                }
                HermesResult.Ok(response)
            } catch (e: LlmException) {
                val auth = e.message?.contains("HTTP 401") == true ||
                           e.message?.contains("HTTP 403") == true
                if (auth) HermesResult.AuthFailed
                else HermesResult.HttpError(e.message ?: "HTTP error")
            } catch (e: IOException) {
                HermesResult.NetworkError(e.message ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected ${verb.name} ${path} error: ${e.message}", e)
                HermesResult.HttpError(e.message ?: "Unexpected error")
            }
        }
        outcome ?: HermesResult.Timeout
    }

    /**
     * Path-segment URL encoder — Hermes job IDs may contain slashes or
     * URL-reserved characters depending on operator naming conventions.
     */
    private fun encode(segment: String): String =
        java.net.URLEncoder.encode(segment, Charsets.UTF_8.name())
}
