package com.jarvis.assistant.remote.openclaw

import android.util.Log
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

    private const val TAG = "OpenClawHealth"

    /** Result of a health check — status + human-readable detail explaining the outcome. */
    data class Result(val status: OpenClawConnectionStatus, val detail: String)

    /**
     * Ping [OpenClawConnectionBuilder.buildModelsEndpoint] and return a [Result].
     * Always returns — never throws.
     */
    suspend fun check(settings: OpenClawSettings): Result {
        if (!settings.isFullyConfigured) return Result(OpenClawConnectionStatus.NOT_CONFIGURED, "")

        val url = OpenClawConnectionBuilder.buildModelsEndpoint(settings)
        Log.d(TAG, "Health check → $url")

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
                    Log.d(TAG, "Response: HTTP ${r.code}")
                    when (r.code) {
                        200      -> Result(OpenClawConnectionStatus.CONNECTED, url)
                        401, 403 -> Result(OpenClawConnectionStatus.AUTH_FAILED,
                                       "HTTP ${r.code} — check the auth token")
                        else     -> Result(OpenClawConnectionStatus.INVALID_RESPONSE,
                                       "HTTP ${r.code} from $url")
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Timed out connecting to $url")
                Result(OpenClawConnectionStatus.TIMED_OUT,
                    "No response from ${settings.host}:${settings.port} — server may be down or firewall is dropping packets")
            } catch (e: java.net.UnknownHostException) {
                Log.w(TAG, "DNS resolution failed for '${settings.host}': ${e.message}")
                Result(OpenClawConnectionStatus.UNREACHABLE,
                    "Can't resolve \"${settings.host}\" — wrong hostname, or Tailscale isn't connected")
            } catch (e: java.net.ConnectException) {
                Log.w(TAG, "Connection refused ${settings.host}:${settings.port}: ${e.message}")
                Result(OpenClawConnectionStatus.UNREACHABLE,
                    "Connection refused on port ${settings.port} — is OpenClaw running? Check Windows Firewall allows that port")
            } catch (e: javax.net.ssl.SSLException) {
                Log.w(TAG, "SSL error to $url: ${e.message}")
                Result(OpenClawConnectionStatus.UNREACHABLE,
                    "SSL/TLS error — try disabling \"Secure\" if OpenClaw isn't using HTTPS")
            } catch (e: java.io.IOException) {
                Log.w(TAG, "IO error: ${e.javaClass.simpleName}: ${e.message}")
                Result(OpenClawConnectionStatus.UNREACHABLE,
                    "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error: ${e.javaClass.simpleName}: ${e.message}")
                Result(OpenClawConnectionStatus.INVALID_RESPONSE,
                    "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            }
        }
    }
}
