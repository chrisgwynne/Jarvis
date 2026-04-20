package com.jarvis.assistant.runtime.plan

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ActionJournalDao {

    @Insert
    suspend fun insert(entry: JournalEntry): Long

    @Query("UPDATE action_journal SET status = :status, completedAtMs = :completedAtMs WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, completedAtMs: Long)

    @Query("UPDATE action_journal SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    /** Update the undo payload for a journal row after [Tool.execute]. */
    @Query("UPDATE action_journal SET undoPayload = :payload WHERE id = :id")
    suspend fun updatePayload(id: Long, payload: String)

    /** Most recent halted plan — used by rollback to reverse partial work. */
    @Query("""
        SELECT planId FROM action_journal
        WHERE status = 'FAILED'
        ORDER BY createdAtMs DESC
        LIMIT 1
    """)
    suspend fun mostRecentFailedPlanId(): String?

    /** All steps for a single plan, in execution order. */
    @Query("SELECT * FROM action_journal WHERE planId = :planId ORDER BY ordinal ASC")
    suspend fun forPlan(planId: String): List<JournalEntry>

    /** The most recently created plan id that has at least one SUCCEEDED step. */
    @Query("""
        SELECT planId FROM action_journal
        WHERE status = 'SUCCEEDED'
        ORDER BY createdAtMs DESC
        LIMIT 1
    """)
    suspend fun mostRecentSucceededPlanId(): String?

    /** Total step count for a plan — used to decide if anything is left to undo. */
    @Query("SELECT COUNT(*) FROM action_journal WHERE planId = :planId AND status = 'SUCCEEDED'")
    suspend fun countSucceeded(planId: String): Int

    /**
     * Trim journal rows older than [olderThanMs] so a long-lived install
     * doesn't accumulate the entire history.  Undo only ever walks the most
     * recent plan, so anything past the retention window is unreachable.
     */
    @Query("DELETE FROM action_journal WHERE createdAtMs < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long)
}
