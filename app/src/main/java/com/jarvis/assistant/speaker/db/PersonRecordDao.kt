package com.jarvis.assistant.speaker.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PersonRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: PersonRecord): Long

    @Update
    suspend fun update(record: PersonRecord)

    @Query("SELECT * FROM person_records WHERE id = :id")
    suspend fun getById(id: Long): PersonRecord?

    @Query("SELECT * FROM person_records ORDER BY lastSeenAt DESC")
    suspend fun getAll(): List<PersonRecord>

    @Query("SELECT * FROM person_records WHERE isOwner = 1 LIMIT 1")
    suspend fun getOwner(): PersonRecord?

    @Query("DELETE FROM person_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM person_records WHERE LOWER(displayName) = LOWER(:name) LIMIT 1")
    suspend fun getByDisplayName(name: String): PersonRecord?
}
