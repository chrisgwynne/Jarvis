package com.jarvis.assistant.session

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "SessionStateEngine"

/**
 * Process-wide session state tracker.
 *
 * Owns the current [JarvisSession] and exposes it as a [StateFlow] for the
 * diagnostics UI to observe.  All mutation methods are thread-safe via
 * [AtomicReference] + volatile StateFlow updates.
 *
 * Lifetime: one instance per process, initialised in [JarvisApp].
 * Sessions are scoped to a single wake-word activation; the engine is reset
 * on each new session open and cleared on explicit stop / service death.
 *
 * Responsibilities:
 *  - Start / end sessions
 *  - Attach / advance [ConversationGoal]s with slot filling
 *  - Store / clear [PendingAction]s awaiting one more input
 *  - Extend expiry after successful activity
 *  - Surface a stable StateFlow for the diagnostics screen
 */
class SessionStateEngine {

    private val _session = AtomicReference<JarvisSession?>(null)
    private val _flow    = MutableStateFlow<JarvisSession?>(null)
    val sessionFlow: StateFlow<JarvisSession?> = _flow.asStateFlow()

    val current: JarvisSession?
        get() = _session.get()?.takeUnless { it.isExpired() }

    val hasActiveSession: Boolean get() = current != null

    // ── Session lifecycle ─────────────────────────────────────────────────────

    fun startSession(sessionId: String) {
        val s = JarvisSession(sessionId = sessionId)
        _session.set(s)
        _flow.value = s
        Log.d(TAG, "[SESSION_STARTED] id=$sessionId")
    }

    fun endSession() {
        val id = _session.get()?.sessionId ?: return
        _session.set(null)
        _flow.value = null
        Log.d(TAG, "[SESSION_ENDED] id=$id")
    }

    fun onUserSpeech() {
        _session.get()?.let { s ->
            s.lastUserSpeechAt = System.currentTimeMillis()
            _flow.value = s.copy()
        }
    }

    fun onAssistantSpeech() {
        _session.get()?.let { s ->
            s.lastAssistantSpeechAt = System.currentTimeMillis()
            _flow.value = s.copy()
        }
    }

    // ── Session extension ─────────────────────────────────────────────────────

    /**
     * Called after any successful command to keep the session alive.
     * [listenerTransition] controls what the session expects next.
     */
    fun extendAfterSuccess() {
        mutate { it.extendPostExecute() }
        Log.d(TAG, "[SESSION_EXTENDED] post-execute")
    }

    fun extendAwaitingSlot() {
        mutate { it.extendForSlot() }
        Log.d(TAG, "[SESSION_EXTENDED] awaiting-slot")
    }

    // ── Goal management ───────────────────────────────────────────────────────

    fun setGoal(goal: ConversationGoal) {
        mutate { s ->
            s.activeGoal = goal
            s.listeningState = if (goal.nextUnfilledSlot != null)
                ListeningState.AWAITING_SLOT else ListeningState.LISTENING
            s.extendForSlot()
        }
        Log.d(TAG, "[SESSION_GOAL_SET] type=${goal.type} slots=${goal.slots.map { it.name }}")
    }

    fun fillSlot(slotName: String, value: String): Boolean {
        val session = _session.get() ?: return false
        val goal = session.activeGoal ?: return false
        if (!goal.fillSlot(slotName, value)) return false

        goal.status = if (goal.allSlotsFilled) GoalStatus.READY_TO_EXECUTE else GoalStatus.AWAITING_SLOT
        session.listeningState = if (goal.allSlotsFilled)
            ListeningState.LISTENING else ListeningState.AWAITING_SLOT
        _flow.value = session.copy()
        Log.d(TAG, "[SESSION_SLOT_FILLED] slot=$slotName value=$value ready=${goal.allSlotsFilled}")
        return true
    }

    /** Fill the next unfilled slot by position (when only one slot is pending). */
    fun fillNextSlot(value: String): Boolean {
        val slot = _session.get()?.activeGoal?.nextUnfilledSlot ?: return false
        return fillSlot(slot.name, value)
    }

    fun markGoalExecuted() {
        mutate { s ->
            s.activeGoal?.status = GoalStatus.EXECUTED
            s.listeningState = ListeningState.LISTENING
            s.extendPostExecute()
        }
        Log.d(TAG, "[SESSION_GOAL_EXECUTED]")
    }

    fun cancelGoal() {
        mutate { s ->
            s.activeGoal?.status = GoalStatus.CANCELLED
            s.activeGoal = null
            s.pendingAction = null
            s.listeningState = ListeningState.LISTENING
            s.extendNormal()
        }
        Log.d(TAG, "[SESSION_GOAL_CANCELLED]")
    }

    fun clearGoal() {
        mutate { s -> s.activeGoal = null }
    }

    // ── Pending action ────────────────────────────────────────────────────────

    fun setPendingAction(action: PendingAction) {
        mutate { s ->
            s.pendingAction = action
            s.listeningState = ListeningState.AWAITING_SLOT
            s.extendForSlot()
        }
        Log.d(TAG, "[SESSION_PENDING_ACTION] tool=${action.toolName} slot=${action.pendingSlot}")
    }

    fun clearPendingAction() {
        mutate { s ->
            s.pendingAction = null
            if (s.listeningState == ListeningState.AWAITING_SLOT)
                s.listeningState = ListeningState.LISTENING
        }
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    fun cancelAll() {
        mutate { s ->
            s.activeGoal?.status = GoalStatus.CANCELLED
            s.activeGoal = null
            s.pendingAction = null
            s.listeningState = ListeningState.LISTENING
            s.extendNormal()
        }
        Log.d(TAG, "[SESSION_GOAL_CANCELLED] all cleared")
    }

    // ── Convenience reads ─────────────────────────────────────────────────────

    val activeGoal: ConversationGoal? get() = current?.activeGoal
    val pendingAction: PendingAction? get() = current?.pendingAction
    val listeningState: ListeningState get() = current?.listeningState ?: ListeningState.IDLE

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mutate(block: (JarvisSession) -> Unit) {
        val s = _session.get() ?: return
        block(s)
        _flow.value = s.copy()
    }
}
