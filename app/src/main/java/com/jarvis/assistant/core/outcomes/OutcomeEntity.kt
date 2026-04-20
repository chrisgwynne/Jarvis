package com.jarvis.assistant.core.outcomes

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row of an [Outcome]. Table: `outcomes`.
 *
 * Situation and goal references are denormalised as strings/ids rather than
 * foreign keys — situations have no durable id (they live in the in-memory
 * registry) and goal rows may be pruned independently.
 */
@Entity(
    tableName = "outcomes",
    indices = [
        Index("occurredAtMs"),
        Index("type"),
        Index("actionClass"),
        Index("dedupeKey"),
        Index("goalId"),
    ],
)
data class OutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val actionClass: String?,
    val dedupeKey: String?,
    val situationType: String?,
    val goalId: Long?,
    val planId: String?,
    val traceId: Long?,
    val occurredAtMs: Long,
    val detail: String?,
)
