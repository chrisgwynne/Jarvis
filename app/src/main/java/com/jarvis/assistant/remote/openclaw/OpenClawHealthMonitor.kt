package com.jarvis.assistant.remote.openclaw

import android.util.Log
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Performs a lightweight HTTP GET to the /health endpoint and maps the result
 * to an [OpenClawConnectionStatus].
 *
 * Used by the Settings UI "Test Connection" button and as a pre-flight check.
 * Reuses [NetworkClient.http] as the base client but applies *tight* call
 * timeouts so a dead OpenClaw doesn't leave the user waiting 30 s on a
 * routine "are you connected?" check.  Bound is 6 s total — enough for a
 * sleepy Tailscale node, short enough that the spoken response feels
 * snappy when the server is just plain down.
 */
object OpenClawHealthMonitor {

    private const val TAG = "OpenClawHealth"

    /**
     * Health-check OkHttp client.  Derived from [NetworkClient.http] so we
     * inherit the connection pool / interceptors, but with much shorter
     * timeouts (the default 30-s connect / 45-s read makes a missing
     * server feel like a bug in the app).
     */
    private val healthHttp by lazy {
        NetworkClient.http.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

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

                val response = healthHttp.newCall(request).execute()
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
