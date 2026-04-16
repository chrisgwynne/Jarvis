package com.jarvis.assistant.knowledge.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.knowledge.db.entity.ContradictionRecord

@Dao
interface ContradictionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(contradiction: ContradictionRecord): Long

    @Query("SELECT * FROM contradictions WHERE status = 'UNRESOLVED'")
    suspend fun getUnresolved(): List<ContradictionRecord>

    @Query("SELECT * FROM contradictions WHERE pageId = :pageId")
    suspend fun getByPage(pageId: Long): List<ContradictionRecord>

    @Query("UPDATE contradictions SET status = :status WHERE id = :id")
    suspend fun resolve(id: Long, status: String)

    @Query("DELETE FROM contradictions WHERE status != 'UNRESOLVED' AND createdAt < :cutoffMs")
    suspend fun pruneResolved(cutoffMs: Long): Int
}
