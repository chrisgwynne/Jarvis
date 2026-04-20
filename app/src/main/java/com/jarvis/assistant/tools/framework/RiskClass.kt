package com.jarvis.assistant.tools.framework

/**
 * RiskClass — how a tool is gated by the confirmation layer.
 *
 * Consumed by [com.jarvis.assistant.core.safety.ConfirmationGate] and
 * checked by [com.jarvis.assistant.runtime.ToolDispatcher] before
 * executing any tool.
 */
enum class RiskClass {
    /** No confirmation. Safe, reversible, cheap to run (volume, torch, open app). */
    LOW,

    /**
     * Ask once. Default is decline. Short 15s TTL. Use for actions the
     * user can recover from but would regret doing by accident (end call,
     * clear notifications, media next on a party queue).
     */
    MEDIUM,

    /**
     * Ask; default is decline; only an explicit affirmative proceeds.
     * Use for actions the user cannot easily undo (send message, send
     * email, smart-home state change, delete).
     */
    HIGH,
}
