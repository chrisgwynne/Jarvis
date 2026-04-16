package com.jarvis.assistant.llm

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * NetworkClient — shared OkHttp client used by every LLM provider.
 *
 * WHY A SINGLETON OBJECT?
 *   OkHttpClient creates a thread pool and a connection pool. Creating one per
 *   request wastes resources and defeats connection keep-alive. Android best
 *   practice is one client for the whole app.
 *
 * WHY suspendCancellableCoroutine INSTEAD OF BLOCKING?
 *   LLM calls can take 2–10 seconds. Blocking a thread for that duration wastes
 *   a thread-pool slot. enqueue() puts the call on OkHttp's own executor; the
 *   coroutine suspends (not blocks), freeing the coroutine thread for other work.
 *   If the parent coroutine is cancelled (e.g. service stopped), invokeOnCancellation
 *   aborts the in-flight HTTP call immediately.
 *
 * TIMEOUTS:
 *   30 s connect   — server unreachable
 *   45 s read      — LLM response stream; 45 s balances long replies vs. stuck connection detection
 *   30 s write     — request body upload (small for us)
 */
object NetworkClient {

    val gson: Gson = GsonBuilder().create()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Separate client for SSE streaming — no read timeout because we read
     * the response body incrementally, one token at a time.
     */
    private val streamHttp: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()

    /**
     * POST a URL-encoded form body — used for OAuth token exchange.
     * [body] should be a pre-encoded string like "grant_type=...&code=..."
     */
    suspend fun postForm(url: String, body: String): String =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(FORM_MEDIA))
                .build()
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val responseBody = r.body?.string() ?: ""
                        if (r.isSuccessful) cont.resume(responseBody)
                        else cont.resumeWithException(
                            LlmException("OAuth HTTP ${r.code}: ${responseBody.take(300)}")
                        )
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }
            })
        }

    /**
     * HTTP GET [url] with optional [headers]. Returns the response body as a String.
     * Throws [LlmException] on HTTP 4xx/5xx, [IOException] on network failure.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .get()
            .build()
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = r.body?.string() ?: ""
                    if (r.isSuccessful) cont.resume(body)
                    else cont.resumeWithException(LlmException("HTTP ${r.code}: ${body.take(200)}"))
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
        })
    }

    /**
     * POST [body] (a JSON string) to [url] with the given [headers].
     * Returns the response body as a String on success.
     * Throws [LlmException] on HTTP 4xx/5xx.
     * Throws [IOException] on network failure (caller should retry if appropriate).
     */
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String
    ): String = suspendCancellableCoroutine { cont ->

        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val call = http.newCall(request)

        // If the coroutine scope is cancelled, abort the HTTP call immediately
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // response.use {} closes the body automatically (important — OkHttp
                // leaks connections if the body is not closed)
                response.use { r ->
                    val responseBody = r.body?.string() ?: ""
                    if (r.isSuccessful) {
                        cont.resume(responseBody)
                    } else {
                        // Truncate long error pages to keep the log readable
                        val preview = responseBody.take(300)
                        cont.resumeWithException(
                            LlmException("HTTP ${r.code}: $preview")
                        )
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                // Network-level failure (no internet, DNS, timeout) — caller retries
                cont.resumeWithException(e)
            }
        })
    }

    /**
     * POST [body] to [url] and read the Server-Sent Events (SSE) response as a
     * [Flow] of raw data values (the JSON string after "data: " on each line).
     *
     * "[DONE]" sentinels and blank/comment lines are filtered out.
     * Runs on [Dispatchers.IO]; the call is cancelled when the flow is cancelled.
     */
    fun postStream(
        url: String,
        headers: Map<String, String>,
        body: String
    ): Flow<String> = flow {
        val call = streamHttp.newCall(
            Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
        )
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                throw LlmException("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
            response.use { r ->
                val source = r.body?.source()
                    ?: throw LlmException("Empty SSE response body")
                while (!source.exhausted() && currentCoroutineContext().isActive) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data.isNotEmpty() && data != "[DONE]") emit(data)
                    }
                }
            }
        } finally {
            if (!call.isCanceled()) call.cancel()
        }
    }.flowOn(Dispatchers.IO)
}
