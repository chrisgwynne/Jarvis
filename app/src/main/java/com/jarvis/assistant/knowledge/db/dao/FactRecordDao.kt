package com.jarvis.assistant.knowledge.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.knowledge.db.entity.FactRecord

@Dao
interface FactRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(fact: FactRecord): Long

    @Query("UPDATE fact_records SET supersededByFactId = :newFactId WHERE id = :oldFactId")
    suspend fun supersede(oldFactId: Long, newFactId: Long)

    @Query("SELECT * FROM fact_records WHERE pageId = :pageId AND supersededByFactId IS NULL")
    suspend fun getActiveForPage(pageId: Long): List<FactRecord>

    @Query("""
        SELECT * FROM fact_records
        WHERE pageId = :pageId AND predicate = :predicate AND supersededByFactId IS NULL
        LIMIT 1
    """)
    suspend fun getByPageAndPredicate(pageId: Long, predicate: String): FactRecord?

    @Query("DELETE FROM fact_records WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: Long)
}
