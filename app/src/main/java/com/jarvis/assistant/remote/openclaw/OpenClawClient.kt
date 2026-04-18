package com.jarvis.assistant.remote.openclaw

import android.util.Log
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * HTTP transport for OpenClaw.
 *
 * Sends requests to the OpenAI-compatible [OpenClawConnectionBuilder.buildChatEndpoint]
 * via a standard POST to /v1/chat/completions.  Reuses the shared [NetworkClient.http]
 * OkHttp instance — no dedicated client or WebSocket needed.
 *
 * Never throws — all outcomes are expressed as [OpenClawExecutionResult].
 */
class OpenClawClient {

    companion object {
        private const val TAG = "OpenClawClient"
    }

    /**
     * POST [request] to the OpenClaw chat completions endpoint and return the result.
     *
     * Timeout is managed by [withTimeoutOrNull] using [OpenClawSettings.timeoutMs].
     */
    suspend fun send(
        settings: OpenClawSettings,
        request:  OpenClawRequest
    ): OpenClawExecutionResult = withContext(Dispatchers.IO) {

        val url = OpenClawConnectionBuilder.buildChatEndpoint(settings)
        val headers = buildMap<String, String> {
            put("Content-Type", "application/json")
            if (settings.authToken.isNotBlank()) {
                put("Authorization", "Bearer ${settings.authToken}")
            }
        }
        val body = NetworkClient.gson.toJson(request)

        val result = withTimeoutOrNull(settings.timeoutMs) {
            try {
                val responseBody = NetworkClient.post(url, headers, body)
                val parsed = OpenClawResponse.fromJson(responseBody)
                    ?: return@withTimeoutOrNull OpenClawExecutionResult.Failure(OpenClawError.MalformedResponse)

                if (parsed.isAuthError) {
                    return@withTimeoutOrNull OpenClawExecutionResult.Failure(OpenClawError.AuthFailed)
                }

                val content = parsed.content
                if (content.isBlank()) {
                    return@withTimeoutOrNull OpenClawExecutionResult.Failure(OpenClawError.MalformedResponse)
                }

                OpenClawExecutionResult.Success(spokenSummary = content, fullText = content)

            } catch (e: LlmException) {
                Log.w(TAG, "OpenClaw HTTP error: ${e.message}")
                when {
                    e.message?.contains("401") == true ||
                    e.message?.contains("403") == true ->
                        OpenClawExecutionResult.Failure(OpenClawError.AuthFailed)
                    else ->
                        OpenClawExecutionResult.Failure(OpenClawError.Unreachable(e.message ?: ""))
                }
            } catch (e: IOException) {
                Log.w(TAG, "OpenClaw IO error: ${e.message}")
                OpenClawExecutionResult.Failure(OpenClawError.Unreachable(e.message ?: ""))
            } catch (e: Exception) {
                Log.w(TAG, "OpenClaw unexpected error: ${e.message}")
                OpenClawExecutionResult.Failure(OpenClawError.Unreachable(e.message ?: ""))
            }
        }

        result ?: OpenClawExecutionResult.Failure(OpenClawError.TimedOut)
    }
}
