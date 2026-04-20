package com.jarvis.assistant.core.routines

import android.content.Context
import android.util.Log
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.events.EventBus
import com.jarvis.assistant.core.events.EventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque

/**
 * RoutineSynthesizer — observes every TOOL_EXECUTED event and counts how
 * often 2–4 consecutive tools recur together inside the same time-of-day
 * bucket. When a sequence passes [proposeThreshold] unique occurrences
 * AND it hasn't been proposed yet, the synthesiser enqueues a proposal
 * for [com.jarvis.assistant.core.decisions.triggers.RoutineProposalTrigger]
 * to surface on the next tick.
 *
 * This is deliberately lightweight: counts live in SharedPreferences, no
 * Room, no ML. The heuristic is "same tool names, same rough hour, seen
 * together more than twice" — enough to notice genuine routines while
 * avoiding noise from one-off voice commands.
 */
class RoutineSynthesizer(
    context: Context,
    /** Minimum distinct same-bucket observations before a sequence is proposed. */
    private val proposeThreshold: Int = 3,
    /** Max gap between two consecutive tool calls in the same sequence. */
    private val sequenceWindowMs: Long = 10 * 60 * 1000L,
    /** How many recent tool calls to consider when forming sequence candidates. */
    private val memoryDepth: Int = 4,
) {
    data class Proposal(
        val id: String,
        val steps: List<String>,
        val timeBucket: Int,
        val observations: Int,
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val recent = ArrayDeque<Entry>()
    private val lock = Any()
    private val pendingProposals = ArrayDeque<Proposal>()

    private data class Entry(val toolName: String, val tsMs: Long)

    fun attach(flow: Flow<Event> = EventBus.events) {
        if (job != null) return
        job = scope.launch {
            try {
                flow.collect { if (it.kind == EventKind.TOOL_EXECUTED) onToolExecuted(it) }
            } catch (e: Exception) {
                Log.w(TAG, "subscription failed: ${e.message}")
            }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        synchronized(lock) {
            recent.clear()
            pendingProposals.clear()
        }
    }

    /** Pull any proposals ready to surface and clear them. */
    fun drainProposals(): List<Proposal> = synchronized(lock) {
        val out = pendingProposals.toList()
        pendingProposals.clear()
        out
    }

    private fun onToolExecuted(event: Event) {
        val toolName = event.payload["tool"]?.takeIf { it.isNotBlank() } ?: return
        if (toolName in IGNORE_TOOLS) return
        val now = event.tsMillis
        val proposals = synchronized(lock) {
            evictStale(now)
            recent.addLast(Entry(toolName, now))
            while (recent.size > memoryDepth) recent.removeFirst()
            collectNewProposals(now)
        }
        synchronized(lock) { pendingProposals.addAll(proposals) }
    }

    private fun evictStale(now: Long) {
        val cutoff = now - sequenceWindowMs
        while (recent.isNotEmpty() && recent.peekFirst().tsMs < cutoff) recent.removeFirst()
    }

    private fun collectNewProposals(now: Long): List<Proposal> {
        if (recent.size < 2) return emptyList()
        val bucket = hourBucket(now)
        val entries = recent.toList()
        val proposals = mutableListOf<Proposal>()
        for (length in 2..entries.size.coerceAtMost(memoryDepth)) {
            val tail = entries.takeLast(length)
            val steps = tail.map { it.toolName }
            if (steps.distinct().size < 2) continue
            val key = sequenceKey(steps, bucket)
            val count = prefs.getInt(key, 0) + 1
            prefs.edit().putInt(key, count).apply()
            val proposedFlag = "$key$PROPOSED_SUFFIX"
            if (count >= proposeThreshold && !prefs.getBoolean(proposedFlag, false)) {
                prefs.edit().putBoolean(proposedFlag, true).apply()
                proposals.add(
                    Proposal(
                        id = key,
                        steps = steps,
                        timeBucket = bucket,
                        observations = count,
                    )
                )
            }
        }
        return proposals
    }

    private fun sequenceKey(steps: List<String>, bucket: Int): String =
        "seq_${bucket}_${steps.joinToString("|")}"

    private fun hourBucket(nowMs: Long): Int =
        Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).hour

    companion object {
        private const val TAG = "RoutineSynthesizer"
        private const val PREFS = "jarvis_routine_synth"
        private const val PROPOSED_SUFFIX = "_proposed"
        private val IGNORE_TOOLS = setOf(
            "save_routine", "run_routine", "list_routines", "delete_routine",
            "undo_last_action", "repeat_last_action", "mute_suggestion",
        )
    }
}
