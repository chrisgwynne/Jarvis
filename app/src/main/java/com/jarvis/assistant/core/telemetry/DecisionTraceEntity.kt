package com.jarvis.assistant.core.telemetry

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent row of [DecisionTraceStore] — one per decision cycle, so we can
 * reconstruct why a proactive action did or did not fire.
 *
 * All blob-ish fields ([snapshotJson], [candidatesJson], [gatesJson]) are
 * opaque strings. Shape is chosen by the writer and may evolve; readers
 * (debug UI, bug reports) should be liberal.
 */
@Entity(
    tableName = "decision_traces",
    indices = [
        Index("createdAtMs"),
        Index("outcome"),
    ],
)
data class DecisionTraceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtMs: Long,
    val tickId: String,
    val outcome: String,
    val dispatchedDedupeKey: String?,
    val snapshotJson: String,
    val candidatesJson: String,
    val gatesJson: String,
)
