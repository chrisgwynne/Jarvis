package com.jarvis.assistant.proactive.followup

import androidx.room.*

@Dao
interface PendingFollowUpDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(followUp: PendingFollowUp): Long

    @Update
    suspend fun update(followUp: PendingFollowUp)

    @Query("SELECT * FROM pending_followups WHERE status = 'PENDING' AND dueAt <= :now ORDER BY dueAt ASC LIMIT 5")
    suspend fun getDue(now: Long): List<PendingFollowUp>

    @Query("SELECT * FROM pending_followups WHERE status IN ('PENDING','SENT') ORDER BY dueAt ASC")
    suspend fun getActive(): List<PendingFollowUp>

    @Query("UPDATE pending_followups SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("DELETE FROM pending_followups WHERE status IN ('RESOLVED','EXPIRED','IGNORED') AND createdAt < :before")
    suspend fun pruneOld(before: Long)

    @Query("SELECT COUNT(*) FROM pending_followups WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    /** Returns how many active (PENDING/SENT) follow-ups exist for the same topic. */
    @Query("SELECT COUNT(*) FROM pending_followups WHERE status IN ('PENDING','SENT') AND topic = :topic")
    suspend fun countActiveForTopic(topic: String): Int
}
