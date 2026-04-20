package com.jarvis.assistant.tools.smart

import android.util.Log
import com.jarvis.assistant.llm.NetworkClient
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * HomeAssistantWebSocketClient — real-time subscription to Home Assistant
 * state_changed events via the WebSocket API.
 *
 * Handshake:
 *   1. Server sends `{type: "auth_required"}`
 *   2. We reply `{type: "auth", access_token: <token>}`
 *   3. Server sends `{type: "auth_ok"}`; on `auth_invalid` we give up.
 *   4. We send `{id: N, type: "subscribe_events", event_type: "state_changed"}`
 *   5. Server replays `{type: "result", success: true}` then streams
 *      `{type: "event", event: {event_type: "state_changed", data: {...}}}`
 *
 * Consumers read [stateChanges] as a Flow. The client does NOT reconnect
 * automatically — callers (HomeAssistantEventAdapter) wrap in a retry loop.
 */
class HomeAssistantWebSocketClient(
    private val baseUrl: String,
    private val token: String,
) {
    data class StateChange(
        val entityId: String,
        val newState: String,
        val previousState: String?,
        val domain: String,
        val friendlyName: String?,
    )

    private val _stateChanges = MutableSharedFlow<StateChange>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val stateChanges: Flow<StateChange> = _stateChanges.asSharedFlow()

    private val socketRef = AtomicReference<WebSocket?>(null)
    private val subscriptionIdSeq = AtomicInteger(1)

    @Volatile private var authenticated: Boolean = false
    @Volatile private var subscribed: Boolean = false

    /** Open the websocket and drive the auth + subscription handshake. */
    fun connect() {
        val wsUrl = buildWsUrl(baseUrl) ?: run {
            Log.w(TAG, "cannot derive ws:// URL from $baseUrl")
            return
        }
        val req = Request.Builder().url(wsUrl).build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                socketRef.set(null)
                authenticated = false
                subscribed = false
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ws closed: $code $reason")
                socketRef.set(null)
                authenticated = false
                subscribed = false
            }
        }
        socketRef.set(NetworkClient.http.newWebSocket(req, listener))
    }

    fun close() {
        socketRef.getAndSet(null)?.close(1000, "client close")
        authenticated = false
        subscribed = false
    }

    fun isConnected(): Boolean = socketRef.get() != null && subscribed

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val json = try { JSONObject(text) } catch (_: Exception) { return }
        when (json.optString("type")) {
            "auth_required" -> {
                val payload = JSONObject().put("type", "auth").put("access_token", token)
                webSocket.send(payload.toString())
            }
            "auth_ok" -> {
                authenticated = true
                val subId = subscriptionIdSeq.getAndIncrement()
                val sub = JSONObject()
                    .put("id", subId)
                    .put("type", "subscribe_events")
                    .put("event_type", "state_changed")
                webSocket.send(sub.toString())
            }
            "auth_invalid" -> {
                Log.w(TAG, "HA websocket auth rejected")
                close()
            }
            "result" -> {
                if (json.optBoolean("success")) subscribed = true
            }
            "event" -> {
                val event = json.optJSONObject("event") ?: return
                if (event.optString("event_type") != "state_changed") return
                val data = event.optJSONObject("data") ?: return
                val entityId = data.optString("entity_id").ifEmpty { return }
                val newStateObj = data.optJSONObject("new_state") ?: return
                val oldStateObj = data.optJSONObject("old_state")
                val newState = newStateObj.optString("state").ifEmpty { return }
                val previousState = oldStateObj?.optString("state")?.ifEmpty { null }
                val attrs = newStateObj.optJSONObject("attributes")
                val friendly = attrs?.optString("friendly_name")?.ifEmpty { null }
                _stateChanges.tryEmit(
                    StateChange(
                        entityId = entityId,
                        newState = newState,
                        previousState = previousState,
                        domain = entityId.substringBefore('.'),
                        friendlyName = friendly,
                    )
                )
            }
        }
    }

    private fun buildWsUrl(raw: String): String? {
        val trimmed = raw.trimEnd('/')
        return when {
            trimmed.startsWith("wss://") -> "$trimmed/api/websocket"
            trimmed.startsWith("ws://") -> "$trimmed/api/websocket"
            trimmed.startsWith("https://") -> trimmed.replaceFirst("https://", "wss://") + "/api/websocket"
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "ws://") + "/api/websocket"
            else -> null
        }
    }

    companion object { private const val TAG = "HaWebSocket" }
}
