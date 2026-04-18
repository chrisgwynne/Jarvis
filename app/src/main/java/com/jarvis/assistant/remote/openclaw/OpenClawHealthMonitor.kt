package com.jarvis.assistant.remote.openclaw

import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Performs a lightweight HTTP GET to the /health endpoint and maps the result
 * to an [OpenClawConnectionStatus].
 *
 * Used by the Settings UI "Test Connection" button and as a pre-flight check.
 * Reuses [NetworkClient.http] — no dedicated OkHttp instance needed for health checks.
 */
object OpenClawHealthMonitor {

    /**
     * Ping [OpenClawConnectionBuilder.buildHealthEndpoint] and return a status.
     * Always returns — never throws.
     */
    suspend fun check(settings: OpenClawSettings): OpenClawConnectionStatus {
        if (!settings.isFullyConfigured) return OpenClawConnectionStatus.NOT_CONFIGURED

        val url = OpenClawConnectionBuilder.buildModelsEndpoint(settings)

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .apply {
                        if (settings.authToken.isNotBlank()) {
                            header("Authorization", "Bearer ${settings.authToken}")
                        }
                    }
                    .build()

                val response = NetworkClient.http.newCall(request).execute()
                response.use { r ->
                    when (r.code) {
                        200             -> OpenClawConnectionStatus.CONNECTED
                        401, 403        -> OpenClawConnectionStatus.AUTH_FAILED
                        else            -> OpenClawConnectionStatus.INVALID_RESPONSE
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                OpenClawConnectionStatus.TIMED_OUT
            } catch (e: java.net.UnknownHostException) {
                OpenClawConnectionStatus.UNREACHABLE
            } catch (e: java.io.IOException) {
                OpenClawConnectionStatus.UNREACHABLE
            } catch (e: Exception) {
                OpenClawConnectionStatus.INVALID_RESPONSE
            }
        }
    }
}
