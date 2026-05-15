package com.jarvis.assistant.ambient

/**
 * A single ambient observation captured by [AmbientEventStore].
 *
 * Privacy: metadata must not contain raw message bodies or exact GPS
 * coordinates. Use [locationBucket] and summaries instead.
 */
data class AmbientEvent(
    val id: Long = 0L,
    val type: AmbientEventType,
    val timestampMs: Long,
    /** System that produced this event (e.g. "event_bus", "todoist", "ha"). */
    val source: String,
    /** Key/value extras — no sensitive content. */
    val metadata: Map<String, String> = emptyMap(),
    val locationBucket: AmbientLocationBucket = AmbientLocationBucket.UNKNOWN,
    /** Package name for APP_OPENED events; null otherwise. */
    val appPackage: String? = null,
    /** [0, 1] confidence in the observation. */
    val confidence: Float = 1.0f,
)
