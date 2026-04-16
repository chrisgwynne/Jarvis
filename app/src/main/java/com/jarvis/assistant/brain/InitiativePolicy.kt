package com.jarvis.assistant.brain

import com.jarvis.assistant.brain.db.dao.BrainPatternDao

/**
 * InitiativePolicy — the DECIDE layer.
 *
 * Maps a [PredictionEngine.Prediction] to an [InitiativeAction] that determines
 * what (if anything) Jarvis does with it.
 *
 * Decision levels (per spec §8):
 *   0 — OBSERVE      : do nothing (low confidence or low value)
 *   1 — SILENT_PREP  : prepare without telling the user
 *   2 — SUGGEST      : light spoken hint, no action required
 *   3 — OFFER_ACTION : offer to perform the action for the user
 *   4 — AUTO_ACT     : act immediately (only for very-high confidence + reversible)
 *
 * RESTRAINT RULE: when in doubt, do nothing (§12).
 */
class InitiativePolicy(
    private val patternDao: BrainPatternDao
) {
    sealed class InitiativeAction {
        object Observe : InitiativeAction()
        object SilentPrep : InitiativeAction()
        data class Suggest(val text: String, val patternKey: String) : InitiativeAction()
        data class OfferAction(val text: String, val actionHint: String, val patternKey: String) : InitiativeAction()
        data class AutoAct(val actionHint: String, val patternKey: String) : InitiativeAction()
    }

    companion object {
        // Confidence thresholds by level
        private const val SUGGEST_THRESHOLD     = 0.60f
        private const val OFFER_THRESHOLD       = 0.80f
        private const val AUTO_ACT_THRESHOLD    = 0.90f

        // Only event types that are reversible / non-intrusive qualify for AutoAct
        private val AUTO_ACT_ELIGIBLE = setOf(
            BrainEventType.MEDIA_PLAY_START.name  // "Open Spotify" — user can dismiss easily
        )

        // These event types warrant a suggestion (not all types are useful to mention)
        private val SUGGEST_ELIGIBLE = setOf(
            BrainEventType.CHARGER_CONNECTED.name,
            BrainEventType.MEDIA_PLAY_START.name,
            BrainEventType.SCREEN_OFF.name,
            BrainEventType.ALARM_SET.name
        )
    }

    /**
     * Decide what to do with a prediction.
     *
     * @param prediction  The ranked prediction from [PredictionEngine].
     * @param isSpeaking  True if Jarvis is currently speaking — never interrupt.
     * @param isListening True if in an active conversation — never interrupt.
     */
    suspend fun decide(
        prediction: PredictionEngine.Prediction,
        isSpeaking: Boolean,
        isListening: Boolean
    ): InitiativeAction {
        // RESTRAINT RULE: never interrupt active sessions
        if (isSpeaking || isListening) return InitiativeAction.Observe

        // Only eligible event types get surface-level actions
        if (prediction.eventType !in SUGGEST_ELIGIBLE) return InitiativeAction.Observe

        val score = prediction.score
        val p     = prediction.pattern

        return when {
            score >= AUTO_ACT_THRESHOLD && prediction.eventType in AUTO_ACT_ELIGIBLE -> {
                // Auto-act: only if user has accepted before (acceptCount > 0)
                if (p.acceptCount > 0) {
                    InitiativeAction.AutoAct(
                        actionHint = prediction.eventType,
                        patternKey = p.patternKey
                    )
                } else {
                    // First time — fall back to offer
                    InitiativeAction.OfferAction(
                        text       = buildOfferText(prediction),
                        actionHint = prediction.eventType,
                        patternKey = p.patternKey
                    )
                }
            }
            score >= OFFER_THRESHOLD -> {
                InitiativeAction.OfferAction(
                    text       = buildOfferText(prediction),
                    actionHint = prediction.eventType,
                    patternKey = p.patternKey
                )
            }
            score >= SUGGEST_THRESHOLD -> {
                InitiativeAction.Suggest(
                    text       = buildSuggestionText(prediction),
                    patternKey = p.patternKey
                )
            }
            else -> InitiativeAction.Observe
        }
    }

    // ── Spoken text builders ──────────────────────────────────────────────────

    private fun buildSuggestionText(pred: PredictionEngine.Prediction): String {
        return when (pred.eventType) {
            BrainEventType.CHARGER_CONNECTED.name ->
                "You usually plug in around now."
            BrainEventType.SCREEN_OFF.name ->
                "You tend to wind down around this time."
            BrainEventType.ALARM_SET.name ->
                "Want me to set your usual alarm?"
            BrainEventType.MEDIA_PLAY_START.name ->
                "You often play music around now."
            else -> pred.reasoning.take(120)
        }
    }

    private fun buildOfferText(pred: PredictionEngine.Prediction): String {
        return when (pred.eventType) {
            BrainEventType.MEDIA_PLAY_START.name -> {
                val btDevice = pred.pattern.triggerEventType
                    ?.let { if (it == BrainEventType.BLUETOOTH_CONNECTED.name) " since you just connected Bluetooth" else "" }
                    ?: ""
                "Want me to open Spotify${btDevice}?"
            }
            BrainEventType.CHARGER_CONNECTED.name ->
                "Your battery is getting low and you usually charge around now — want a reminder?"
            BrainEventType.ALARM_SET.name ->
                "Want me to set your usual alarm for tomorrow?"
            else -> buildSuggestionText(pred)
        }
    }
}
