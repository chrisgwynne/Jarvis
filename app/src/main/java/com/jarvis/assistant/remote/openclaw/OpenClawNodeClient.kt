package com.jarvis.assistant.remote.openclaw

import android.util.Log
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenClawNodeClient — registers Jarvis as an OpenClaw node over a persistent WebSocket.
 *
 * ## Protocol flow
 *
 * 1. Connect to `ws(s)://host:port/gateway`
 * 2. Receive `connect.challenge` event → extract nonce
 * 3. Send `connect` req: role=node, commands list, auth token, device identity
 * 4. Receive `hello-ok` → persist deviceToken for future reconnects
 * 5. Keep alive by responding to `tick` events passively (no explicit reply needed)
 * 6. Receive inbound `req` frames → dispatch to [onInvoke] → send `res` back
 *
 * Reconnects with exponential backoff (1 s → 2 s → … → 60 s cap).
 *
 * @param settingsRepo       Provides the current snapshot and persists deviceToken.
 * @param availableCommands  Returns the list of command names to advertise on connect.
 * @param onInvoke           Suspend lambda that executes a command; returns spoken result.
 */
class OpenClawNodeClient(
    private val settingsRepo: OpenClawSettingsRepository,
    private val availableCommands: () -> List<String>,
    private val onInvoke: suspend (command: String, args: Map<String, Any>) -> String,
) {

    private val _status = MutableStateFlow(OpenClawNodeStatus.DISABLED)
    val status: StateFlow<OpenClawNodeStatus> = _status.asStateFlow()

    private fun setStatus(s: OpenClawNodeStatus) {
        _status.value = s
        _sharedStatus.value = s
    }

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var pendingNonce: String = ""

    // OkHttp client with no read timeout — WebSocket must stay open indefinitely
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) return
        setStatus(OpenClawNodeStatus.CONNECTING)
        scope.launch { connectLoop() }
    }

    fun stop() {
        running.set(false)
        ws?.close(1000, "Service stopping")
        ws = null
        setStatus(OpenClawNodeStatus.DISABLED)
        scope.cancel()
    }

    // ── Connection loop with exponential backoff ──────────────────────────────

    private suspend fun connectLoop() {
        var backoffMs = 1_000L
        while (running.get()) {
            val settings = settingsRepo.snapshot()
            if (!settings.isFullyConfigured || !settings.nodeEnabled) {
                setStatus(OpenClawNodeStatus.DISABLED)
                return
            }
            setStatus(OpenClawNodeStatus.CONNECTING)
            connect(settings)
            if (!running.get()) break
            // Reconnect after backoff
            Log.d(TAG, "Reconnecting in ${backoffMs}ms")
            setStatus(OpenClawNodeStatus.RECONNECTING)
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private fun connect(settings: OpenClawSettings) {
        val url = OpenClawConnectionBuilder.buildWsEndpoint(settings)
        Log.d(TAG, "Connecting node to $url")

        val request = Request.Builder().url(url).build()
        ws = httpClient.newWebSocket(request, Listener(settings))
    }

    // ── Outbound frame helpers ────────────────────────────────────────────────

    private fun sendConnect(settings: OpenClawSettings) {
        val correlationId = "connect-${UUID.randomUUID()}"
        // Use stored deviceToken on reconnect; empty string on first connect
        val authToken = settings.deviceToken.ifBlank { settings.authToken }

        val frame = JSONObject().apply {
            put("type", "req")
            put("id", correlationId)
            put("method", "connect")
            put("params", JSONObject().apply {
                put("minProtocol", 1)
                put("maxProtocol", 1)
                put("client", JSONObject().apply {
                    put("id", "jarvis-android")
                    put("version", "1.0.0")
                    put("platform", "android")
                    put("mode", "node")
                })
                put("role", "node")
                put("scopes",  org.json.JSONArray())
                put("caps",    org.json.JSONArray())
                put("commands", org.json.JSONArray(availableCommands()))
                put("permissions", JSONObject())
                put("auth", JSONObject().apply {
                    if (authToken.isNotBlank()) put("token", authToken)
                })
                put("locale",    "en-US")
                put("userAgent", "Jarvis/1.0 Android")
                put("device", JSONObject().apply {
                    put("id",          settings.deviceId)
                    put("publicKey",   "")
                    put("signature",   "")
                    put("signedAt",    0)
                    put("nonce",       pendingNonce)
                })
            })
        }
        ws?.send(frame.toString())
        Log.d(TAG, "Sent connect req (id=$correlationId, commands=${availableCommands().size})")
    }

    private fun sendResult(requestId: String, result: String) {
        val frame = JSONObject().apply {
            put("type", "res")
            put("id", requestId)
            put("ok", true)
            put("payload", JSONObject().apply { put("result", result) })
        }
        ws?.send(frame.toString())
    }

    private fun sendError(requestId: String, code: String, message: String) {
        val frame = JSONObject().apply {
            put("type", "res")
            put("id", requestId)
            put("ok", false)
            put("error", JSONObject().apply {
                put("details", JSONObject().apply {
                    put("code", code)
                    put("message", message)
                })
            })
        }
        ws?.send(frame.toString())
    }

    // ── Inbound frame handling ────────────────────────────────────────────────

    private fun handleMessage(text: String, settings: OpenClawSettings) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val type = json.optString("type")

        when (type) {
            "event" -> handleEvent(json, settings)
            "req"   -> handleInvokeReq(json)
            "res"   -> handleResponse(json, settings)
            else    -> Log.v(TAG, "Unknown frame type: $type")
        }
    }

    private fun handleEvent(json: JSONObject, settings: OpenClawSettings) {
        val event = json.optString("event")
        when (event) {
            "connect.challenge" -> {
                pendingNonce = json.optJSONObject("payload")?.optString("nonce") ?: ""
                Log.d(TAG, "Got challenge, nonce=${pendingNonce.take(8)}…")
                sendConnect(settings)
            }
            "tick" -> {
                // Gateway keepalive — no reply needed, just log at verbose level
                Log.v(TAG, "tick seq=${json.optInt("seq")}")
            }
            else -> Log.v(TAG, "Event: $event")
        }
    }

    private fun handleResponse(json: JSONObject, settings: OpenClawSettings) {
        val ok      = json.optBoolean("ok", false)
        val payload = json.optJSONObject("payload") ?: return
        val payType = payload.optString("type")

        if (payType == "hello-ok" && ok) {
            // Pairing approved — persist the deviceToken if provided
            val deviceToken = payload.optJSONObject("auth")?.optString("deviceToken") ?: ""
            if (deviceToken.isNotBlank()) {
                settingsRepo.saveDeviceToken(deviceToken)
                Log.d(TAG, "Stored deviceToken for future reconnects")
            }
            val connId = payload.optJSONObject("server")?.optString("connId") ?: "?"
            Log.i(TAG, "Connected as node (connId=$connId)")
            setStatus(OpenClawNodeStatus.CONNECTED)
        } else if (!ok) {
            val code = json.optJSONObject("error")?.optJSONObject("details")?.optString("code") ?: "UNKNOWN"
            if (code == "PENDING_APPROVAL" || code == "AWAITING_APPROVAL") {
                Log.i(TAG, "Node pairing pending admin approval")
                setStatus(OpenClawNodeStatus.PENDING_APPROVAL)
            } else {
                Log.w(TAG, "Connect failed: $code")
                setStatus(OpenClawNodeStatus.ERROR)
            }
        }
    }

    private fun handleInvokeReq(json: JSONObject) {
        val requestId = json.optString("id")
        // The gateway may relay as method=<command> or method=node.invoke with params.command
        val params  = json.optJSONObject("params") ?: JSONObject()
        val command = params.optString("command").ifBlank { json.optString("method") }

        if (command.isBlank() || requestId.isBlank()) return
        if (command == "connect") return  // ignore our own connect echoes

        Log.d(TAG, "Invoke: $command (req $requestId)")

        // Parse args — params itself if direct, or params.args if nested
        val args = (params.optJSONObject("args") ?: params).toMap()

        scope.launch {
            try {
                val result = onInvoke(command, args)
                sendResult(requestId, result)
                Log.d(TAG, "Invoke $command → success")
            } catch (e: Exception) {
                Log.w(TAG, "Invoke $command failed: ${e.message}")
                sendError(requestId, "INVOKE_FAILED", e.message ?: "unknown error")
            }
        }
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private inner class Listener(private val settings: OpenClawSettings) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket open")
            // challenge event arrives next; connect req is sent in handleEvent
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text, settings)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure: ${t.javaClass.simpleName}: ${t.message}")
            if (running.get()) setStatus(OpenClawNodeStatus.RECONNECTING)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code")
            if (running.get()) setStatus(OpenClawNodeStatus.RECONNECTING)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key -> map[key] = get(key) }
        return map
    }

    companion object {
        private const val TAG = "OpenClawNode"

        // Shared status observable — SettingsViewModel reads this without needing a
        // direct reference to the running client instance.
        private val _sharedStatus = MutableStateFlow(OpenClawNodeStatus.DISABLED)
        val sharedStatus: StateFlow<OpenClawNodeStatus> = _sharedStatus.asStateFlow()
    }
}
