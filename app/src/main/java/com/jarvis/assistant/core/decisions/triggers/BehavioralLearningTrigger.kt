package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveEventType

class BehavioralLearningTrigger : Trigger {
    override val id: String = "behavioral_learning"
    override val actionClass: String? = null

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val snapshot = ctx.proactive
        val description = snapshot.topPredictionDescription ?: return null
        if (snapshot.topPredictionScore < 0.60f) return null
        if (snapshot.isJarvisSpeaking || snapshot.isJarvisListening) return null

        val knowledge = snapshot.predictionKnowledgeContext
        val spokenText = buildString {
            append(description.trimEnd('.', '!', '?'))
            append('.')
            if (!knowledge.isNullOrBlank()) append(" ${knowledge.take(120).trimEnd('.')}.")
        }

        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.BEHAVIORAL_LEARNING,
            title = "Habit insight",
            spokenText = spokenText,
            urgency = (snapshot.topPredictionScore * 0.7f).coerceAtMost(0.65f),
            relevance = snapshot.topPredictionScore,
            confidence = snapshot.topPredictionScore,
            annoyanceCost = 0.40f,
            dedupeKey = "brain_ctx_${description.hashCode().toUInt().toString(16)}",
            actionClass = actionClass,
            metadata = mapOf(
                "predictionScore" to snapshot.topPredictionScore.toString(),
                "description" to description,
            ),
        )
    }
}
