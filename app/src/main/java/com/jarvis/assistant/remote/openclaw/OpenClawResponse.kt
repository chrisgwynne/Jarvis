package com.jarvis.assistant.remote.openclaw

import org.json.JSONObject

/**
 * JSON response received from OpenClaw.
 *
 * Parsed manually with org.json.
 */
data class OpenClawResponse(
    val requestId:         String,
    val status:            String,       // "ok" | "error" | "auth_failed"
    val spokenSummary:     String,       // short spoken reply
    val fullText:          String,       // full detail (may be empty)
    val needsConfirmation: Boolean,
    val isLongJob:         Boolean,
    val errorCode:         String,       // "" when no error
    val debugMessage:      String        // "" in production
) {
    val isSuccess: Boolean get() = status == "ok"
    val isAuthFailure: Boolean get() = status == "auth_failed"

    companion object {
        /**
         * Parse from a raw WebSocket text frame.
         * Returns null if the JSON is structurally invalid.
         */
        fun fromJson(json: String): OpenClawResponse? = try {
            val obj = JSONObject(json)
            OpenClawResponse(
                requestId         = obj.optString("requestId"),
                status            = obj.optString("status", "error"),
                spokenSummary     = obj.optString("spokenSummary"),
                fullText          = obj.optString("fullText"),
                needsConfirmation = obj.optBoolean("needsConfirmation", false),
                isLongJob         = obj.optBoolean("isLongJob", false),
                errorCode         = obj.optString("errorCode"),
                debugMessage      = obj.optString("debugMessage")
            )
        } catch (_: Exception) {
            null
        }
    }
}
