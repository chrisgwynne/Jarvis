package com.jarvis.assistant.remote.openclaw

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Low-level WebSocket transport for OpenClaw.
 *
 * Design choices:
 *   • Dedicated [OkHttpClient] with readTimeout=0 — timeouts are managed by
 *     [withTimeoutOrNull] so we get clean coroutine cancellation instead of
 *     relying on OkHttp's internal thread.
 *   • Each [send] call opens a fresh connection — stateless, no reconnect logic
 *     needed.  OpenClaw requests are short-lived (<30 s typical).
 *   • [suspendCancellableCoroutine] bridges the OkHttp callback into a coroutine.
 *     [invokeOnCancellation] tears down the WebSocket if the parent coroutine is
 *     cancelled (e.g. the user says "stop").
 */
class OpenClawClient {

    companion object {
        private const val TAG = "OpenClawClient"
    }

    /** Dedicated client: read timeout disabled — we manage it ourselves. */
    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)    // managed by withTimeoutOrNull
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Open a WebSocket to [OpenClawConnectionBuilder.buildWsEndpoint], send
     * [request] as JSON, and wait for one response frame.
     *
     * Returns:
     *   - [OpenClawExecutionResult.Success] on a valid ok response
     *   - [OpenClawExecutionResult.Failure] for every error condition
     *
     * Never throws — all exceptions are converted to [OpenClawExecutionResult.Failure].
     */
    suspend fun send(
        settings: OpenClawSettings,
        request:  OpenClawRequest
    ): OpenClawExecutionResult = withContext(Dispatchers.IO) {

        val url = OpenClawConnectionBuilder.buildWsEndpoint(settings)

        val result: OpenClawExecutionResult? = withTimeoutOrNull(settings.timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val wsRequest = Request.Builder()
                    .url(url)
                    .apply {
                        if (settings.authToken.isNotBlank()) {
                            header("Authorization", "Bearer ${settings.authToken}")
                        }
                    }
                    .build()

                var ws: WebSocket? = null

                ws = wsClient.newWebSocket(wsRequest, object : WebSocketListener() {

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(request.toJson())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d(TAG, "Frame received for ${request.requestId}")
                        webSocket.close(1000, "done")

                        val parsed = OpenClawResponse.fromJson(text)
                        if (parsed == null) {
                            cont.resume(
                                OpenClawExecutionResult.Failure(OpenClawError.MalformedResponse)
                            )
                            return
                        }
                        if (parsed.requestId.isNotBlank() &&
                            parsed.requestId != request.requestId) {
                            // Stale frame from a previous connection — ignore
                            return
                        }
                        val execResult = when {
                            parsed.isAuthFailure ->
                                OpenClawExecutionResult.Failure(OpenClawError.AuthFailed)
                            !parsed.isSuccess ->
                                OpenClawExecutionResult.Failure(
                                    OpenClawError.TaskFailed(parsed.errorCode.ifBlank { "unknown" })
                                )
                            else ->
                                OpenClawExecutionResult.Success(
                                    spokenSummary = parsed.spokenSummary,
                                    fullText      = parsed.fullText
                                )
                        }
                        cont.resume(execResult)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        Log.w(TAG, "WS failure: ${t.message}")
                        if (cont.isActive) {
                            val error = when {
                                response?.code == 401 || response?.code == 403 ->
                                    OpenClawError.AuthFailed
                                t is java.net.UnknownHostException ||
                                t is java.net.ConnectException ->
                                    OpenClawError.Unreachable(t.message ?: "")
                                else ->
                                    OpenClawError.Unreachable(t.message ?: "")
                            }
                            cont.resume(OpenClawExecutionResult.Failure(error))
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        // If we haven't resumed yet, the server closed without sending a frame
                        if (cont.isActive) {
                            cont.resume(
                                OpenClawExecutionResult.Failure(OpenClawError.ConnectionDropped)
                            )
                        }
                    }
                })

                cont.invokeOnCancellation {
                    ws?.cancel()
                }
            }
        }

        result ?: OpenClawExecutionResult.Failure(OpenClawError.TimedOut)
    }
}
