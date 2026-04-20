package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.core.routines.RoutineSynthesizer
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * RoutineProposalTrigger — surfaces proposals from [RoutineSynthesizer] as
 * proactive suggestions ("You've done X then Y around this time a few
 * times — save it as a routine?"). Each proposal fires once; the
 * synthesiser itself flags it as proposed so re-triggering requires a
 * fresh pattern.
 */
class RoutineProposalTrigger(
    private val synthesizer: RoutineSynthesizer,
) : Trigger {
    override val id: String = "routine_proposal"
    override val actionClass: String = "BRAIN"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val proposals = synthesizer.drainProposals()
        val top = proposals.firstOrNull() ?: return null
        val stepList = top.steps.joinToString(" then ") { it.replace('_', ' ') }
        return Candidate(
            triggerId = id,
            eventType = ProactiveEventType.BEHAVIORAL_LEARNING,
            title = "Routine suggestion",
            spokenText = "You've been doing $stepList a few times around this hour. Save it as a routine?",
            urgency = 0.30f,
            relevance = 0.65f,
            confidence = 0.80f,
            annoyanceCost = 0.40f,
            dedupeKey = "routine_proposal_${top.id}",
            actionClass = actionClass,
            metadata = mapOf(
                "sequence_key" to top.id,
                "steps" to top.steps.joinToString("|"),
                "observations" to top.observations.toString(),
            ),
        )
    }
}
