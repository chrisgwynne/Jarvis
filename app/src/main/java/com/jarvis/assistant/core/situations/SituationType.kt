package com.jarvis.assistant.core.situations

/**
 * SituationType — named higher-level situations the Situation Engine
 * recognises when multiple signals line up.
 *
 * Situations are distinct from [com.jarvis.assistant.proactive.ProactiveEventType]:
 * an event is a single signal (battery is low), a situation is the *meaning* of
 * multiple signals together (battery is low AND the user is about to leave
 * home). Downstream decisions (Decision layer, proactive engine, goal layer,
 * prompt) can key on a situation instead of coupling to raw signals.
 *
 * The enum is deliberately small and additive. Adding a new situation should
 * only require declaring it here and registering an evaluator rule in
 * [SituationEvaluator]; no other system should have to change.
 */
enum class SituationType {
    LOW_BATTERY_BEFORE_TRAVEL,
    LEAVING_HOME_SOON,
    ARRIVING_HOME_WITH_PENDING_TASKS,
    LIKELY_COMMUTE_START,
    IN_MEETING_AND_UNAVAILABLE,
    TIRED_LATE_NIGHT_INTERACTION,
    MISSED_CALL_WHILE_NOW_FREE,
    HEADPHONES_IN_URGENT_NOTIFICATION_CONTEXT,
    POSSIBLE_DELIVERY_WAITING,
    REPEATED_PATTERN_MOMENT,
}
