package com.jarvis.assistant.core.routines

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SavedRoutine — a named, persistent sequence of tool calls the user (or
 * future synthesiser) has promoted from ad-hoc to reusable.
 *
 * Storage shape mirrors [com.jarvis.assistant.runtime.plan.PlannedStep]
 * JSON so [com.jarvis.assistant.runtime.plan.PlanRunner] can execute
 * a routine by materialising the stored steps into a fresh Plan.
 *
 * [stepsJson] is a JSON array of
 * `{toolName, argsJson, shortLabel, reversible}` records — the same
 * shape PlannedStep already uses, so we don't need Room type converters.
 */
@Entity(
    tableName = "saved_routines",
    indices = [
        Index(value = ["nameNormalized"], unique = true),
        Index("createdAtMs"),
    ],
)
data class SavedRoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameNormalized: String,
    val stepsJson: String,
    val createdAtMs: Long,
    val lastRunAtMs: Long? = null,
    val runCount: Int = 0,
)
