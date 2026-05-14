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
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume
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

    // A new scope is created each time start() is called so that stop() → start()
    // works correctly.  stop() cancels the old scope; start() assigns a fresh one.
    @Volatile private var scope: CoroutineScope = newScope()
    private val running = AtomicBoolean(false)

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var pendingNonce: String = ""

    // OkHttp client with no read timeout — WebSocket must stay open indefinitely.
    // pingInterval sends an OkHttp-level WebSocket ping frame every 30 s so NAT
    // entries and intermediate firewalls don't silently drop the idle connection.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) return
        // Recreate the scope if the previous one was cancelled by stop().
        // Without this, start() → stop() → start() would silently do nothing
        // because scope.launch() is a no-op on a cancelled scope.
        if (!scope.coroutineContext[Job]!!.isActive) {
            scope = newScope()
        }
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
            if (!settings.isFullyConfigured) {
                setStatus(OpenClawNodeStatus.DISABLED)
                return
            }

            // ── Pairing pre-flight ────────────────────────────────────────────
            // The gateway's connect schema (v3+) requires `device.publicKey`
            // AND `device.signature` — a signed challenge.  Until we wire
            // Ed25519 keypair generation + nonce signing, those fields would
            // ship blank and the gateway rejects every frame with code 1008.
            // Reopening the socket every backoff cycle is wasted bandwidth
            // *and* logs noise that drowns real failures.  When neither a
            // persisted deviceToken nor a one-shot pairingCode is present
            // we have nothing valid to send — stop and surface the state.
            //
            // Status drains to UNPAIRED so the Settings UI can offer a
            // pairing-code field; flipping the flag again (or saving a code)
            // calls start() which kicks the loop back to life.
            if (!isPairingMaterialAvailable(settings)) {
                Log.i(TAG, "[OPENCLAW_NODE_DISABLED_UNPAIRED] " +
                    "deviceToken_blank=${settings.deviceToken.isBlank()} " +
                    "pairingCode_blank=${settings.pairingCode.isBlank()} " +
                    "deviceId_blank=${settings.deviceId.isBlank()} — " +
                    "not opening WebSocket until pairing material is provided")
                setStatus(OpenClawNodeStatus.UNPAIRED)
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

    /**
     * Pairing pre-flight check.  We can connect when EITHER:
     *   - A previously approved `deviceToken` is on disk (returning device), OR
     *   - A one-shot `pairingCode` was entered in Settings.
     *
     * Without one of those the gateway will reject every attempt.  Note: in
     * the longer term this gate should also require a generated Ed25519
     * keypair so `device.publicKey` / `signature` are non-empty.  Until that
     * lands the gate is a strict subset — pairing material alone is the
     * minimum we need.
     */
    private fun isPairingMaterialAvailable(settings: OpenClawSettings): Boolean {
        if (settings.deviceId.isBlank()) return false
        return settings.deviceToken.isNotBlank() || settings.pairingCode.isNotBlank()
    }

    // Suspends until the WebSocket session ends (closed or failed).
    // connectLoop calls this and only retries after it returns, preventing
    // multiple overlapping connections from being opened simultaneously.
    private suspend fun connect(settings: OpenClawSettings) =
        suspendCancellableCoroutine<Unit> { cont ->
            val url = OpenClawConnectionBuilder.buildWsEndpoint(settings)
            Log.d(TAG, "Connecting node to $url")
            val request = Request.Builder().url(url).build()

            ws = httpClient.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "[NODE_WS_OPEN] waiting for connect.challenge")
                    // Assign the field so handleEvent / sendResult can find the
                    // socket even on the OkHttp dispatcher thread before
                    // newWebSocket()'s return value is visible to other threads.
                    ws = webSocket
                    // Do NOT send `connect` here.  The gateway always emits a
                    // `connect.challenge` event with a nonce that we MUST sign
                    // with the device private key (see handleEvent).  Sending
                    // before the challenge produces a duplicate connect frame
                    // and forces us to send an unsigned identity, which the
                    // current gateway schema rejects with `must have required
                    // property 'publicKey'`/`'signature'`.
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text, settings)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code $reason")
                    webSocket.close(1000, null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "[NODE_WS_FAILURE] ${t.javaClass.simpleName}: ${t.message} " +
                        "http=${response?.code ?: "-"}")
                    if (running.get()) setStatus(OpenClawNodeStatus.RECONNECTING)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "[NODE_WS_CLOSED] code=$code reason=\"$reason\"")
                    if (running.get()) setStatus(OpenClawNodeStatus.RECONNECTING)
                    if (cont.isActive) cont.resume(Unit)
                }
            })

            cont.invokeOnCancellation { ws?.close(1000, "Service stopping") }
        }

    // ── Outbound frame helpers ────────────────────────────────────────────────

    /**
     * Send the initial `connect` request frame.  Callers from inside the
     * [WebSocketListener] callbacks MUST pass [target] (the `webSocket`
     * parameter), because the [ws] field may not yet be assigned when
     * `onOpen` fires — `httpClient.newWebSocket()` returns the WebSocket
     * synchronously, but the listener can run on the OkHttp dispatcher
     * thread before that assignment is visible.  Without this, the
     * `ws?.send(...)` was a silent no-op and the gateway logged
     * "closed before connect" on every reconnect.
     */
    private fun sendConnect(settings: OpenClawSettings, target: WebSocket? = ws) {
        val correlationId = "connect-${UUID.randomUUID()}"
        // Use stored deviceToken on reconnect; empty string on first connect
        val authToken = settings.deviceToken.ifBlank { settings.authToken }

        // Schema-aligned with the omi-bridge reference client (the only known
        // accepted connect frame):
        //  - protocol 3 (we previously sent 1, which the live gateway rejects)
        //  - client.id "cli" (gateway has an enum of allowed values; ours
        //    was custom; "cli" is the proven-working value, and the role
        //    field below still marks us as a node)
        //  - device.publicKey / .signature / .signedAt are OMITTED when
        //    empty (gateway schema requires non-empty when present, and
        //    accepts the connect with the field absent)
        //  - device.id remains so the gateway can pair us to a stable
        //    identity across reconnects
        val frame = JSONObject().apply {
            put("type", "req")
            put("id", correlationId)
            put("method", "connect")
            put("params", JSONObject().apply {
                put("minProtocol", 3)
                put("maxProtocol", 3)
                put("client", JSONObject().apply {
                    put("id",       "cli")
                    put("version",  "1.0.0")
                    put("platform", "android")
                    put("mode",     "cli")
                })
                put("role", "node")
                put("scopes",  org.json.JSONArray())
                put("caps",    org.json.JSONArray())
                put("commands", org.json.JSONArray(availableCommands()))
                put("permissions", JSONObject())
                put("auth", JSONObject().apply {
                    if (authToken.isNotBlank()) put("token", authToken)
                    // One-shot pairing code from Settings UI.  When the
                    // gateway sees this field it auto-approves the device
                    // instead of requiring `openclaw devices approve`.
                    if (settings.pairingCode.isNotBlank()) {
                        put("pairingCode", settings.pairingCode)
                    }
                })
                put("locale",    "en-US")
                put("userAgent", "Jarvis-Android/1.0")
                // Device block: include `id` (always) and `nonce` (when the
                // gateway has sent us one).  publicKey / signature / signedAt
                // are omitted entirely until we wire keypair signing — the
                // schema rejects empty-string placeholders for these fields.
                put("device", JSONObject().apply {
                    put("id", settings.deviceId)
                    if (pendingNonce.isNotBlank()) put("nonce", pendingNonce)
                })
            })
        }
        val payload = frame.toString()
        // Prefer the explicit target (passed from onOpen) — see KDoc above
        // for the race-condition rationale.  Fall back to the field for
        // callers outside the listener (e.g. handleEvent re-sending after
        // a challenge arrives).
        val effective = target ?: ws
        val sent = effective?.send(payload) ?: false
        Log.d(TAG, "[NODE_CONNECT_REQ_SENT] ok=$sent ws_null=${effective == null} " +
            "id=$correlationId commands=${availableCommands().size} " +
            "pairing=${if (settings.pairingCode.isNotBlank()) "yes" else "no"} " +
            "deviceToken=${if (settings.deviceToken.isNotBlank()) "yes" else "no"} " +
            "bytes=${payload.length}")
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
                Log.d(TAG, "[NODE_CHALLENGE_RX] nonce=${pendingNonce.take(8)}…")
                sendConnect(settings, target = ws)
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
