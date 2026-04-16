package com.jarvis.assistant.remote.openclaw

/**
 * JSON request payload sent to the OpenClaw /gateway WebSocket.
 *
 * Serialised manually with org.json to avoid Gson local-class obfuscation.
 */
data class OpenClawRequest(
    val requestId:      String,
    val transcript:     String,
    val routeType:      RouteType,
    val sessionId:      String,
    val timeoutMs:      Long,
    val isVoiceRequest: Boolean = true,
    val deviceContext:  String  = "android"
) {
    /** Produce the JSON string to send over the WebSocket. */
    fun toJson(): String {
        val escaped = transcript
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"requestId":"$requestId","transcript":"$escaped","routeType":"${routeType.name}","sessionId":"$sessionId","timeoutMs":$timeoutMs,"isVoiceRequest":$isVoiceRequest,"deviceContext":"$deviceContext"}"""
    }
}
