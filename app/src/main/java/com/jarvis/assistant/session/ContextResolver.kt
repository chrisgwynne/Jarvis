package com.jarvis.assistant.session

import android.util.Log
import com.jarvis.assistant.session.context.ContextBundle
import com.jarvis.assistant.session.context.RecentCalendarContext
import com.jarvis.assistant.session.context.RecentHomeAssistantContext
import com.jarvis.assistant.session.context.RecentMessageContext
import com.jarvis.assistant.session.context.RecentTodoistContext

private const val TAG = "ContextResolver"

/**
 * Resolves what "it / that / there / them" refers to in any given utterance,
 * using the active contexts from all domain stores.
 *
 * Resolution priority (highest wins):
 *  1. Visual — "show it", "send that" after camera/screenshot
 *  2. Maps navigation — "take me there", "navigate there"
 *  3. Recent app — "close it", "open it again"
 *  4. Message — "reply to that", "read it again"
 *  5. Calendar — "move that to X", "reschedule it"
 *  6. Home Assistant — "turn it off/on"
 *  7. Todoist — "mark it done", "complete that"
 *  8. Proactive — "navigate there" after proactive event
 */
class ContextResolver(private val bundle: ContextBundle) {

    sealed class Resolved {
        data class Visual(val imageFilePath: String?, val summary: String?) : Resolved()
        data class MapsDestination(val destination: String) : Resolved()
        data class RecentApp(val appName: String, val packageName: String?) : Resolved()
        data class Message(val ctx: RecentMessageContext) : Resolved()
        data class CalendarEvent(val ctx: RecentCalendarContext) : Resolved()
        data class HaEntity(val ctx: RecentHomeAssistantContext) : Resolved()
        data class TodoistTask(val ctx: RecentTodoistContext) : Resolved()
        /** Context exists but which entity is ambiguous — ask user. */
        data class Ambiguous(val reason: String) : Resolved()
        /** No context matched; fall through to normal pipeline. */
        object None : Resolved()
    }

    /**
     * Determines which entity a pronoun utterance most likely refers to.
     *
     * @param transcript The raw user utterance.
     * @param goalType   The current active goal type, if any — biases resolution.
     */
    fun resolve(transcript: String, goalType: GoalType? = null): Resolved {
        val t = transcript.lowercase()

        // Explicit domain hints take priority regardless of context availability
        if (containsNavigateHint(t)) {
            bundle.mapsNavigation?.current?.let {
                Log.d(TAG, "[CONTEXT_RESOLVED] maps-navigation dest=${it.destination}")
                return Resolved.MapsDestination(it.destination)
            }
            bundle.proactive?.current?.entityLocation?.let { loc ->
                Log.d(TAG, "[CONTEXT_RESOLVED] proactive-location loc=$loc")
                return Resolved.MapsDestination(loc)
            }
        }

        if (containsVisualHint(t)) {
            val vc = bundle.visual?.current
            if (vc != null) {
                Log.d(TAG, "[CONTEXT_RESOLVED] visual source=${vc.source}")
                return Resolved.Visual(vc.imageFilePath, vc.summary)
            }
        }

        if (containsMessageHint(t)) {
            bundle.message?.current?.let {
                Log.d(TAG, "[CONTEXT_RESOLVED] message sender=${it.sender}")
                return Resolved.Message(it)
            }
        }

        if (containsCalendarHint(t)) {
            bundle.calendar?.current?.let {
                Log.d(TAG, "[CONTEXT_RESOLVED] calendar event=${it.title}")
                return Resolved.CalendarEvent(it)
            }
        }

        if (containsHaHint(t)) {
            bundle.homeAssistant?.current?.let {
                Log.d(TAG, "[CONTEXT_RESOLVED] ha entity=${it.entityId}")
                return Resolved.HaEntity(it)
            }
        }

        if (containsTodoistHint(t)) {
            bundle.todoist?.current?.let {
                Log.d(TAG, "[CONTEXT_RESOLVED] todoist task=${it.taskContent}")
                return Resolved.TodoistTask(it)
            }
        }

        // Generic pronoun with goal hint — bias towards goal's domain
        if (containsGenericPronoun(t)) {
            return resolveByGoalType(goalType) ?: resolveByRecency()
        }

        Log.d(TAG, "[CONTEXT_RESOLVED] none matched for '$t'")
        return Resolved.None
    }

    // ── Domain hint detectors ──────────────────────────────────────────────────

    private fun containsVisualHint(t: String) =
        Regex("""show\s+(it|that|me)|open\s+(it|that|the\s+image)|send\s+(it|that|this)|read\s+(it|that)\s+again|share\s+(it|that)""").containsMatchIn(t)

    private fun containsNavigateHint(t: String) =
        Regex("""navigate\s+there|take\s+me\s+there|drive\s+there|go\s+there|directions\s+there""").containsMatchIn(t)

    private fun containsMessageHint(t: String) =
        Regex("""reply|respond\s+to|message\s+back|text\s+back|send\s+reply""").containsMatchIn(t)

    private fun containsCalendarHint(t: String) =
        Regex("""move\s+(that|it)|reschedule\s+(it|that)|change\s+(it|that)\s+to|cancel\s+(that|it)\s+event|delete\s+(that|it)\s+event""").containsMatchIn(t)

    private fun containsHaHint(t: String) =
        Regex("""turn\s+it\s+(off|on)|switch\s+it\s+(off|on)|turn\s+that\s+(off|on)|flip\s+it""").containsMatchIn(t)

    private fun containsTodoistHint(t: String) =
        Regex("""mark\s+it\s+done|complete\s+it|complete\s+that|mark\s+that\s+done|make\s+it\s+urgent|mark\s+it\s+urgent""").containsMatchIn(t)

    private fun containsGenericPronoun(t: String) =
        Regex("""(\bit\b|\bthat\b|\bthis\b|\bthem\b|\bthose\b)""").containsMatchIn(t)

    // ── Goal-biased fallback ───────────────────────────────────────────────────

    private fun resolveByGoalType(goalType: GoalType?): Resolved? = when (goalType) {
        GoalType.ANALYSE_IMAGE, GoalType.SHARE_MEDIA ->
            bundle.visual?.current?.let { Resolved.Visual(it.imageFilePath, it.summary) }
        GoalType.EDIT_CALENDAR_EVENT, GoalType.CREATE_CALENDAR_EVENT ->
            bundle.calendar?.current?.let { Resolved.CalendarEvent(it) }
        GoalType.HA_CONTROL ->
            bundle.homeAssistant?.current?.let { Resolved.HaEntity(it) }
        GoalType.REPLY_TO_MESSAGE, GoalType.SEND_MESSAGE ->
            bundle.message?.current?.let { Resolved.Message(it) }
        GoalType.EDIT_RECENT_TASK, GoalType.CREATE_TODOIST_TASK ->
            bundle.todoist?.current?.let { Resolved.TodoistTask(it) }
        GoalType.START_NAVIGATION ->
            bundle.mapsNavigation?.current?.let { Resolved.MapsDestination(it.destination) }
        else -> null
    }

    private fun resolveByRecency(): Resolved {
        // Resolve to whichever domain has the most recently set context
        data class Candidate(val resolvedAt: Long, val resolved: Resolved)
        val candidates = mutableListOf<Candidate>()

        bundle.visual?.current?.let { candidates += Candidate(it.capturedAtMs, Resolved.Visual(it.imageFilePath, it.summary)) }
        bundle.mapsNavigation?.current?.let { candidates += Candidate(it.routeLoadedAt, Resolved.MapsDestination(it.destination)) }
        bundle.message?.current?.let { candidates += Candidate(it.recordedAt, Resolved.Message(it)) }
        bundle.calendar?.current?.let { candidates += Candidate(it.recordedAt, Resolved.CalendarEvent(it)) }
        bundle.homeAssistant?.current?.let { candidates += Candidate(it.recordedAt, Resolved.HaEntity(it)) }
        bundle.todoist?.current?.let { candidates += Candidate(it.recordedAt, Resolved.TodoistTask(it)) }

        return candidates.maxByOrNull { it.resolvedAt }?.resolved
            ?: Resolved.None
    }
}
