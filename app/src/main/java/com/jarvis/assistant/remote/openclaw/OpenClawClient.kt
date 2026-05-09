package com.jarvis.assistant.remote.openclaw

import android.util.Log
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP transport for OpenClaw.
 *
 * Sends requests to the OpenAI-compatible [OpenClawConnectionBuilder.buildChatEndpoint]
 * via a standard POST to /v1/chat/completions.
 *
 * Uses a dedicated OkHttp client with no read timeout — the coroutine
 * [withTimeoutOrNull] wrapper in [send] is the sole timeout controller.
 * This prevents the shared 45 s NetworkClient read timeout from silently
 * cutting off slow tool-call responses (e.g. wiki retrieval) before the
 * user-configured OpenClaw timeout fires.
 *
 * Never throws — all outcomes are expressed as [OpenClawExecutionResult].
 */
class OpenClawClient {

    companion object {
        private const val TAG = "OpenClawClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    // Inherits connection pool, protocols, and retry settings from NetworkClient.http
    // but removes the read timeout — withTimeoutOrNull handles cancellation instead.
    private val httpClient: OkHttpClient = NetworkClient.http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private suspend fun post(url: String, headers: Map<String, String>, body: String): String =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val responseBody = r.body?.string() ?: ""
                        if (r.isSuccessful) cont.resume(responseBody)
                        else cont.resumeWithException(
                            LlmException("HTTP ${r.code}: ${responseBody.take(300)}")
                        )
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }
            })
        }

    /**
     * Stream [request] to the OpenClaw chat completions endpoint.
     *
     * Returns a cold [Flow] of raw content tokens parsed from the SSE stream.
     * Callers are responsible for timeout and sentence extraction.
     * Throws [LlmException] on HTTP errors; the flow is cancelled on IO failure.
     */
    fun stream(settings: OpenClawSettings, request: OpenClawRequest): Flow<String> {
        val url = OpenClawConnectionBuilder.buildChatEndpoint(settings)
        val headers = buildMap<String, String> {
            put("Content-Type", "application/json")
            if (settings.authToken.isNotBlank()) put("Authorization", "Bearer ${settings.authToken}")
        }
        val body = NetworkClient.gson.toJson(request.copy(stream = true))
        return flow {
            val req = Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            val call = httpClient.newCall(req)
            try {
                val resp = call.execute()
                if (!resp.isSuccessful) {
                    val b = resp.body?.string()?.take(200) ?: ""
                    throw LlmException("HTTP ${resp.code}: $b")
                }
                resp.use { r ->
                    val src = r.body?.source() ?: throw LlmException("Empty SSE body")
                    while (!src.exhausted() && currentCoroutineContext().isActive) {
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        val data = line.removePrefix("data: ").trim()
                        if (data.isEmpty() || data == "[DONE]") continue
                        val content = try {
                            JSONObject(data)
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content") ?: ""
                        } catch (_: Exception) { "" }
                        if (content.isNotEmpty()) emit(content)
                    }
                }
            } finally {
                if (!call.isCanceled()) call.cancel()
            }
        }.flowOn(Dispatchers.IO)
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
                val responseBody = post(url, headers, body)
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
                val isAuth = e.message?.contains("401") == true ||
                             e.message?.contains("403") == true
                if (isAuth) {
                    // Auth failure on a self-hosted endpoint is almost always
                    // a config drift the operator wants to know about — surface
                    // it as a HIGH non-fatal so the rate-limited issue lands
                    // after the third occurrence inside the cooldown window.
                    com.jarvis.assistant.reporting.github.IssueReporter.get()?.reportHigh(
                        subsystem = "openclaw",
                        category  = "AUTH_FAILED",
                        message   = "OpenClaw rejected the bearer token (${e.message?.take(80)}).",
                        throwable = e,
                    )
                    OpenClawExecutionResult.Failure(OpenClawError.AuthFailed)
                } else {
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
