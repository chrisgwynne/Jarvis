package com.jarvis.assistant.proactive

import android.util.Log
import com.jarvis.assistant.brain.BrainEngine
import com.jarvis.assistant.knowledge.KnowledgeQueryEngine

/**
 * AppBrainPredictionSource — queries [BrainEngine] for top predictions and
 * optionally enriches them with knowledge context from [KnowledgeQueryEngine].
 *
 * [brainEngineProvider] is a lambda so this source can be constructed before
 * [BrainEngine] is initialized (deferred lookup — safe as long as the polling
 * loop starts after full initialization).
 */
class AppBrainPredictionSource(
    private val brainEngineProvider: () -> BrainEngine?,
    private val knowledgeQueryEngine: KnowledgeQueryEngine? = null
) : BrainPredictionSource {

    companion object {
        private const val TAG       = "AppBrainPredictionSource"
        private const val MIN_SCORE = 0.60f
    }

    override suspend fun getTopPrediction(): BrainPrediction? {
        return try {
            val predictions = brainEngineProvider()?.predict() ?: return null
            val top = predictions.firstOrNull { it.score >= MIN_SCORE } ?: return null

            val knowledge = knowledgeQueryEngine?.let { engine ->
                try {
                    engine.retrieveContext(top.reasoning).takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    Log.w(TAG, "Knowledge enrichment failed: ${e.message}")
                    null
                }
            }

            Log.d(TAG, "Top prediction: ${top.eventType} score=${top.score} knowledge=${knowledge != null}")
            BrainPrediction(
                description      = top.reasoning,
                score            = top.score,
                eventType        = top.eventType,
                knowledgeContext = knowledge
            )
        } catch (e: Exception) {
            Log.w(TAG, "Prediction failed: ${e.message}")
            null
        }
    }
}
