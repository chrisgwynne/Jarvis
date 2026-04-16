package com.jarvis.assistant.followup

/**
 * FollowUpContext — in-memory store for the current active flow and a short
 * history of recently completed/cancelled flows.
 *
 * This is NOT persisted to disk.  Active flows are short-lived operational
 * state and are intentionally lost if the service restarts.
 *
 * NOT thread-safe; must be accessed on the Main dispatcher only (consistent
 * with the rest of the audio pipeline).
 */
class FollowUpContext {

    private var _activeFlow: ActiveFlow? = null
    private val _recentFlows: ArrayDeque<ActiveFlow> = ArrayDeque()

    /**
     * The current active flow, or null if there is none.
     *
     * Accessing this property automatically marks an ACTIVE flow as EXPIRED
     * and clears it if its [ActiveFlow.expiresAt] has passed.
     */
    val activeFlow: ActiveFlow?
        get() {
            val flow = _activeFlow ?: return null
            return if (flow.isExpired()) {
                if (flow.status == FlowStatus.ACTIVE) flow.status = FlowStatus.EXPIRED
                archiveAndClear()
                null
            } else {
                flow
            }
        }

    /**
     * Replace (or clear) the active flow.
     *
     * If an existing active flow is being superseded, it is marked CANCELLED
     * and moved to [recentFlows].
     */
    fun setActiveFlow(flow: ActiveFlow?) {
        _activeFlow?.let { old ->
            if (old.status == FlowStatus.ACTIVE) old.markCancelled()
            archive(old)
        }
        _activeFlow = flow
    }

    fun clearActiveFlow() = setActiveFlow(null)

    /** Up to five recently terminated flows (most recent first). */
    val recentFlows: List<ActiveFlow> get() = _recentFlows.toList()

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun archiveAndClear() {
        _activeFlow?.let { archive(it) }
        _activeFlow = null
    }

    private fun archive(flow: ActiveFlow) {
        _recentFlows.addFirst(flow)
        if (_recentFlows.size > 5) _recentFlows.removeLast()
    }
}
