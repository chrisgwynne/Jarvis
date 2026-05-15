package com.jarvis.assistant.trust

import android.util.Log
import com.jarvis.assistant.tools.framework.ToolInput

private const val TAG = "AutonomyEngine"

/**
 * Central decision engine for Jarvis's trust and autonomy system.
 *
 * Replaces the inline RiskClass × ConfidenceTier check in [ToolDispatcher]
 * with a context-aware, learned, user-configurable policy.
 *
 * Pipeline (per evaluation):
 *   1. Classify tool → [ActionRiskCategory] (action-aware)
 *   2. Evaluate trust signals → [TrustScore]
 *   3. Derive effective [AutonomyLevel] for (category, preset, context)
 *   4. Apply learned patterns + user overrides
 *   5. Return [AutonomyDecision]: AutoApprove | Confirm(prompt) | Block(reason)
 *
 * All non-Android logic is pure/stateless — the engine is easily unit-tested.
 */
class AutonomyEngine(
    private val settingsRepo: AutonomySettingsRepository,
    private val learnedStore: LearnedTrustStore,
) {

    /**
     * Evaluate whether [toolName] with [input] may execute under [trustCtx].
     *
     * @param toolName   machine name of the tool being dispatched
     * @param input      the matched tool input (for action-aware classification)
     * @param trustCtx   live trust signals from the runtime
     */
    fun evaluate(
        toolName: String,
        input: ToolInput,
        trustCtx: TrustContext,
    ): AutonomyDecision {
        val settings = settingsRepo.snapshot()
        val category = ActionRiskCategory.classify(toolName, input)
        val trust    = TrustSignalEvaluator.evaluate(trustCtx)

        Log.d(TAG, "[AUTONOMY_EVALUATE] tool=$toolName category=$category " +
            "mode=${trust.mode} score=%.2f preset=${settings.preset}".format(trust.value))

        // CRITICAL: always block — direct the user to do it manually
        if (category == ActionRiskCategory.CRITICAL) {
            Log.d(TAG, "[AUTONOMY_BLOCK] tool=$toolName reason=CRITICAL")
            return AutonomyDecision.Block(
                "That needs to be done manually — I won't automate it."
            )
        }

        // Lockscreen restrictions: block sensitive sends/reads
        if (trust.mode == TrustMode.LOCKSCREEN_LIMITED &&
            settings.lockscreenRestrictions &&
            isLockedRestricted(toolName)) {
            Log.d(TAG, "[AUTONOMY_BLOCK] tool=$toolName reason=LOCKSCREEN")
            return AutonomyDecision.Block(
                "I can't do that while the screen's locked."
            )
        }

        // Unknown speaker: restrict medium+ actions involving other people
        if (trust.mode == TrustMode.UNKNOWN_SPEAKER &&
            category >= ActionRiskCategory.MEDIUM_RISK &&
            isAffectsOthers(toolName)) {
            val prompt = ConfirmationPromptBuilder.build(toolName, input)
            Log.d(TAG, "[AUTONOMY_CONFIRM] tool=$toolName reason=UNKNOWN_SPEAKER")
            return AutonomyDecision.Confirm(prompt)
        }

        // User explicitly requested "always ask" for this tool
        if (learnedStore.requiresConfirmation(toolName)) {
            val prompt = ConfirmationPromptBuilder.build(toolName, input)
            Log.d(TAG, "[AUTONOMY_CONFIRM] tool=$toolName reason=USER_ALWAYS_ASK")
            return AutonomyDecision.Confirm(prompt)
        }

        // Per-category user overrides (settings toggles)
        val forcedConfirm = when (toolName) {
            "send_sms", "sms_send", "sms",
            "whatsapp_message", "whatsapp_send", "whatsapp",
            "email_send", "email"       -> settings.requireConfirmForMessages
            "call_contact", "call"      -> settings.requireConfirmForCalls
            "share_media", "visual_followup" -> settings.requireConfirmForMediaShare
            else                         -> false
        }
        if (forcedConfirm) {
            val prompt = ConfirmationPromptBuilder.build(toolName, input)
            Log.d(TAG, "[AUTONOMY_CONFIRM] tool=$toolName reason=SETTINGS_OVERRIDE")
            return AutonomyDecision.Confirm(prompt)
        }

        // Car mode: maximise autonomy for LOW/MEDIUM actions
        if (trust.mode == TrustMode.CAR_MODE && settings.carModeAutonomy &&
            category <= ActionRiskCategory.MEDIUM_RISK) {
            Log.d(TAG, "[AUTONOMY_ALLOW] tool=$toolName reason=CAR_MODE")
            learnedStore.recordAutoApproval(toolName)
            return AutonomyDecision.AutoApprove
        }

        val effectiveLevel = effectiveLevel(category, trust, settings, toolName)

        return when (effectiveLevel) {
            AutonomyLevel.SAFE_AUTOMATIC -> {
                Log.d(TAG, "[AUTONOMY_ALLOW] tool=$toolName level=SAFE_AUTOMATIC")
                learnedStore.recordAutoApproval(toolName)
                AutonomyDecision.AutoApprove
            }
            AutonomyLevel.CONFIRM_IF_RISKY -> {
                val threshold = when (settings.preset) {
                    AutonomyPreset.JARVIS_STYLE -> TRUST_THRESHOLD_JARVIS
                    else                        -> TRUST_THRESHOLD_BALANCED
                }
                // Learned pattern: user never corrects this → auto-approve
                val learnedSkip = learnedStore.shouldSkipConfirmation(toolName)
                if (trust.value >= threshold || learnedSkip) {
                    Log.d(TAG, "[AUTONOMY_ALLOW] tool=$toolName level=CONFIRM_IF_RISKY " +
                        "score=%.2f learned=$learnedSkip".format(trust.value))
                    learnedStore.recordAutoApproval(toolName)
                    AutonomyDecision.AutoApprove
                } else {
                    val prompt = ConfirmationPromptBuilder.build(toolName, input)
                    Log.d(TAG, "[AUTONOMY_CONFIRM] tool=$toolName score=%.2f threshold=%.2f"
                        .format(trust.value, threshold))
                    AutonomyDecision.Confirm(prompt)
                }
            }
            AutonomyLevel.ALWAYS_CONFIRM -> {
                val prompt = ConfirmationPromptBuilder.build(toolName, input)
                Log.d(TAG, "[AUTONOMY_CONFIRM] tool=$toolName level=ALWAYS_CONFIRM")
                AutonomyDecision.Confirm(prompt)
            }
            AutonomyLevel.NEVER_AUTOMATE -> {
                Log.d(TAG, "[AUTONOMY_BLOCK] tool=$toolName level=NEVER_AUTOMATE")
                AutonomyDecision.Block("That action requires manual intervention.")
            }
        }
    }

    // ── Level derivation ──────────────────────────────────────────────────────

    private fun effectiveLevel(
        category: ActionRiskCategory,
        trust: TrustScore,
        settings: AutonomySettings,
        toolName: String,
    ): AutonomyLevel {
        val base = baseLevel(category, settings.preset)

        // HIGH trust + home/headphones mode can relax MEDIUM to auto
        if (base == AutonomyLevel.CONFIRM_IF_RISKY) {
            val trustedMode = trust.mode in setOf(
                TrustMode.OWNER_TRUSTED,
                TrustMode.HOME_TRUSTED,
                if (settings.headphonesPrivateMode) TrustMode.HEADPHONES_PRIVATE else null,
                if (settings.homeTrustedMode)       TrustMode.HOME_TRUSTED       else null,
            )
            if (trustedMode && trust.isHighTrust) return AutonomyLevel.SAFE_AUTOMATIC
        }

        return base
    }

    private fun baseLevel(
        category: ActionRiskCategory,
        preset: AutonomyPreset,
    ): AutonomyLevel = when (category) {
        ActionRiskCategory.LOW_RISK  -> AutonomyLevel.SAFE_AUTOMATIC
        ActionRiskCategory.MEDIUM_RISK -> when (preset) {
            AutonomyPreset.CONSERVATIVE -> AutonomyLevel.ALWAYS_CONFIRM
            AutonomyPreset.BALANCED     -> AutonomyLevel.CONFIRM_IF_RISKY
            AutonomyPreset.JARVIS_STYLE -> AutonomyLevel.CONFIRM_IF_RISKY
        }
        ActionRiskCategory.HIGH_RISK -> AutonomyLevel.ALWAYS_CONFIRM
        ActionRiskCategory.CRITICAL  -> AutonomyLevel.NEVER_AUTOMATE
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isLockedRestricted(toolName: String): Boolean = toolName in setOf(
        "send_sms", "sms_send", "sms",
        "whatsapp_message", "whatsapp_send", "whatsapp",
        "email_send", "email",
        "call_contact", "call",
        "share_media", "visual_followup",
        "read_sms", "read_notifications",
    )

    private fun isAffectsOthers(toolName: String): Boolean = toolName in setOf(
        "send_sms", "sms_send", "sms",
        "whatsapp_message", "whatsapp_send", "whatsapp",
        "email_send", "email",
        "call_contact", "call",
        "share_media",
    )
}

// Make ActionRiskCategory comparable for >= checks
operator fun ActionRiskCategory.compareTo(other: ActionRiskCategory): Int =
    this.ordinal.compareTo(other.ordinal)
