package com.jarvis.assistant.trust

import com.jarvis.assistant.tools.framework.ToolInput

/**
 * How autonomously Jarvis may execute an action.
 */
enum class AutonomyLevel {
    /** Execute immediately — no prompt, no confirmation. */
    SAFE_AUTOMATIC,

    /**
     * Execute automatically when trust is high enough; otherwise ask a short
     * contextual confirmation.  The threshold varies by [AutonomyPreset].
     */
    CONFIRM_IF_RISKY,

    /** Always ask a short confirmation before executing. */
    ALWAYS_CONFIRM,

    /**
     * Refuse and direct the user to take the action manually.
     * Reserved for CRITICAL-risk actions (factory reset, wipe data, …).
     */
    NEVER_AUTOMATE,
}

/** User-selectable autonomy preset. */
enum class AutonomyPreset {
    /**
     * Always confirm MEDIUM-risk actions; never auto-execute anything that
     * affects other people or external systems.
     */
    CONSERVATIVE,

    /**
     * Auto-execute when trust signals are strong; confirm otherwise.
     * Default for new users.
     */
    BALANCED,

    /**
     * Minimise confirmations.  Trust the owner; only confirm HIGH-risk
     * actions.  Fast and natural — "Jarvis-style".
     */
    JARVIS_STYLE,
}

/**
 * The decision returned by [AutonomyEngine.evaluate].
 */
sealed class AutonomyDecision {
    /** Proceed immediately — no confirmation required. */
    object AutoApprove : AutonomyDecision()

    /** Speak [prompt] and wait for the user's yes/no. */
    data class Confirm(val prompt: String) : AutonomyDecision()

    /** Refuse to execute; speak [reason]. */
    data class Block(val reason: String) : AutonomyDecision()
}

// ── Threshold constants ──────────────────────────────────────────────────────

/** Trust score at or above which CONFIRM_IF_RISKY auto-approves (BALANCED). */
const val TRUST_THRESHOLD_BALANCED = 0.70f

/** Trust score at or above which CONFIRM_IF_RISKY auto-approves (JARVIS_STYLE). */
const val TRUST_THRESHOLD_JARVIS = 0.55f
