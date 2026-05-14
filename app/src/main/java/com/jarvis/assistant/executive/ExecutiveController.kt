package com.jarvis.assistant.executive

import android.util.Log
import com.jarvis.assistant.voice.VoiceFeatureFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ExecutiveController — the persistent "what is Jarvis currently doing /
 * tracking" layer that turns a one-shot voice chatbot into a stateful
 * operating assistant.
 *
 * Scope of this scaffold (intentionally minimal):
 *   • Track in-flight [JarvisTask]s with state + last update time.
 *   • Expose them as a [StateFlow] so the UI / status surfaces can observe.
 *   • Resolve [PriorityDecision] for incoming [AttentionEvent]s without
 *     making any TTS or routing decisions itself — that stays with the
 *     runtime / proactive engine for now.
 *
 * Gated by [VoiceFeatureFlags.Flag.EXECUTIVE_CONTROLLER_ENABLED] (default
 * OFF).  Until that flag flips, every public method is either a no-op or
 * returns a passive verdict — wiring this class into JarvisRuntime cannot
 * regress production behaviour.
 *
 * Designed as a single instance owned by JarvisRuntime.
 */
class ExecutiveController {

    companion object { private const val TAG = "ExecutiveController" }

    // ── Models ────────────────────────────────────────────────────────────────

    enum class TaskState { PENDING, ACTIVE, BLOCKED, COMPLETED, CANCELLED }

    data class JarvisTask(
        val id:           String,
        val title:        String,
        val state:        TaskState,
        val createdAtMs:  Long,
        val updatedAtMs:  Long,
        val origin:       String,         // "voice" | "proactive" | "automation"
        val metadata:     Map<String, String> = emptyMap()
    )

    /** A goal the user has expressed that may span multiple tasks. */
    data class ActiveGoal(
        val id:          String,
        val description: String,
        val taskIds:     List<String>,
        val createdAtMs: Long
    )

    data class PendingAction(
        val id:       String,
        val toolName: String,
        val args:     Map<String, String>,
        val reason:   String
    )

    /** Anything that wants Jarvis's attention.  Routed through [decide]. */
    data class AttentionEvent(
        val source:     String,        // "user_voice" | "notification" | "proactive" | "ha_event"
        val urgency:    Float,         // 0..1
        val relevance:  Float,         // 0..1
        val confidence: Float,         // 0..1
        val payload:    Map<String, String> = emptyMap()
    )

    enum class PriorityVerdict {
        /** Speak now. */                      SPEAK_NOW,
        /** Surface silently in the shade. */  SILENT_NOTIFY,
        /** Defer until current task ends. */  WAIT,
        /** Drop entirely. */                  IGNORE,
        /** Ask the user before acting. */     ASK_CONFIRMATION
    }

    data class PriorityDecision(
        val verdict: PriorityVerdict,
        val reason:  String
    )

    // ── State ─────────────────────────────────────────────────────────────────

    private val tasks    = ConcurrentHashMap<String, JarvisTask>()
    private val goals    = ConcurrentHashMap<String, ActiveGoal>()
    private val _tasksFlow = MutableStateFlow<List<JarvisTask>>(emptyList())
    val tasksFlow: StateFlow<List<JarvisTask>> = _tasksFlow.asStateFlow()

    // ── Task lifecycle ────────────────────────────────────────────────────────

    fun createTask(title: String, origin: String, metadata: Map<String, String> = emptyMap()): String {
        if (!flagOn()) return ""
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val task = JarvisTask(id, title, TaskState.PENDING, now, now, origin, metadata)
        tasks[id] = task
        publish()
        Log.d(TAG, "[TASK_CREATED] id=$id title=\"$title\" origin=$origin")
        return id
    }

    fun updateTaskState(id: String, state: TaskState) {
        if (!flagOn()) return
        val existing = tasks[id] ?: return
        tasks[id] = existing.copy(state = state, updatedAtMs = System.currentTimeMillis())
        publish()
        Log.d(TAG, "[TASK_STATE] id=$id state=$state")
    }

    fun snapshot(): List<JarvisTask> = tasks.values.sortedByDescending { it.updatedAtMs }

    // ── Priority resolution ───────────────────────────────────────────────────

    /**
     * Decide what to do about an [event] given current task state.
     *
     * When the flag is off this always returns [PriorityVerdict.SILENT_NOTIFY]
     * with reason "executive_disabled" so legacy callers cannot get an unsafe
     * SPEAK_NOW until they're explicitly opted in.
     */
    fun decide(event: AttentionEvent): PriorityDecision {
        if (!flagOn()) {
            return PriorityDecision(PriorityVerdict.SILENT_NOTIFY, "executive_disabled")
        }

        val activeTaskCount = tasks.values.count { it.state == TaskState.ACTIVE }

        val score = event.urgency * 0.5f + event.relevance * 0.3f + event.confidence * 0.2f
        val verdict = when {
            event.confidence < 0.30f                            -> PriorityVerdict.IGNORE
            event.urgency >= 0.80f                              -> PriorityVerdict.SPEAK_NOW
            event.urgency >= 0.55f && activeTaskCount == 0      -> PriorityVerdict.SPEAK_NOW
            event.urgency >= 0.40f                              -> PriorityVerdict.SILENT_NOTIFY
            event.confidence in 0.30f..0.55f                    -> PriorityVerdict.ASK_CONFIRMATION
            else                                                -> PriorityVerdict.WAIT
        }
        val decision = PriorityDecision(
            verdict = verdict,
            reason  = "score=%.2f active_tasks=%d source=%s".format(score, activeTaskCount, event.source)
        )
        Log.d(TAG, "[ATTENTION_DECISION] $decision")
        return decision
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun flagOn() =
        VoiceFeatureFlags.isEnabled(VoiceFeatureFlags.Flag.EXECUTIVE_CONTROLLER_ENABLED)

    private fun publish() {
        _tasksFlow.value = snapshot()
    }
}
