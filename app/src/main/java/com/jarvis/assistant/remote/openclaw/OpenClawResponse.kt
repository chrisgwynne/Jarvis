package com.jarvis.assistant.remote.openclaw

import com.jarvis.assistant.llm.NetworkClient

/**
 * OpenAI-compatible chat completions response from OpenClaw.
 * Parsed via Gson from the HTTP response body.
 */
data class OpenClawResponse(
    val choices: List<Choice>?,
    val error:   ErrorBody?
) {
    data class Choice(val message: Msg?, val finish_reason: String?)
    data class Msg(val role: String?, val content: String?)
    data class ErrorBody(val message: String?, val code: String?)

    /** The assistant's reply text, trimmed. Empty string if the response was malformed. */
    val content: String
        get() = choices?.firstOrNull()?.message?.content?.trim() ?: ""

    /** True when the error body signals an auth problem. */
    val isAuthError: Boolean
        get() = error?.code == "invalid_api_key" ||
                error?.code == "unauthorized" ||
                error?.message?.contains("401") == true

    companion object {
        fun fromJson(json: String): OpenClawResponse? = try {
            NetworkClient.gson.fromJson(json, OpenClawResponse::class.java)
        } catch (_: Exception) {
            null
        }
    }
}
