package com.jarvis.assistant.followup

enum class FlowStatus {
    /** Actively collecting slots or awaiting execution. */
    ACTIVE,
    /** All slots filled, action executed successfully. */
    COMPLETED,
    /** User explicitly cancelled, or flow was superseded. */
    CANCELLED,
    /** Timed out without completion. */
    EXPIRED
}
