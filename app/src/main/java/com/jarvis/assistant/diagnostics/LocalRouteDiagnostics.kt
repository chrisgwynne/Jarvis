package com.jarvis.assistant.diagnostics

import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * LocalRouteDiagnostics — a small in-memory ring buffer of the last N
 * local-tool dispatches, surfaced by the Settings → Diagnostics screen.
 *
 * Every entry captures the contract the sprint defines:
 *
 *   - transcript (raw + normalised)
 *   - matched intent
 *   - tool that handled it
 *   - extracted slots (best-effort string map)
 *   - route — always "LOCAL" for entries from this buffer; the field
 *     exists so a future remote tracker can share the same shape
 *   - result — "success" / "failure(<reason>)"
 *   - total latency in ms
 *   - whether OpenClaw / Hermes / memory / LLM were touched (must be
 *     false for phone-capable intents — used by the diagnostics UI to
 *     flag regressions in red)
 *
 * Thread-safe (concurrent deque), fixed capacity, lock-free.  The
 * runtime calls [record] on every successful local dispatch; the UI
 * reads via [snapshot] / [stateFlow].
 */
object LocalRouteDiagnostics {

    private const val TAG = "LocalRouteDiag"
    private const val CAPACITY = 30

    data class Entry(
        val timestampMs: Long,
        val transcript: String,
        val normalisedTranscript: String,
        val intent: String,
        val tool: String,
        val slots: Map<String, String>,
        val route: String,
        val result: String,
        val latencyMs: Long,
        val remoteTouched: Boolean,
    )

    private val ring = ConcurrentLinkedDeque<Entry>()
    private val total = AtomicLong(0)

    private val _stateFlow = kotlinx.coroutines.flow.MutableStateFlow<List<Entry>>(emptyList())
    val stateFlow: kotlinx.coroutines.flow.StateFlow<List<Entry>> = _stateFlow

    /**
     * Append a new route entry.  Fire-and-forget — never throws.
     * Older entries are dropped when [CAPACITY] is exceeded.
     */
    fun record(
        transcript: String,
        normalisedTranscript: String,
        intent: String,
        tool: String,
        slots: Map<String, String> = emptyMap(),
        result: String,
        latencyMs: Long,
        remoteTouched: Boolean = false,
        route: String = "LOCAL",
    ) {
        try {
            val e = Entry(
                timestampMs           = System.currentTimeMillis(),
                transcript            = transcript.take(160),
                normalisedTranscript  = normalisedTranscript.take(160),
                intent                = intent,
                tool                  = tool,
                slots                 = slots.mapValues { it.value.take(80) },
                route                 = route,
                result                = result,
                latencyMs             = latencyMs,
                remoteTouched         = remoteTouched,
            )
            ring.addFirst(e)
            while (ring.size > CAPACITY) ring.pollLast()
            total.incrementAndGet()
            _stateFlow.value = ring.toList()
            Log.d(TAG, "[LOCAL_ROUTE_RECORDED] intent=$intent tool=$tool " +
                "latency=${latencyMs}ms result=$result")
        } catch (_: Throwable) { /* never break the dispatch path */ }
    }

    fun snapshot(): List<Entry> = ring.toList()

    /** Total local routes recorded since process start — diagnostics
     *  uses this to show "ever recorded N local commands". */
    fun totalCount(): Long = total.get()

    fun clear() {
        ring.clear()
        _stateFlow.value = emptyList()
    }
}
