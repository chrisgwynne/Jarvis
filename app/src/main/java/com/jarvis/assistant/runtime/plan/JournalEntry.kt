package com.jarvis.assistant.runtime.plan

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * JournalEntry — one row per executed [PlannedStep], capturing enough state
 * for [PlanRunner.undoLastPlan] to walk back through the steps in reverse.
 *
 * Storage layout:
 *   * one (planId, ordinal) per step
 *   * status moves PENDING → SUCCEEDED | FAILED | UNDONE | SKIPPED
 *   * undoPayload is whatever the tool wrote into ToolResult.Success.rawData,
 *     plus the original argsJson — enough for the tool's own undo() to
 *     rehydrate without reconstructing the world.
 */
@Entity(
    tableName = "action_journal",
    indices   = [
        Index(value = ["planId"]),
        Index(value = ["status"]),
        Index(value = ["createdAtMs"])
    ]
)
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: String,
    val ordinal: Int,
    val toolName: String,
    val argsJson: String,
    val undoPayload: String,
    val status: String,
    val originatingTranscript: String,
    val createdAtMs: Long,
    val completedAtMs: Long?
) {
    companion object {
        const val STATUS_PENDING   = "PENDING"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED    = "FAILED"
        const val STATUS_UNDONE    = "UNDONE"
        const val STATUS_SKIPPED   = "SKIPPED"
    }
}
