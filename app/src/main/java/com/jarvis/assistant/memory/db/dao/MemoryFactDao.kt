package com.jarvis.assistant.memory.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.memory.db.entity.FactCategory
import com.jarvis.assistant.memory.db.entity.MemoryFact

@Dao
interface MemoryFactDao {

    @Query("SELECT * FROM memory_facts WHERE factKey = :key LIMIT 1")
    suspend fun getByKey(key: String): MemoryFact?

    @Query("SELECT * FROM memory_facts WHERE category = :category ORDER BY lastUpdatedAt DESC")
    suspend fun getByCategory(category: FactCategory): List<MemoryFact>

    @Query("SELECT * FROM memory_facts ORDER BY lastUpdatedAt DESC")
    suspend fun getAll(): List<MemoryFact>

    /** Insert or replace — factKey has a unique index so this acts as upsert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: MemoryFact)

    @Query("DELETE FROM memory_facts WHERE factKey = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM memory_facts")
    suspend fun deleteAll()
}
