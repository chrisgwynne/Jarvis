package com.jarvis.assistant.core.goals

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row of a [Goal]. Table: `goals`.
 *
 * [evidenceJson] is a small JSON array of short strings; the codec lives in
 * [com.jarvis.assistant.core.goals.GoalStore] so entity readers (debug
 * dumps, migrations) don't depend on a JSON library of choice.
 */
@Entity(
    tableName = "goals",
    indices = [
        Index("type"),
        Index("status"),
        Index("rootDedupeKey", unique = true),
        Index("expiresAtMs"),
    ],
)
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val status: String,
    val originSituation: String?,
    val rootDedupeKey: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val expiresAtMs: Long,
    val evidenceJson: String,
)
