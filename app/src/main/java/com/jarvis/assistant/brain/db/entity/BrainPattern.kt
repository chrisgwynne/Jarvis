package com.jarvis.assistant.brain.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * BrainPattern — a detected behavioural regularity with a confidence score.
 *
 * Patterns are upserted on [patternKey] so re-running the analyser refines
 * rather than duplicates existing records.  The [decayFactor] is reduced
 * when drift is detected (recent behaviour diverges from historical).
 *
 * Pattern types:
 *  - TIME     — event recurs in a consistent hourly window
 *  - SEQUENCE — event B reliably follows event A within a short window
 *  - CONTEXT  — event occurs under a specific context (location + time + day)
 *  - DAY      — event is significantly more frequent on weekdays or weekends
 */
@Entity(
    tableName = "brain_patterns",
    indices = [
        Index("patternKey", unique = true),
        Index("eventType"),
        Index("confidence"),
        Index("updatedAt")
    ]
)
data class BrainPattern(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Stable identifier for upsert — derived deterministically from pattern fields. */
    val patternKey: String,

    /** One of: TIME / SEQUENCE / CONTEXT / DAY. */
    val patternType: String,

    /** Primary event type this pattern describes. */
    val eventType: String,

    /** For SEQUENCE patterns: the trigger event that precedes [eventType]. */
    val triggerEventType: String? = null,

    /** Start of the observed time window, e.g. "22:00". */
    val timeWindowStart: String? = null,

    /** End of the observed time window, e.g. "22:59". */
    val timeWindowEnd: String? = null,

    /** Location context: "home" / "away" / "any". */
    val locationContext: String? = null,

    /** Day context: "weekday" / "weekend" / "any". */
    val dayContext: String? = null,

    /** Number of times this pattern was observed in the analysis window. */
    val occurrenceCount: Int,

    /** Total eligible slots checked (denominator for confidence). */
    val totalChecks: Int,

    /** Raw confidence: occurrenceCount / totalChecks, range 0.0–1.0. */
    val confidence: Float,

    /** Human-readable label: "low" / "medium" / "high" / "very_high". */
    val confidenceLabel: String,

    /** Natural-language description used for suggestions and LLM context. */
    val humanDescription: String,

    /** Epoch ms of the most recent event that matched this pattern. */
    val lastSeen: Long,

    /** Epoch ms when this record was first created. */
    val createdAt: Long,

    /** Epoch ms when this record was last updated by the analyser. */
    val updatedAt: Long,

    /**
     * Multiplied into confidence when making dispatch decisions.
     * Reduced toward 0 when drift is detected; restored as new data arrives.
     */
    val decayFactor: Float = 1.0f,

    /** Epoch ms when a suggestion was last dispatched for this pattern. */
    val lastSuggestedAt: Long = 0L,

    /** How many times the user accepted a suggestion from this pattern. */
    val acceptCount: Int = 0
) {
    /** Effective confidence after applying decay. */
    val effectiveConfidence: Float get() = confidence * decayFactor
}
