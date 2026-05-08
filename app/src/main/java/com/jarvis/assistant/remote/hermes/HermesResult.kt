package com.jarvis.assistant.remote.hermes

/**
 * Sealed outcome for every [HermesJobsClient] call.  Mirrors the
 * never-throws contract of OpenClawClient — the call site decides how to
 * surface each branch (banner, retry, fall back to local scheduling).
 */
sealed class HermesResult {

    /** 2xx response.  [body] is the raw JSON payload returned by Hermes. */
    data class Ok(val body: String) : HermesResult()

    /** HTTP 401 / 403 — bearer token rejected or missing. */
    data object AuthFailed : HermesResult()

    /** Non-2xx response that wasn't auth-related. */
    data class HttpError(val message: String) : HermesResult()

    /** Network-level failure (DNS, TCP, TLS) — caller may retry. */
    data class NetworkError(val message: String) : HermesResult()

    /** Per-call timeout exceeded ([HermesSettings.timeoutMs]). */
    data object Timeout : HermesResult()
}
